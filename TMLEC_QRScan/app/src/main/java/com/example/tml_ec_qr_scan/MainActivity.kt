package com.example.tml_ec_qr_scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import boofcv.android.ConvertBitmap
import boofcv.factory.fiducial.FactoryFiducial
import boofcv.struct.image.GrayU8
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private val viewModel: MainViewModel by viewModels()
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var previewView: PreviewView

    // Track recording session
    private var recordingStartTime = 0L
    private val smoothedCenters = mutableMapOf<String, TrackedPoint>()
    private val lastPhaseChangeTimestamp = mutableMapOf<String, Long>()

    // Initialize breathing classifier
    private lateinit var breathingClassifier: BreathingClassifier

    // YOLO chest detection components
    private var yoloChestDetector: YoloChestDetector? = null
    private var chestTracker: ChestTracker? = null

    // NFC support for patient data
    private lateinit var nfcManager: NFCManager

    // Calibration related variables
    private var isCalibrating = false
    private var calibrationStartTime = 0L
    private var calibrationData = mutableListOf<Float>()
    private var calibrationVelocityThresholds =
            CalibrationThresholds(
                    inhaleThreshold = -8f,
                    exhaleThreshold = 8f,
                    pauseThresholdLow = -3f,
                    pauseThresholdHigh = 3f
            )
    private val CALIBRATION_DURATION_MS = 10000

    // Training mode flag
    private var isTrainingDataMode = false

    // Callback for image analysis
    private var qrCodeDetectionCallback: ((List<BoofCvQrDetection>, Float, Float, Int) -> Unit)? =
            null

    // QR Grid calibration state
    private var isGridCalibrating = false
    private var gridCalibrationPositions = mutableMapOf<String, Offset>()
    private val GRID_CALIBRATION_DURATION = 5000L // 5 seconds

    // Simple QR alignment guide - no complex grid system needed
    private var showAlignmentGuide = true

    @SuppressLint("StateFlowValueCalledInComposition")
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize components
        cameraExecutor = Executors.newSingleThreadExecutor()
        previewView =
                PreviewView(this).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

        breathingClassifier = BreathingClassifier(this)

        // Initialize YOLO detector
        try {
            yoloChestDetector = YoloChestDetector(this)
            chestTracker = ChestTracker()
            Log.d("MainActivity", "✅ YOLO detector initialized")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ YOLO detector failed: ${e.message}")
            yoloChestDetector = null
            chestTracker = null
        }

        // Initialize NFC manager for patient data
        nfcManager = NFCManager(this)

        // Check if launched from NFC intent
        handleNFCIntent(intent)

        viewModel.setCalibrationCompleter { completeCalibration() }
        requestCameraPermissionIfNeeded()

        setContent {
            com.example.tml_ec_qr_scan.ui.theme.TMLEC_QRScanTheme {
                Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                ) {
                    // State variables
                    var qrDetections by remember { mutableStateOf(emptyList<BoofCvQrDetection>()) }
                    var imageWidth by remember { mutableStateOf(1280f) }
                    var imageHeight by remember { mutableStateOf(720f) }
                    var rotationDegrees by remember { mutableStateOf(0) }

                    // Set up QR detection callback
                    DisposableEffect(Unit) {
                        val disposable =
                                object {
                                    var active = true
                                }

                        fun updateQrDetections(
                                detections: List<BoofCvQrDetection>,
                                width: Float,
                                height: Float,
                                rotation: Int
                        ) {
                            if (disposable.active) {
                                qrDetections = detections
                                imageWidth = width
                                imageHeight = height
                                rotationDegrees = rotation
                            }
                        }

                        qrCodeDetectionCallback = { detections, width, height, rotation ->
                            updateQrDetections(detections, width, height, rotation)
                        }

                        onDispose { disposable.active = false }
                    }

                    // Camera initialization effects
                    val uiState by viewModel.uiState.collectAsState()
                    LaunchedEffect(uiState) {
                        if (uiState is UiState.Calibrating || uiState is UiState.Recording) {
                            if (cameraProvider == null) {
                                initializeCamera()
                            } else {
                                bindCameraUseCases()
                            }
                        }
                    }

                    val isCameraStarted by viewModel.isCameraStarted.collectAsState()
                    LaunchedEffect(isCameraStarted) {
                        if (isCameraStarted && uiState is UiState.CameraSetup) {
                            if (cameraProvider == null) {
                                initializeCamera()
                            } else {
                                bindCameraUseCases()
                            }
                        }
                    }

                    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
                    LaunchedEffect(isFrontCamera) {
                        if (cameraProvider != null) {
                            bindCameraUseCases()
                        }
                    }

                    // Main screen content
                    com.example.tml_ec_qr_scan.MainScreen(
                            viewModel = viewModel,
                            onStartRecording = { startRecording() },
                            onStopRecording = { stopRecording() },
                            onForceBreathingUpdate = { forceBreathingUpdate() },
                            onSaveData = { saveData() },
                            onNewPatient = { newPatient() },
                            onStartCalibration = { startCalibration() },
                            onToggleTrainingMode = { toggleTrainingMode() },
                            onSaveGraph = { saveRespirationChartAsImage() },
                            previewView = {
                                CameraPreviewWithOverlays(
                                        qrDetections = qrDetections,
                                        imageWidth = imageWidth,
                                        imageHeight = imageHeight,
                                        rotationDegrees = rotationDegrees
                                )
                            }
                    )
                }
            }
        }
    }

    @Composable
    private fun CameraPreviewWithOverlays(
            qrDetections: List<BoofCvQrDetection>,
            imageWidth: Float,
            imageHeight: Float,
            rotationDegrees: Int
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Camera preview
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            // QR/YOLO overlays based on tracking mode
            val trackingMode by viewModel.currentTrackingMode.collectAsState()
            when (trackingMode) {
                TrackingMode.QR_TRACKING -> {
                    if (qrDetections.isNotEmpty()) {
                        BoofCVQRCodeOverlay(
                                qrDetections = qrDetections,
                                imageWidth = imageWidth,
                                imageHeight = imageHeight,
                                rotationDegrees = rotationDegrees,
                                modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Show alignment guide for QR positioning
                    if (showAlignmentGuide) {
                        QRAlignmentGuide(modifier = Modifier.fillMaxSize())
                    }
                }
                TrackingMode.YOLO_TRACKING -> {
                    // FIXED: During calibration, show QR overlay even in YOLO mode
                    if (isCalibrating && qrDetections.isNotEmpty()) {
                        BoofCVQRCodeOverlay(
                                qrDetections = qrDetections,
                                imageWidth = imageWidth,
                                imageHeight = imageHeight,
                                rotationDegrees = rotationDegrees,
                                modifier = Modifier.fillMaxSize()
                        )
                    } else if (!isCalibrating) {
                        // Normal YOLO mode when not calibrating
                        val chestDetection by viewModel.chestDetection.collectAsState()
                        chestDetection?.let { detection ->
                            ChestOverlay(
                                    chestDetection = detection,
                                    imageWidth = imageWidth,
                                    imageHeight = imageHeight,
                                    rotationDegrees = rotationDegrees,
                                    modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            // Breathing phase display
            val breathingPhase by viewModel.currentBreathingPhase.collectAsState()
            val confidence by viewModel.breathingConfidence.collectAsState()
            val currentVelocity by viewModel.currentVelocity.collectAsState()
            val isRecording by viewModel.isRecording.collectAsState()

            // Show QR alignment instruction when in QR tracking mode and not recording
            if (trackingMode == TrackingMode.QR_TRACKING && showAlignmentGuide && !isRecording) {
                Box(
                        modifier =
                                Modifier.align(Alignment.TopCenter)
                                        .padding(top = 32.dp, start = 16.dp, end = 16.dp)
                                        .background(
                                                Color.Black.copy(alpha = 0.7f),
                                                shape = MaterialTheme.shapes.small
                                        )
                                        .padding(12.dp)
                ) {
                    Text(
                            text = "🎯 Center your QR code on the red dot for optimal tracking",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            if (breathingPhase != "Unknown") {
                Box(
                        modifier =
                                Modifier.align(Alignment.TopEnd)
                                        .padding(
                                                top = 80.dp,
                                                end = 16.dp,
                                                start = 16.dp
                                        ) // Increased from 32dp to 80dp
                                        .background(
                                                Color.Black.copy(alpha = 0.7f),
                                                shape = MaterialTheme.shapes.small
                                        )
                                        .padding(8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                                text =
                                        when (breathingPhase.lowercase()) {
                                            "exhaling" -> " Exhaling"
                                            "inhaling" -> "Inhaling"
                                            "pause" -> "Pause"
                                            else -> "🔄 Detecting movement..."
                                        },
                                color =
                                        when (breathingPhase.lowercase()) {
                                            "exhaling" -> Color(0xFF2196F3) // Blue for exhaling
                                            "inhaling" -> Color(0xFF4CAF50) // Green for inhaling
                                            "pause" -> Color(0xFFFFC107) // Yellow for pause
                                            else -> Color(0xFFFFFFFF) // White for unknown
                                        },
                                style =
                                        MaterialTheme.typography
                                                .bodyLarge, // Changed from titleLarge
                                fontWeight = FontWeight.Medium // Changed from Bold
                        )

                        // Show velocity for debugging if needed
                        Text(
                                text = "Movement: ${String.format("%.1f", currentVelocity)} px/s",
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall
                        )

                        // Recording status
                        //                        if (isRecording) {
                        //                            Text(
                        //                                    text = "🔴 RECORDING ACTIVE",
                        //                                    color = Color(0xFF4CAF50),
                        //                                    style =
                        // MaterialTheme.typography.bodySmall,
                        //                                    fontWeight = FontWeight.Bold
                        //                            )
                        //                        }
                    }
                }
            }

            // Add coordinate system debug info
            Box(
                    modifier =
                            Modifier.align(Alignment.TopStart)
                                    .padding(top = 32.dp, start = 16.dp)
                                    .background(
                                            Color.Black.copy(alpha = 0.7f),
                                            shape = MaterialTheme.shapes.small
                                    )
                                    .padding(8.dp)
            ) {
                Column {
                    //                    Text(
                    //                            text = "Coordinate Debug",
                    //                            color = Color.White,
                    //                            style = MaterialTheme.typography.bodySmall,
                    //                            fontWeight = FontWeight.Bold
                    //                    )
                    //                    Text(
                    //                            text = "Rotation: ${rotationDegrees}°",
                    //                            color = Color.White,
                    //                            style = MaterialTheme.typography.bodySmall
                    //                    )
                    //                    if (trackingMode == TrackingMode.QR_TRACKING ||
                    // isCalibrating) {
                    //                        Text(
                    //                            text = "Image:
                    // ${imageWidth.toInt()}x${imageHeight.toInt()}",
                    //                            color = Color.White,
                    //                            style = MaterialTheme.typography.bodySmall
                    //                        )
                    //                    }
                    // Only show QR Count for QR tracking mode or during calibration
                    if (trackingMode == TrackingMode.QR_TRACKING || isCalibrating) {
                        Text(
                                text = "QR Detected: ${qrDetections.size}",
                                color =
                                        if (qrDetections.isNotEmpty()) Color(0xFF4CAF50)
                                        else Color(0xFFFF5252),
                                style = MaterialTheme.typography.bodySmall
                        )
                    }
                    //                    Text(
                    //                            text = "Mode: ${trackingMode.name}",
                    //                            color = Color.White,
                    //                            style = MaterialTheme.typography.bodySmall
                    //                    )
                }
            }

            // Recording timer
            if (isRecording) {
                val remainingTime by viewModel.recordingTimeRemaining.collectAsState()
                Box(
                        modifier =
                                Modifier.align(Alignment.TopCenter)
                                        .padding(top = 32.dp)
                                        .background(
                                                color = Color.Black.copy(alpha = 0.7f),
                                                shape = RoundedCornerShape(12.dp)
                                        )
                                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                                text = "RECORDING TIME",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                        )
                        Text(
                                text = formatTime(remainingTime),
                                color =
                                        if (remainingTime < 10) Color(0xFFFF5252)
                                        else Color(0xFF4CAF50),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // FIXED: Special calibration mode indicator
            if (isCalibrating) {
                Box(
                        modifier =
                                Modifier.align(Alignment.BottomCenter)
                                        .padding(bottom = 100.dp, start = 16.dp, end = 16.dp)
                                        .background(
                                                Color(0xFF4CAF50).copy(alpha = 0.9f),
                                                shape = RoundedCornerShape(12.dp)
                                        )
                                        .padding(16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                                text = "🎯 CALIBRATION MODE",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                        )
                        Text(
                                text = "Using QR Tracking for Calibration",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                        )
                        if (trackingMode == TrackingMode.YOLO_TRACKING) {
                            Text(
                                    text = "(Temporarily overriding YOLO mode)",
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }

                        // Calibration progress
                        val remainingTime =
                                if (isCalibrating) {
                                    kotlin.math.max(
                                            0,
                                            CALIBRATION_DURATION_MS -
                                                    (System.currentTimeMillis() -
                                                            calibrationStartTime)
                                    ) / 1000
                                } else {
                                    0
                                }

                        Text(
                                text = "Time remaining: ${remainingTime}s",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    // Class-level functions start here
    private fun startRecording() {
        recordingStartTime = System.currentTimeMillis()

        // Reset breathing detection state for new recording session
        positionHistories.clear()
        Log.d("RobustBreathing", "Position histories cleared for new recording session")

        viewModel.startRecording()
    }

    private fun stopRecording() {
        viewModel.stopRecording()
        val patientMetadata = viewModel.patientMetadata.value
        val respiratoryData = viewModel.respiratoryData.value

        if (patientMetadata != null && respiratoryData.isNotEmpty()) {
            Toast.makeText(
                            this,
                            "Recording stopped. ${respiratoryData.size} data points collected.",
                            Toast.LENGTH_SHORT
                    )
                    .show()
        }
    }

    private fun saveData() {
        val patientMetadata = viewModel.patientMetadata.value
        val respiratoryData = viewModel.respiratoryData.value

        if (patientMetadata != null && respiratoryData.isNotEmpty()) {
            saveRespiratoryData(this, respiratoryData, patientMetadata)
        }
    }

    private fun forceBreathingUpdate() {
        viewModel.forceBreathingUpdate()
        Toast.makeText(this, "Breathing phase updated", Toast.LENGTH_SHORT).show()
    }

    private fun newPatient() {
        // Reset breathing detection state for new patient
        positionHistories.clear()
        Log.d("RobustBreathing", "Position histories cleared for new patient")

        viewModel.startNewPatient()
    }

    private fun toggleTrainingMode() {
        viewModel.toggleTrainingMode(!viewModel.isTrainingMode.value)
    }

    private fun startCalibration() {
        calibrationData.clear()
        isCalibrating = true
        calibrationStartTime = System.currentTimeMillis()

        // Clear any existing position histories for fresh calibration
        positionHistories.clear()
        smoothedCenters.clear()

        // Enhanced calibration start message
        val currentTrackingMode = viewModel.currentTrackingMode.value
        val calibrationMessage =
                if (currentTrackingMode == TrackingMode.YOLO_TRACKING) {
                    "Calibration started using QR tracking (regardless of current mode). Please place QR code on chest and breathe normally for 10 seconds."
                } else {
                    "Calibration started using QR tracking. Please breathe normally for 10 seconds."
                }

        Toast.makeText(this, calibrationMessage, Toast.LENGTH_LONG).show()

        Log.d("CalibrationMode", "🎯 CALIBRATION STARTED")
        Log.d("CalibrationMode", "Current tracking mode: $currentTrackingMode")
        Log.d("CalibrationMode", "Calibration will use: QR_TRACKING (forced)")
        Log.d("CalibrationMode", "Duration: ${CALIBRATION_DURATION_MS / 1000} seconds")

        viewModel.updateCalibrationState(true)

        Handler(Looper.getMainLooper())
                .postDelayed(
                        {
                            if (isCalibrating) {
                                completeCalibration()
                            }
                        },
                        CALIBRATION_DURATION_MS.toLong()
                )
    }

    private fun completeCalibration() {
        isCalibrating = false
        viewModel.updateCalibrationState(false)

        // Calculate calibration statistics
        val totalQrDetections = positionHistories.values.sumOf { it.positions.size }
        val uniqueQrCodes = positionHistories.keys.size

        val completionMessage =
                if (totalQrDetections > 0) {
                    "Calibration complete! Collected $totalQrDetections QR position samples from $uniqueQrCodes QR code(s). Ready for tracking."
                } else {
                    "Calibration complete, but no QR codes were detected. Please ensure QR code is visible during calibration."
                }

        Toast.makeText(this, completionMessage, Toast.LENGTH_LONG).show()

        Log.d("CalibrationMode", "🎯 CALIBRATION COMPLETED")
        Log.d("CalibrationMode", "QR detections collected: $totalQrDetections")
        Log.d("CalibrationMode", "Unique QR codes: $uniqueQrCodes")
        Log.d("CalibrationMode", "Position histories: ${positionHistories.keys}")

        // Log calibration quality assessment
        if (totalQrDetections >= 50) {
            Log.d("CalibrationMode", "✅ Excellent calibration quality (50+ samples)")
        } else if (totalQrDetections >= 20) {
            Log.d("CalibrationMode", "✅ Good calibration quality (20+ samples)")
        } else if (totalQrDetections >= 10) {
            Log.d("CalibrationMode", "⚠️ Fair calibration quality (10+ samples)")
        } else if (totalQrDetections > 0) {
            Log.d("CalibrationMode", "⚠️ Poor calibration quality (<10 samples)")
        } else {
            Log.d("CalibrationMode", "❌ No calibration data collected")
        }
    }

    private fun saveRespirationChartAsImage() {
        val respiratoryData = viewModel.respiratoryData.value
        if (respiratoryData.isEmpty()) {
            Toast.makeText(this, "No respiratory data to save as graph", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Create a larger bitmap to match the RespirationChart design
            val width = 1400
            val height = 1000
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Fill background with black (matching RespirationChart)
            canvas.drawColor(android.graphics.Color.argb(127, 0, 0, 0)) // Black with alpha 0.5f

            // Define the exact same colors as RespirationChart
            val inhaleColor = android.graphics.Color.argb(255, 129, 199, 132) // Color(0xFF81C784)
            val exhaleColor = android.graphics.Color.argb(255, 100, 181, 246) // Color(0xFF64B5F6)
            val pauseColor = android.graphics.Color.argb(255, 255, 213, 79) // Color(0xFFFFD54F)

            // Chart layout matching RespirationChart structure
            val leftAxisWidth = 80f // Y-axis area
            val chartPadding = 40f
            val legendHeight = 120f // Space for legend at bottom
            val titleHeight = 60f // Space for title at top

            val chartWidth = width - leftAxisWidth - chartPadding * 2
            val chartHeight = height - legendHeight - titleHeight - chartPadding * 2
            val chartTop = titleHeight + chartPadding
            val chartLeft = leftAxisWidth + chartPadding
            val chartBottom = chartTop + chartHeight
            val middleY = chartTop + chartHeight / 2

            // Set up paint objects
            val titlePaint =
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 36f
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }

            val axisPaint =
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        strokeWidth = 2f
                        style = android.graphics.Paint.Style.STROKE
                        isAntiAlias = true
                    }

            val gridPaint =
                    android.graphics.Paint().apply {
                        color =
                                android.graphics.Color.argb(
                                        51,
                                        255,
                                        255,
                                        255
                                ) // White with alpha 0.2f
                        strokeWidth = 1f
                        style = android.graphics.Paint.Style.STROKE
                        isAntiAlias = true
                    }

            val labelPaint =
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 24f
                        isAntiAlias = true
                    }

            val linePaint =
                    android.graphics.Paint().apply {
                        strokeWidth = 6f // 1.5dp equivalent at higher resolution
                        style = android.graphics.Paint.Style.STROKE
                        isAntiAlias = true
                        strokeCap = android.graphics.Paint.Cap.ROUND
                    }

            // Draw title
            canvas.drawText("Respiratory Chart", width / 2f, 40f, titlePaint)

            // Calculate velocity scaling (matching RespirationChart logic)
            val maxVelocity =
                    respiratoryData.maxOfOrNull { kotlin.math.abs(it.velocity) }?.coerceAtLeast(10f)
                            ?: 10f
            val yPerVelocity = (chartHeight / 2) / maxVelocity
            val velocityStep = 10f

            // Draw Y-axis label (vertical text)
            canvas.save()
            canvas.rotate(-90f, 30f, middleY)
            labelPaint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText("Velocity (px/s)", 30f, middleY + 10f, labelPaint)
            canvas.restore()

            // Draw horizontal center line (0 velocity)
            canvas.drawLine(chartLeft, middleY, chartLeft + chartWidth, middleY, axisPaint)

            // Draw velocity grid lines and labels
            labelPaint.textAlign = android.graphics.Paint.Align.RIGHT

            // Positive velocity lines
            var velocity = velocityStep
            while (velocity <= maxVelocity) {
                val y = middleY - (velocity * yPerVelocity)

                // Grid line
                canvas.drawLine(chartLeft, y, chartLeft + chartWidth, y, gridPaint)

                // Label
                canvas.drawText("+${velocity.toInt()}", leftAxisWidth - 10f, y + 8f, labelPaint)

                velocity += velocityStep
            }

            // Negative velocity lines
            velocity = -velocityStep
            while (velocity >= -maxVelocity) {
                val y = middleY - (velocity * yPerVelocity)

                // Grid line
                canvas.drawLine(chartLeft, y, chartLeft + chartWidth, y, gridPaint)

                // Label
                canvas.drawText("${velocity.toInt()}", leftAxisWidth - 10f, y + 8f, labelPaint)

                velocity -= velocityStep
            }

            // Draw center line label (0)
            canvas.drawText("0", leftAxisWidth - 10f, middleY + 8f, labelPaint)

            // Draw X-axis label
            labelPaint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText(
                    "Time (seconds)",
                    chartLeft + chartWidth / 2,
                    chartBottom + 35f,
                    labelPaint
            )

            // Calculate data point positions and draw the chart
            if (respiratoryData.size > 1) {
                val startTime = respiratoryData.first().timestamp
                val endTime = respiratoryData.last().timestamp
                val timeRange = (endTime - startTime).coerceAtLeast(1L)

                // Draw time markers (every 4 seconds like RespirationChart)
                val timeMarkerInterval = 4000L // 4 seconds
                var currentTime = startTime

                while (currentTime <= endTime) {
                    val x = chartLeft + (currentTime - startTime) / timeRange.toFloat() * chartWidth

                    // Vertical grid line
                    canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)

                    // Time label
                    val seconds = (currentTime - startTime) / 1000
                    canvas.drawText("${seconds}s", x, chartBottom + 25f, labelPaint)

                    currentTime += timeMarkerInterval
                }

                // Draw the respiratory data lines with exact same colors as RespirationChart
                for (i in 0 until respiratoryData.size - 1) {
                    val current = respiratoryData[i]
                    val next = respiratoryData[i + 1]

                    val currentX =
                            chartLeft +
                                    (current.timestamp - startTime) / timeRange.toFloat() *
                                            chartWidth
                    val currentY = middleY - (current.velocity * yPerVelocity)
                    val nextX =
                            chartLeft +
                                    (next.timestamp - startTime) / timeRange.toFloat() * chartWidth
                    val nextY = middleY - (next.velocity * yPerVelocity)

                    // Set line color based on breathing phase (exact same logic as
                    // RespirationChart)
                    val phase = current.breathingPhase.lowercase().trim()
                    linePaint.color =
                            when (phase) {
                                "inhaling" -> inhaleColor
                                "exhaling" -> exhaleColor
                                "pause" -> pauseColor
                                else -> android.graphics.Color.GRAY
                            }

                    // Draw line segment
                    canvas.drawLine(currentX, currentY, nextX, nextY, linePaint)
                }
            }

            // Draw legend (matching RespirationChart design exactly)
            val legendTop = chartBottom + 60f
            val legendPaint =
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 28f
                        isAntiAlias = true
                    }

            val legendLinePaint =
                    android.graphics.Paint().apply {
                        strokeWidth = 8f
                        style = android.graphics.Paint.Style.STROKE
                        isAntiAlias = true
                    }

            // Legend background (dark like RespirationChart)
            val legendBgPaint =
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(230, 0, 0, 0) // Black with alpha 0.9f
                        style = android.graphics.Paint.Style.FILL
                    }
            canvas.drawRect(0f, legendTop - 20f, width.toFloat(), height.toFloat(), legendBgPaint)

            // Legend title
            legendPaint.textAlign = android.graphics.Paint.Align.LEFT
            canvas.drawText("Breathing Phase Legend:", 30f, legendTop + 10f, legendPaint)

            // Legend items with exact same layout as RespirationChart
            val legendItemTop = legendTop + 50f
            val lineLength = 60f
            val itemSpacing = 200f

            // Inhaling legend
            legendLinePaint.color = inhaleColor
            canvas.drawLine(30f, legendItemTop, 30f + lineLength, legendItemTop, legendLinePaint)
            canvas.drawText("Inhaling", 30f + lineLength + 15f, legendItemTop + 10f, legendPaint)

            // Exhaling legend
            legendLinePaint.color = exhaleColor
            canvas.drawLine(
                    30f + itemSpacing,
                    legendItemTop,
                    30f + itemSpacing + lineLength,
                    legendItemTop,
                    legendLinePaint
            )
            canvas.drawText(
                    "Exhaling",
                    30f + itemSpacing + lineLength + 15f,
                    legendItemTop + 10f,
                    legendPaint
            )

            // Pause legend
            legendLinePaint.color = pauseColor
            canvas.drawLine(
                    30f + itemSpacing * 2,
                    legendItemTop,
                    30f + itemSpacing * 2 + lineLength,
                    legendItemTop,
                    legendLinePaint
            )
            canvas.drawText(
                    "Pause",
                    30f + itemSpacing * 2 + lineLength + 15f,
                    legendItemTop + 10f,
                    legendPaint
            )

            // Save the bitmap to gallery
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val formattedDate = dateFormat.format(Date())
            val fileName = "respiratory_chart_${formattedDate}.png"

            val imagesDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val imageFile = File(imagesDir, fileName)

            imageFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            // Notify media scanner to add to gallery
            val mediaScanIntent =
                    android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = android.net.Uri.fromFile(imageFile)
            sendBroadcast(mediaScanIntent)

            Toast.makeText(this, "Graph saved to gallery: $fileName", Toast.LENGTH_LONG).show()
            Log.d(
                    "MainActivity",
                    "RespirationChart-style graph saved to: ${imageFile.absolutePath}"
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving RespirationChart-style graph: ${e.message}", e)
            Toast.makeText(this, "Error saving graph: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "%d:%02d".format(minutes, remainingSeconds)
    }

    // Camera and detection functions
    private fun requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            val requestPermissionLauncher =
                    registerForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        if (permissions[Manifest.permission.CAMERA] == true) {
                            val currentState = viewModel.uiState.value
                            if (currentState is UiState.CameraSetup ||
                                            currentState is UiState.Calibrating ||
                                            currentState is UiState.Recording
                            ) {
                                initializeCamera()
                            }
                        } else {
                            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG)
                                    .show()
                        }
                    }
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun initializeCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermissionIfNeeded()
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
                {
                    try {
                        cameraProvider = cameraProviderFuture.get()
                        bindCameraUseCases()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize camera", e)
                        Toast.makeText(
                                        this,
                                        "Failed to initialize camera: ${e.message}",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                    }
                },
                ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        try {
            cameraProvider.unbindAll()

            preview =
                    Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

            imageAnalyzer =
                    ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(cameraExecutor) { imageProxy ->
                                    processImage(imageProxy) { detections, width, height, rotation
                                        ->
                                        processDetections(detections, width, height, rotation)
                                        qrCodeDetectionCallback?.invoke(
                                                detections,
                                                width,
                                                height,
                                                rotation
                                        )
                                    }
                                }
                            }

            val isFrontCamera = viewModel.isFrontCamera.value
            val cameraSelector =
                    if (isFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            Toast.makeText(this, "Camera initialization failed: ${exc.message}", Toast.LENGTH_LONG)
                    .show()
        }
    }

    private fun processImage(
            imageProxy: ImageProxy,
            callback: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit
    ) {
        try {
            val bitmap = convertImageProxyToBitmap(imageProxy) ?: return
            val rotation = imageProxy.imageInfo.rotationDegrees

            // FIXED: During calibration, ALWAYS use QR tracking logic regardless of current
            // tracking mode
            // This ensures calibration establishes proper QR movement baselines for the main
            // application focus
            val effectiveTrackingMode =
                    if (isCalibrating) {
                        TrackingMode.QR_TRACKING
                    } else {
                        viewModel.currentTrackingMode.value
                    }

            when (effectiveTrackingMode) {
                TrackingMode.QR_TRACKING -> {
                    val detections = detectQrCodesBoof(bitmap)
                    callback(detections, bitmap.width.toFloat(), bitmap.height.toFloat(), rotation)
                }
                TrackingMode.YOLO_TRACKING -> {
                    processYoloChestDetection(bitmap, rotation)
                    callback(emptyList(), bitmap.width.toFloat(), bitmap.height.toFloat(), rotation)
                }
            }

            // Enhanced logging for calibration mode
            if (isCalibrating && System.currentTimeMillis() % 2000 < 50) {
                Log.d("CalibrationMode", "🎯 CALIBRATION: Using QR tracking logic (forced)")
                Log.d(
                        "CalibrationMode",
                        "Current tracking mode: ${viewModel.currentTrackingMode.value}"
                )
                Log.d("CalibrationMode", "Effective tracking mode: $effectiveTrackingMode")
                Log.d(
                        "CalibrationMode",
                        "Calibration time remaining: ${(CALIBRATION_DURATION_MS - (System.currentTimeMillis() - calibrationStartTime)) / 1000}s"
                )
            }
        } catch (e: Exception) {
            Log.e("CameraProcess", "Error processing image", e)
        } finally {
            imageProxy.close()
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun convertImageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun detectQrCodesBoof(bitmap: Bitmap): List<BoofCvQrDetection> {
        return try {
            val gray = GrayU8(bitmap.width, bitmap.height)
            ConvertBitmap.bitmapToGray(bitmap, gray, null)
            val detector = FactoryFiducial.qrcode(null, GrayU8::class.java)
            detector.process(gray)

            detector.detections.map { detection ->
                val corners =
                        (0 until detection.bounds.size()).map { i ->
                            val pt = detection.bounds.get(i)
                            Offset(pt.x.toFloat(), pt.y.toFloat())
                        }
                val centerX = corners.map { it.x }.average().toFloat()
                val centerY = corners.map { it.y }.average().toFloat()

                BoofCvQrDetection(detection.message, Offset(centerX, centerY), corners)
            }
        } catch (e: Exception) {
            Log.e("BoofCV", "Detection failed", e)
            emptyList()
        }
    }

    private fun processYoloChestDetection(bitmap: Bitmap, rotation: Int) {
        try {
            val chestDetections = yoloChestDetector?.detectChest(bitmap) ?: emptyList()
            if (chestDetections.isNotEmpty()) {
                val bestDetection = chestDetections.maxByOrNull { it.confidence }
                bestDetection?.let { detection ->
                    viewModel.updateChestDetection(detection)

                    val currentTime = System.currentTimeMillis()
                    chestTracker?.let { tracker ->
                        val breathingData =
                                tracker.updateChestPositionDetailed(detection, currentTime)
                        breathingData?.let { data ->
                            val normalizedPhase =
                                    when (data.breathingPhase.lowercase().trim()) {
                                        "inhaling" -> "inhaling"
                                        "exhaling" -> "exhaling"
                                        "pause" -> "pause"
                                        else -> "pause"
                                    }

                            viewModel.updateBreathingData(
                                    phase =
                                            when (normalizedPhase) {
                                                "inhaling" -> -1
                                                "exhaling" -> 1
                                                else -> 0
                                            },
                                    confidence = 0.8f,
                                    velocity = data.velocity
                            )

                            viewModel.updateCurrentBreathingPhase(normalizedPhase)

                            if (viewModel.isRecording.value) {
                                viewModel.addRespiratoryDataPoint(data)
                            }
                        }
                    }
                }
            } else {
                viewModel.updateChestDetection(null)
            }
        } catch (e: Exception) {
            Log.e("YoloProcessing", "Error processing YOLO detection: ${e.message}", e)
        }
    }

    private fun processDetections(
            detections: List<BoofCvQrDetection>,
            width: Float,
            height: Float,
            rotation: Int
    ) {
        if (isCalibrating || viewModel.isRecording.value) {
            val currentTime = System.currentTimeMillis()

            if (isCalibrating) {
                processCalibrationData(detections, width, height, rotation, currentTime)
            } else if (viewModel.isRecording.value) {
                processRecordingData(detections, width, height, rotation, currentTime)
            }
        }
    }

    private fun processCalibrationData(
            detections: List<BoofCvQrDetection>,
            width: Float,
            height: Float,
            rotation: Int,
            currentTime: Long
    ) {
        if (currentTime - calibrationStartTime >= CALIBRATION_DURATION_MS) {
            isCalibrating = false
            completeCalibration()
            return
        }

        if (detections.isNotEmpty()) {
            val detection = detections.first()
            processDetectionForData(detection, width, height, rotation, currentTime, false)
        }
    }

    private fun processRecordingData(
            detections: List<BoofCvQrDetection>,
            width: Float,
            height: Float,
            rotation: Int,
            currentTime: Long
    ) {
        if (detections.isNotEmpty()) {
            val detection = detections.first()
            processDetectionForData(detection, width, height, rotation, currentTime, true)
        }
    }

    private fun processDetectionForData(
            detection: BoofCvQrDetection,
            width: Float,
            height: Float,
            rotation: Int,
            currentTime: Long,
            isRecording: Boolean
    ) {
        val key = detection.rawValue ?: "unknown"

        // Use rotation-aware breathing analysis
        val breathingAnalysis =
                analyzeBreathingMovementWithRotation(
                        key,
                        detection.center,
                        rotation,
                        width,
                        height,
                        currentTime
                )

        val velocity = breathingAnalysis.velocity
        val breathingPhase = breathingAnalysis.phase
        val confidence = breathingAnalysis.confidence
        val amplitude = breathingAnalysis.amplitude

        // Enhanced logging for breathing analysis
        if (currentTime % 1000 < 50) {
            Log.d("RobustBreathing", "=== BREATHING ANALYSIS (ROTATION-AWARE) ===")
            Log.d("RobustBreathing", "Rotation: ${rotation}°")
            Log.d(
                    "RobustBreathing",
                    "Phase: $breathingPhase, Y-Velocity: ${String.format("%.2f", velocity)}"
            )
            Log.d(
                    "RobustBreathing",
                    "Direction: ${breathingAnalysis.direction}, Confidence: ${String.format("%.2f", confidence)}"
            )
            Log.d("RobustBreathing", "Trend: ${breathingAnalysis.movementTrend}")
            Log.d("RobustBreathing", "Amplitude: ${String.format("%.2f", amplitude)}")
        }

        viewModel.updateBreathingData(
                phase =
                        when (breathingPhase) {
                            "inhaling" -> -1
                            "exhaling" -> 1
                            else -> 0
                        },
                confidence = confidence,
                velocity = velocity
        )

        if (isRecording) {
            val movementType =
                    when (breathingPhase) {
                        "inhaling" -> "upward"
                        "exhaling" -> "downward"
                        else -> "stable"
                    }

            // Get the stabilized point for recording (after rotation transformation)
            val stabilizedPoint = smoothedCenters[key]?.center ?: detection.center

            val dataPoint =
                    RespiratoryDataPoint(
                            timestamp = currentTime - recordingStartTime,
                            position = stabilizedPoint,
                            qrId = key,
                            movement = movementType,
                            breathingPhase = breathingPhase,
                            amplitude = amplitude,
                            velocity = velocity
                    )

            viewModel.addRespiratoryDataPoint(dataPoint)

            if (currentTime % 2000 < 50) {
                Log.d(
                        "ImprovedDataPoint",
                        "Added: Phase=$breathingPhase, Y-Velocity=${String.format("%.2f", velocity)}, Confidence=${String.format("%.2f", confidence)}"
                )
            }
            viewModel.addRespiratoryDataPoint(dataPoint)
        }

        // Debug logging for velocity analysis
        if (currentTime % 2000 < 50) { // Log every 2 seconds
            Log.d("RealisticBreathing", "=== BREATHING VELOCITY DEBUG ===")
            Log.d(
                    "RealisticBreathing",
                    "Position change: ${String.format("%.2f", detection.center.y - (smoothedCenters[key]?.center?.y ?: 0f))} pixels"
            )
            Log.d(
                    "RealisticBreathing",
                    "Time delta: ${String.format("%.3f", (currentTime - (lastPhaseChangeTimestamp[key] ?: 0L)) / 1000f)} seconds"
            )
            Log.d(
                    "RealisticBreathing",
                    "Raw velocity: ${String.format("%.2f", velocity)} pixels/sec"
            )
            Log.d(
                    "RealisticBreathing",
                    "Scaled velocity: ${String.format("%.2f", velocity)} (used for phase)"
            )
            Log.d("RealisticBreathing", "Detected phase: $breathingPhase")
            Log.d("RealisticBreathing", "Amplitude: ${String.format("%.2f", amplitude)} pixels")
        }
    }

    /** Transform QR coordinates based on camera rotation to ensure consistent vertical tracking */
    private fun transformQrCoordinatesForBreathing(
            center: Offset,
            rotationDegrees: Int,
            imageWidth: Float,
            imageHeight: Float
    ): Offset {
        // Transform coordinates to always track the "vertical" axis relative to the person
        // regardless of camera rotation
        return when (rotationDegrees) {
            90 -> {
                // Camera rotated 90 degrees: X becomes Y, Y becomes inverted X
                Offset(x = center.y, y = imageWidth - center.x)
            }
            180 -> {
                // Camera rotated 180 degrees: Both X and Y are inverted
                Offset(x = imageWidth - center.x, y = imageHeight - center.y)
            }
            270 -> {
                // Camera rotated 270 degrees: X becomes inverted Y, Y becomes X
                Offset(x = imageHeight - center.y, y = center.x)
            }
            else -> {
                // No rotation or 0 degrees: Use coordinates as-is
                center
            }
        }
    }

    /** Enhanced breathing movement analysis that accounts for camera rotation */
    private fun analyzeBreathingMovementWithRotation(
            qrId: String,
            rawCenter: Offset,
            rotationDegrees: Int,
            imageWidth: Float,
            imageHeight: Float,
            currentTime: Long
    ): BreathingAnalysis {
        // Transform coordinates to handle camera rotation
        val transformedCenter =
                transformQrCoordinatesForBreathing(
                        rawCenter,
                        rotationDegrees,
                        imageWidth,
                        imageHeight
                )

        // Create a TrackedPoint with transformed coordinates
        val trackedPoint = smoothedCenters[qrId]
        val stabilizedPoint = stabilizeQrPosition(transformedCenter, trackedPoint, currentTime)
        smoothedCenters[qrId] = stabilizedPoint

        // Enhanced coordinate transformation debugging
        if (trackedPoint != null && currentTime % 500 < 50) {
            val deltaX = stabilizedPoint.center.x - trackedPoint.center.x
            val deltaY = stabilizedPoint.center.y - trackedPoint.center.y
            val timeDelta = (currentTime - trackedPoint.lastUpdateTime) / 1000f

            if (timeDelta > 0) {
                val velocityX = deltaX / timeDelta
                val velocityY = deltaY / timeDelta

                Log.d("RotationDebug", "=== COORDINATE TRANSFORMATION & MOVEMENT ===")
                Log.d("RotationDebug", "Rotation: ${rotationDegrees}°")
                Log.d("RotationDebug", "Raw center: (${rawCenter.x}, ${rawCenter.y})")
                Log.d(
                        "RotationDebug",
                        "Transformed center: (${transformedCenter.x}, ${transformedCenter.y})"
                )
                Log.d(
                        "RotationDebug",
                        "Stabilized center: (${stabilizedPoint.center.x}, ${stabilizedPoint.center.y})"
                )
                Log.d("RotationDebug", "Image size: ${imageWidth}x${imageHeight}")
                Log.d("RotationDebug", "Delta X: $deltaX, Delta Y: $deltaY")
                Log.d("RotationDebug", "Velocity X: $velocityX, Velocity Y: $velocityY")
                Log.d(
                        "RotationDebug",
                        "Dominant Movement: ${if (kotlin.math.abs(velocityX) > kotlin.math.abs(velocityY)) "HORIZONTAL" else "VERTICAL"}"
                )
                Log.d(
                        "RotationDebug",
                        "Y Movement Direction: ${if (velocityY > 0) "DOWN (inhaling)" else "UP (exhaling)"}"
                )
                Log.d(
                        "RotationDebug",
                        "Expected Breathing: ${if (velocityY > 4) "INHALING" else if (velocityY < -4) "EXHALING" else "PAUSE"}"
                )
            }
        }

        // Use the existing breathing analysis with transformed coordinates
        val analysis = analyzeBreathingMovement(qrId, stabilizedPoint, currentTime)

        // Add rotation information to the analysis for debugging
        if (currentTime % 1000 < 50) {
            Log.d("BreathingRotation", "=== FINAL BREATHING ANALYSIS ===")
            Log.d("BreathingRotation", "Rotation applied: ${rotationDegrees}°")
            Log.d("BreathingRotation", "Detected phase: ${analysis.phase}")
            Log.d("BreathingRotation", "Y-velocity: ${String.format("%.2f", analysis.velocity)}")
            Log.d("BreathingRotation", "Direction: ${analysis.direction}")
            Log.d("BreathingRotation", "Confidence: ${String.format("%.2f", analysis.confidence)}")
        }

        return analysis
    }

    // Data class to hold breathing analysis results
    private data class BreathingAnalysis(
            val velocity: Float,
            val phase: String,
            val confidence: Float,
            val amplitude: Float,
            val direction: String,
            val movementTrend: String
    )

    // Enhanced position history for robust analysis with phase stability
    private data class PositionHistory(
            val positions: MutableList<Pair<Long, Offset>> = mutableListOf(),
            val velocities: MutableList<Float> =
                    mutableListOf(), // For your proven exponential smoothing
            val phases: MutableList<String> = mutableListOf(),
            var baselineY: Float = 0f,
            var lastPhaseChange: Long = 0L,
            var currentPhase: String = "pause" // Start with pause phase
    )

    // Store position history for each QR code
    private val positionHistories = mutableMapOf<String, PositionHistory>()

    /** STABLE: Improved breathing detection with temporal smoothing and hysteresis */
    private fun analyzeBreathingMovement(
            qrId: String,
            stabilizedPoint: TrackedPoint,
            currentTime: Long
    ): BreathingAnalysis {

        // Get or create position history
        val history = positionHistories.getOrPut(qrId) { PositionHistory() }

        // Add current position
        history.positions.add(Pair(currentTime, stabilizedPoint.center))

        // Keep longer history for better stability (3 seconds)
        val cutoffTime = currentTime - 3000
        history.positions.removeAll { it.first < cutoffTime }

        // Need sufficient data for stable analysis
        if (history.positions.size < 5) {
            return BreathingAnalysis(
                    velocity = 0f,
                    phase = "pause",
                    confidence = 0.5f,
                    amplitude = 0f,
                    direction = "stable",
                    movementTrend = "initializing"
            )
        }

        // Calculate velocity using YOUR PROVEN approach from old working code
        val recentPositions =
                history.positions.takeLast(5) // Back to your original 5-point approach
        val velocities = mutableListOf<Float>()

        for (i in 1 until recentPositions.size) {
            val (time1, pos1) = recentPositions[i - 1]
            val (time2, pos2) = recentPositions[i]
            val timeDelta = (time2 - time1) / 1000f

            if (timeDelta > 0.05f) { // Back to your original time delta
                val rawVelocity = (pos2.y - pos1.y) / timeDelta
                velocities.add(rawVelocity)
            }
        }

        // Use YOUR PROVEN weighted average approach from old code
        val HISTORY_SIZE = 5
        val totalWeight = (1 + HISTORY_SIZE) * HISTORY_SIZE / 2.0f
        var weightedSum = 0f

        // Apply your original exponential smoothing approach
        val avgVelocity =
                if (velocities.isNotEmpty()) {
                    velocities.average().toFloat()
                } else {
                    0f
                }

        // Get previous velocity for your proven exponential smoothing
        val prevVelocity =
                if (history.velocities.isNotEmpty()) {
                    history.velocities.last()
                } else {
                    0f
                }

        // Apply YOUR PROVEN exponential smoothing (alpha = 0.7f from your old code)
        val alpha = 0.7f
        val smoothedVelocity = prevVelocity * alpha + avgVelocity * (1 - alpha)

        // Store velocity in history for next iteration
        history.velocities.add(smoothedVelocity)
        if (history.velocities.size > 10) {
            history.velocities.removeAt(0) // Keep last 10 velocities
        }

        // Apply minimal scaling to preserve your working ranges
        val scaledVelocity = smoothedVelocity // No artificial scaling that causes issues

        // INFLECTION POINT detection for smoother turning points
        val hasDirectionChange =
                if (velocities.size >= 2) {
                    val recent = velocities.takeLast(2)
                    // Check if velocity is crossing zero (direction change)
                    (recent[0] > 0 && recent[1] < 0) || (recent[0] < 0 && recent[1] > 0)
                } else {
                    false
                }

        // Use more NATURAL scaling that preserves curve shape
        val naturalVelocity = scaledVelocity * 0.8f // Gentler scaling to preserve natural curves

        // Apply SOFT constraints instead of hard clamping for smoother curves
        //        val scaledVelocity =
        //                when {
        //                    naturalVelocity > 100f ->
        //                            90f + (naturalVelocity - 100f) * 0.1f // Soft limit above 100
        //                    naturalVelocity < -100f ->
        //                            -90f + (naturalVelocity + 100f) * 0.1f // Soft limit below
        // -100
        //                    else -> naturalVelocity // Preserve natural velocity within normal
        // range
        //                }

        // Enhanced logging for turning point detection
        if (hasDirectionChange && currentTime % 500 < 50) {
            Log.d(
                    "TurningPoint",
                    "🔄 DIRECTION CHANGE DETECTED: Velocity transitioning through zero"
            )
            Log.d("TurningPoint", "Recent velocities: ${velocities.takeLast(2)}")
            Log.d("TurningPoint", "Scaled velocity: ${String.format("%.2f", scaledVelocity)}")
        }

        // HYSTERESIS: Different thresholds for entering vs staying in a phase
        val currentPhase = history.currentPhase
        val phaseStartTime = history.lastPhaseChange

        // Minimum phase duration (allow natural breathing transitions)
        val MIN_PHASE_DURATION = 400L // Reduced to 400ms for natural breathing flow
        val timeSincePhaseChange = currentTime - phaseStartTime
        val canChangePhase = timeSincePhaseChange > MIN_PHASE_DURATION

        // BREATHING PHASE DETECTION with DIRECT TRANSITIONS (no forced pause)
        val newPhase =
                when (currentPhase) {
                    "inhaling" -> {
                        when {
                            // Stay inhaling if still moving up
                            scaledVelocity > 2f -> "inhaling"
                            // DIRECT transition to exhaling when moving down (no pause!)
                            scaledVelocity <= -4f && canChangePhase -> "exhaling"
                            // Only go to pause if velocity is truly minimal AND we've been in this
                            // state
                            scaledVelocity >= -2f &&
                                    scaledVelocity <= 2f &&
                                    timeSincePhaseChange > 1000L &&
                                    canChangePhase -> "pause"
                            else -> "inhaling" // Stay in current phase during natural transition
                        }
                    }
                    "exhaling" -> {
                        when {
                            // Stay exhaling if still moving down
                            scaledVelocity < -2f -> "exhaling"
                            // DIRECT transition to inhaling when moving up (no pause!)
                            scaledVelocity >= 4f && canChangePhase -> "inhaling"
                            // Only go to pause if velocity is truly minimal AND we've been in this
                            // state
                            scaledVelocity >= -2f &&
                                    scaledVelocity <= 2f &&
                                    timeSincePhaseChange > 1000L &&
                                    canChangePhase -> "pause"
                            else -> "exhaling" // Stay in current phase during natural transition
                        }
                    }
                    "pause" -> {
                        when {
                            // Exit pause when there's clear movement
                            scaledVelocity >= 4f && canChangePhase -> "inhaling"
                            scaledVelocity <= -4f && canChangePhase -> "exhaling"
                            else -> "pause" // Stay in pause
                        }
                    }
                    else -> {
                        // Initial phase determination (no hysteresis)
                        when {
                            scaledVelocity >= 4f -> "inhaling"
                            scaledVelocity <= -4f -> "exhaling"
                            else -> "pause" // Only start with pause if truly no movement
                        }
                    }
                }

        // Update phase history if changed
        if (newPhase != currentPhase) {
            history.currentPhase = newPhase
            history.lastPhaseChange = currentTime

            // Enhanced logging for transition types
            if ((currentPhase == "inhaling" && newPhase == "exhaling") ||
                            (currentPhase == "exhaling" && newPhase == "inhaling")
            ) {
                Log.d(
                        "DirectTransition",
                        "🔄 DIRECT TRANSITION: $currentPhase -> $newPhase (velocity: ${String.format("%.1f", scaledVelocity)}) - Natural breathing flow!"
                )
            } else {
                Log.d(
                        "PhaseChange",
                        "PHASE CHANGE: $currentPhase -> $newPhase (velocity: ${String.format("%.1f", scaledVelocity)})"
                )
            }
        }

        // Calculate confidence based on velocity strength and phase stability
        val velocityStrength = kotlin.math.abs(scaledVelocity) / 20f
        val phaseStability = kotlin.math.min(timeSincePhaseChange / 1000f, 2f) / 2f // Max 2 seconds
        val confidence =
                (0.6f + velocityStrength * 0.2f + phaseStability * 0.2f).coerceIn(0.6f, 0.95f)

        // Improved amplitude calculation
        val amplitude =
                if (history.positions.size > 3) {
                    val recentPositions = history.positions.takeLast(10)
                    val yPositions = recentPositions.map { it.second.y }
                    val range = (yPositions.maxOrNull() ?: 0f) - (yPositions.minOrNull() ?: 0f)
                    (range * 0.6f).coerceAtMost(50f)
                } else {
                    0f
                }

        // Debug logging every 2 seconds with PAUSE FOCUS
        if (currentTime % 2000 < 100) {
            Log.d("RestoredBreathing", "=== RESTORED TO YOUR PROVEN APPROACH ===")
            Log.d(
                    "RestoredBreathing",
                    "Raw velocities: ${velocities.takeLast(3).map { String.format("%.1f", it) }}"
            )
            Log.d("RestoredBreathing", "Average velocity: ${String.format("%.2f", avgVelocity)}")
            Log.d("RestoredBreathing", "Previous velocity: ${String.format("%.2f", prevVelocity)}")
            Log.d(
                    "RestoredBreathing",
                    "Smoothed velocity (α=0.7): ${String.format("%.2f", smoothedVelocity)}"
            )
            Log.d("RestoredBreathing", "Final velocity: ${String.format("%.2f", scaledVelocity)}")
            Log.d("RestoredBreathing", "Current phase: $newPhase (${timeSincePhaseChange}ms)")
            Log.d("RestoredBreathing", "Can change phase: $canChangePhase")

            // PAUSE DEBUGGING - show when pause is truly warranted
            if (newPhase == "pause") {
                Log.d(
                        "PauseDetection",
                        "🟡 PAUSE DETECTED - Velocity: ${String.format("%.2f", scaledVelocity)}"
                )
                Log.d(
                        "PauseDetection",
                        "This pause was triggered after ${timeSincePhaseChange}ms of minimal movement"
                )
                Log.d("PauseDetection", "Exit thresholds: inhaling >= 4.0, exhaling <= -4.0")
            } else if (scaledVelocity >= -2f && scaledVelocity <= 2f && timeSincePhaseChange < 1000L
            ) {
                Log.d(
                        "NaturalFlow",
                        "⚡ AVOIDING PAUSE during natural transition (vel: ${String.format("%.2f", scaledVelocity)}, time: ${timeSincePhaseChange}ms)"
                )
                Log.d("NaturalFlow", "Staying in $newPhase to allow natural breathing flow")
            }
        }

        return BreathingAnalysis(
                velocity = scaledVelocity,
                phase = newPhase,
                confidence = confidence,
                amplitude = amplitude,
                direction =
                        when (newPhase) {
                            "inhaling" -> "upward"
                            "exhaling" -> "downward"
                            else -> "stable"
                        },
                movementTrend = newPhase
        )
    }

    private fun stabilizeQrPosition(
            currentCenter: Offset,
            trackedPoint: TrackedPoint?,
            currentTime: Long
    ): TrackedPoint {
        val lockingThreshold = 15f // Back to your original value
        val stabilityThreshold = 0.3f // Back to your original value

        if (trackedPoint == null) {
            return TrackedPoint(
                    center = currentCenter,
                    lastUpdateTime = currentTime,
                    velocity = Offset.Zero,
                    isLocked = false,
                    initialPosition = currentCenter
            )
        }

        val initialPos = trackedPoint.initialPosition ?: currentCenter
        val distanceFromInitial =
                sqrt(
                        (currentCenter.x - initialPos.x).pow(2) +
                                (currentCenter.y - initialPos.y).pow(2)
                )

        // Use YOUR PROVEN stabilization approach from old working code
        val alpha = 0.6f // Your original proven value

        val stabilizedCenter =
                if (distanceFromInitial < lockingThreshold && trackedPoint.isLocked) {
                    Offset(
                            x = trackedPoint.center.x * alpha + currentCenter.x * (1 - alpha),
                            y = trackedPoint.center.y * alpha + currentCenter.y * (1 - alpha)
                    )
                } else {
                    currentCenter // Your original approach - no smoothing when not locked
                }

        val timeDelta = (currentTime - trackedPoint.lastUpdateTime) / 1000f
        val velocity =
                if (timeDelta > 0) {
                    Offset(
                            x = (stabilizedCenter.x - trackedPoint.center.x) / timeDelta,
                            y = (stabilizedCenter.y - trackedPoint.center.y) / timeDelta
                    )
                } else {
                    Offset.Zero
                }

        // Use YOUR PROVEN locking mechanism
        val shouldLock =
                velocity.getDistance() < stabilityThreshold ||
                        (trackedPoint.isLocked && distanceFromInitial < lockingThreshold * 1.5f)

        return TrackedPoint(
                center = stabilizedCenter,
                lastUpdateTime = currentTime,
                velocity = velocity,
                isLocked = shouldLock,
                initialPosition = trackedPoint.initialPosition ?: currentCenter
        )
    }

    private fun saveRespiratoryData(
            context: Context,
            data: List<RespiratoryDataPoint>,
            metadata: PatientMetadata
    ) {
        if (data.isEmpty()) {
            Toast.makeText(context, "No respiratory data to save", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Format timestamp to be more readable in filename
            val startTime = data.first().timestamp
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val formattedDate = dateFormat.format(Date(startTime))

            // Calculate and format duration in seconds
            val endTime = data.last().timestamp
            val durationInSeconds = (endTime - startTime) / 1000f
            val formattedDuration = String.format(Locale.US, "%.3f", durationInSeconds)

            val fileName = "respiratory_data_${metadata.id}_$formattedDate.csv"
            val csvFile =
                    File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS
                            ),
                            fileName
                    )

            // Calculate breathing metrics for the summary
            val metrics = calculateBreathingMetrics(data)

            csvFile.bufferedWriter().use { writer ->
                // Write patient information
                writer.write("# Patient Information\n")
                writer.write("ID,${metadata.id}\n")
                writer.write("Age,${metadata.age}\n")
                writer.write("Gender,${metadata.gender}\n")
                writer.write("Health Status,${metadata.healthStatus}\n")
                writer.write("Notes,${metadata.additionalNotes.replace(",", ";")}\n")
                writer.write("\n")

                // Write breathing analysis summary
                writer.write("# Breathing Analysis Summary\n")
                writer.write("Total Duration (seconds),${formattedDuration}\n")
                writer.write("Breathing Rate (breaths/minute),${metrics.breathingRate}\n")
                writer.write("Average Amplitude,${metrics.averageAmplitude}\n")
                writer.write("Maximum Amplitude,${metrics.maxAmplitude}\n")
                writer.write("Minimum Amplitude,${metrics.minAmplitude}\n")
                writer.write("Total Breaths,${metrics.breathCount}\n")
                writer.write("\n")

                // Write column headers
                writer.write(
                        "Relative Time (ms),QR ID,X,Y,Movement Direction,Breathing Phase,Amplitude,Velocity,Patient ID,Age,Gender,Health Status\n"
                )

                // Write data rows with relative timestamps for better readability
                data.forEach { point ->
                    val relativeTime = point.timestamp - startTime
                    writer.write(
                            "${relativeTime},${point.qrId},${point.position.x},${point.position.y}," +
                                    "${point.movement},${point.breathingPhase},${point.amplitude},${point.velocity}," +
                                    "${metadata.id},${metadata.age},${metadata.gender},${metadata.healthStatus}\n"
                    )
                }
            }

            Log.d(
                    "RespiratoryTracking",
                    "Successfully saved ${data.size} data points to ${csvFile.absolutePath}"
            )
            runOnUiThread {
                Toast.makeText(context, "Saved data to ${csvFile.name}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("RespiratoryTracking", "Error saving data: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(context, "Error saving data: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
            }
        }
    }

    /** Calculate breathing metrics from respiratory data points */
    private fun calculateBreathingMetrics(data: List<RespiratoryDataPoint>): BreathingMetrics {
        if (data.isEmpty()) {
            return BreathingMetrics(0f, 0f, 0f, 0f, 0)
        }

        // Extract amplitudes for analysis
        val amplitudes = data.map { it.amplitude }
        val timestamps = data.map { it.timestamp }

        // Calculate basic statistics
        val averageAmplitude = amplitudes.average().toFloat()
        val maxAmplitude = amplitudes.maxOrNull() ?: 0f
        val minAmplitude = amplitudes.minOrNull() ?: 0f

        // Use the SAME breathing rate calculation method as MainViewModel
        val breathingRate = calculateBreathingRateFromData(data)

        // Count breath cycles using the same method as breathing rate calculation
        val breathCount = countBreathCyclesFromData(data)

        Log.d(
                "RespiratoryTracking",
                "Breathing Analysis (CSV): Rate=$breathingRate, Count=$breathCount, Data points=${data.size}"
        )

        return BreathingMetrics(
                breathingRate = breathingRate,
                averageAmplitude = averageAmplitude,
                maxAmplitude = maxAmplitude,
                minAmplitude = minAmplitude,
                breathCount = breathCount
        )
    }

    /** Calculate breathing rate using the same method as MainViewModel */
    private fun calculateBreathingRateFromData(data: List<RespiratoryDataPoint>): Float {
        // Even with minimal data, provide a reasonable estimate
        if (data.size < 10) {
            Log.d("CSV_BreathingRate", "Limited data (${data.size} points), using default estimate")
            return 16f // Middle of normal range (12-20)
        }

        Log.d("CSV_BreathingRate", "========= CSV BREATHING RATE CALCULATION =========")
        Log.d("CSV_BreathingRate", "Total data points: ${data.size}")

        // Group data points into time windows to smooth out rapid fluctuations
        val timeWindowSize = 300 // 300ms windows
        val dataPoints = data.sortedBy { it.timestamp }
        val timeWindows = mutableListOf<Pair<Long, String>>()

        var currentWindowStart = dataPoints.firstOrNull()?.timestamp ?: 0L
        var currentWindowEnd = currentWindowStart + timeWindowSize
        var currentWindowPhases = mutableListOf<String>()

        // Group data into time windows and determine dominant phase per window
        for (point in dataPoints) {
            if (point.timestamp <= currentWindowEnd) {
                currentWindowPhases.add(point.breathingPhase.lowercase())
            } else {
                // Determine dominant phase for this window
                val dominantPhase =
                        currentWindowPhases.groupBy { it }.maxByOrNull { it.value.size }?.key
                                ?: "pause"

                timeWindows.add(Pair(currentWindowStart, dominantPhase))

                // Start new window
                currentWindowStart = point.timestamp
                currentWindowEnd = currentWindowStart + timeWindowSize
                currentWindowPhases = mutableListOf(point.breathingPhase.lowercase())
            }
        }

        // Add final window if not empty
        if (currentWindowPhases.isNotEmpty()) {
            val dominantPhase =
                    currentWindowPhases.groupBy { it }.maxByOrNull { it.value.size }?.key ?: "pause"
            timeWindows.add(Pair(currentWindowStart, dominantPhase))
        }

        // Check if there's any actual breathing (inhaling/exhaling phases)
        val phaseDistribution = timeWindows.groupBy { it.second }.mapValues { it.value.size }
        val hasInhalingPhases = phaseDistribution["inhaling"] ?: 0 > 0
        val hasExhalingPhases = phaseDistribution["exhaling"] ?: 0 > 0

        // Count how many pause phases we have as a percentage
        val totalWindows = timeWindows.size
        val pausePhases = phaseDistribution["pause"] ?: 0
        val pausePercentage = if (totalWindows > 0) pausePhases.toFloat() / totalWindows else 0f

        Log.d("CSV_BreathingRate", "Phase distribution: $phaseDistribution")
        Log.d(
                "CSV_BreathingRate",
                "Pause percentage: $pausePercentage (${pausePhases}/${totalWindows})"
        )

        // If majority of phases are pause (>80%) and minimal inhaling/exhaling, report actual low
        // rate
        if (pausePercentage > 0.8f && (!hasInhalingPhases || !hasExhalingPhases)) {
            Log.d("CSV_BreathingRate", "DETECTED NO BREATHING: ${pausePercentage*100}% pauses")
            return 0f // Report 0 breaths per minute for no breathing
        }

        // Count cycles using the time windows (smoothed phases)
        var cycleCount = 0
        var prevPhase = ""
        var inhaleSeen = false
        var exhaleSeen = false

        for ((timestamp, phase) in timeWindows) {
            // If we see inhale followed by exhale, count it as a cycle
            if (phase == "inhaling") {
                inhaleSeen = true
            } else if (phase == "exhaling" && inhaleSeen) {
                exhaleSeen = true
            }

            // When we see inhale again after having seen both inhale and exhale, count a cycle
            if (phase == "inhaling" && prevPhase != "inhaling" && inhaleSeen && exhaleSeen) {
                cycleCount++
                Log.d("CSV_BreathingRate", "FULL CYCLE DETECTED #$cycleCount at $timestamp")
                inhaleSeen = true // Reset exhale but keep inhale true
                exhaleSeen = false
            }

            prevPhase = phase
        }

        // Calculate duration in minutes (from first to last data point)
        val startTime = dataPoints.first().timestamp
        val endTime = dataPoints.last().timestamp
        val durationMs = endTime - startTime
        val durationMinutes = durationMs / 60000f // Convert ms to minutes

        Log.d("CSV_BreathingRate", "Start time: $startTime, End time: $endTime")
        Log.d("CSV_BreathingRate", "Duration: ${durationMs}ms (${durationMinutes} minutes)")
        Log.d("CSV_BreathingRate", "Cycles detected: $cycleCount")

        // If we didn't detect any cycles but have reasonable duration, estimate based on phases
        if (cycleCount == 0 && durationMinutes > 0) {
            // Count phase changes as a rough estimate
            var phaseChanges = 0
            for (i in 1 until timeWindows.size) {
                if (timeWindows[i].second != timeWindows[i - 1].second) {
                    phaseChanges++
                }
            }

            // Assume approximately 2 phase changes per cycle
            cycleCount = kotlin.math.max(1, phaseChanges / 2)
            Log.d(
                    "CSV_BreathingRate",
                    "No cycles detected, estimated $cycleCount cycles from $phaseChanges phase changes"
            )
        }

        // Ensure we have at least 1 cycle for calculation purposes
        cycleCount = kotlin.math.max(1, cycleCount)

        // Apply physiological constraints - normal human breathing is 8-30 breaths per minute
        val breathingRate = if (durationMinutes > 0) cycleCount / durationMinutes else 16f

        // If the rate seems implausible, use a default value
        val finalRate =
                if (breathingRate < 5f || breathingRate > 40f) {
                    Log.d(
                            "CSV_BreathingRate",
                            "Calculated rate ($breathingRate) outside physiological range, using default"
                    )
                    16f // Default to middle of normal range
                } else {
                    breathingRate
                }

        Log.d("CSV_BreathingRate", "Raw breathing rate: $breathingRate breaths/min")
        Log.d("CSV_BreathingRate", "FINAL CSV RATE: $finalRate breaths/min")

        return finalRate
    }

    /** Count breath cycles using the same method as breathing rate calculation */
    private fun countBreathCyclesFromData(data: List<RespiratoryDataPoint>): Int {
        if (data.size < 10) {
            return 0
        }

        // Use the same time window approach as breathing rate calculation
        val timeWindowSize = 300 // 300ms windows
        val dataPoints = data.sortedBy { it.timestamp }
        val timeWindows = mutableListOf<Pair<Long, String>>()

        var currentWindowStart = dataPoints.firstOrNull()?.timestamp ?: 0L
        var currentWindowEnd = currentWindowStart + timeWindowSize
        var currentWindowPhases = mutableListOf<String>()

        // Group data into time windows and determine dominant phase per window
        for (point in dataPoints) {
            if (point.timestamp <= currentWindowEnd) {
                currentWindowPhases.add(point.breathingPhase.lowercase())
            } else {
                val dominantPhase =
                        currentWindowPhases.groupBy { it }.maxByOrNull { it.value.size }?.key
                                ?: "pause"
                timeWindows.add(Pair(currentWindowStart, dominantPhase))

                currentWindowStart = point.timestamp
                currentWindowEnd = currentWindowStart + timeWindowSize
                currentWindowPhases = mutableListOf(point.breathingPhase.lowercase())
            }
        }

        // Add final window if not empty
        if (currentWindowPhases.isNotEmpty()) {
            val dominantPhase =
                    currentWindowPhases.groupBy { it }.maxByOrNull { it.value.size }?.key ?: "pause"
            timeWindows.add(Pair(currentWindowStart, dominantPhase))
        }

        // Count cycles using the time windows (smoothed phases)
        var cycleCount = 0
        var prevPhase = ""
        var inhaleSeen = false
        var exhaleSeen = false

        for ((timestamp, phase) in timeWindows) {
            if (phase == "inhaling") {
                inhaleSeen = true
            } else if (phase == "exhaling" && inhaleSeen) {
                exhaleSeen = true
            }

            if (phase == "inhaling" && prevPhase != "inhaling" && inhaleSeen && exhaleSeen) {
                cycleCount++
                inhaleSeen = true
                exhaleSeen = false
            }

            prevPhase = phase
        }

        // If no cycles detected, estimate based on phase changes
        if (cycleCount == 0) {
            var phaseChanges = 0
            for (i in 1 until timeWindows.size) {
                if (timeWindows[i].second != timeWindows[i - 1].second) {
                    phaseChanges++
                }
            }
            cycleCount = kotlin.math.max(1, phaseChanges / 2)
        }

        return kotlin.math.max(1, cycleCount)
    }

    @Composable
    fun BoofCVQRCodeOverlay(
            qrDetections: List<BoofCvQrDetection>,
            imageWidth: Float,
            imageHeight: Float,
            rotationDegrees: Int,
            modifier: Modifier = Modifier
    ) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val viewWidth = size.width
            val viewHeight = size.height

            val (width, height) =
                    if (rotationDegrees % 180 == 90) {
                        Pair(imageHeight, imageWidth)
                    } else {
                        Pair(imageWidth, imageHeight)
                    }

            val scaleX = viewWidth / width
            val scaleY = viewHeight / height
            val scale = minOf(scaleX, scaleY)

            val offsetX = (viewWidth - width * scale) / 2
            val offsetY = (viewHeight - height * scale) / 2

            qrDetections.forEach { detection ->
                val transformedCorners =
                        detection.corners.map { corner ->
                            val rotatedCorner =
                                    when (rotationDegrees) {
                                        90 -> Offset(corner.y, imageWidth - corner.x)
                                        180 -> Offset(imageWidth - corner.x, imageHeight - corner.y)
                                        270 -> Offset(imageHeight - corner.y, corner.x)
                                        else -> corner
                                    }

                            Offset(
                                    x = offsetX + rotatedCorner.x * scale,
                                    y = offsetY + rotatedCorner.y * scale
                            )
                        }

                // Calculate DYNAMIC stroke width based on QR code size
                val qrWidth = kotlin.math.abs(transformedCorners[0].x - transformedCorners[2].x)
                val qrHeight = kotlin.math.abs(transformedCorners[0].y - transformedCorners[2].y)
                val qrSize = kotlin.math.sqrt(qrWidth * qrWidth + qrHeight * qrHeight)

                // Dynamic stroke width: larger QR codes get thicker lines (3-12f range)
                val dynamicStrokeWidth = (qrSize / 30f).coerceIn(3f, 12f)

                for (i in transformedCorners.indices) {
                    drawLine(
                            color = Color.Green,
                            start = transformedCorners[i],
                            end = transformedCorners[(i + 1) % transformedCorners.size],
                            strokeWidth = dynamicStrokeWidth // Dynamic width based on QR size
                    )
                }

                val center =
                        when (rotationDegrees) {
                            90 -> Offset(detection.center.y, imageWidth - detection.center.x)
                            180 ->
                                    Offset(
                                            imageWidth - detection.center.x,
                                            imageHeight - detection.center.y
                                    )
                            270 -> Offset(imageHeight - detection.center.y, detection.center.x)
                            else -> detection.center
                        }

                val screenCenter =
                        Offset(x = offsetX + center.x * scale, y = offsetY + center.y * scale)

                // Dynamic center dot size based on QR code size (8-20f range)
                val dynamicCenterRadius = (qrSize / 50f).coerceIn(8f, 20f)

                drawCircle(
                        color = Color.Red.copy(alpha = 0.9f),
                        radius = dynamicCenterRadius, // Dynamic radius based on QR size
                        center = screenCenter
                )
            }
        }
    }

    /** Start 3x3 QR grid calibration mode for optimal positioning */
    private fun startQrGridCalibration() {
        isGridCalibrating = true
        gridCalibrationPositions.clear()

        Toast.makeText(
                        this,
                        "QR Grid Calibration: Position 3x3 QR codes on chest. Center QR should be at breathing center.",
                        Toast.LENGTH_LONG
                )
                .show()

        Log.d("QRGridCalibration", "🎯 Starting 3x3 QR Grid Calibration")

        Handler(Looper.getMainLooper())
                .postDelayed({ completeQrGridCalibration() }, GRID_CALIBRATION_DURATION)
    }

    /** Complete QR grid calibration and provide positioning feedback */
    private fun completeQrGridCalibration() {
        isGridCalibrating = false

        val detectedQrCount = gridCalibrationPositions.size
        val expectedQrCount = 9 // 3x3 grid

        when {
            detectedQrCount >= 7 -> {
                // Good grid setup
                val centerQr = findCenterQrCode(gridCalibrationPositions)
                Toast.makeText(
                                this,
                                "✅ Excellent QR Grid Setup! Detected $detectedQrCount/9 codes. Center QR: $centerQr",
                                Toast.LENGTH_LONG
                        )
                        .show()

                Log.d("QRGridCalibration", "✅ EXCELLENT grid setup: $detectedQrCount QRs detected")
                logGridPositions()
            }
            detectedQrCount >= 4 -> {
                // Acceptable grid setup
                Toast.makeText(
                                this,
                                "⚠️ Acceptable QR Grid Setup. Detected $detectedQrCount/9 codes. Try to position all 9 for best tracking.",
                                Toast.LENGTH_LONG
                        )
                        .show()

                Log.d(
                        "QRGridCalibration",
                        "⚠️ ACCEPTABLE grid setup: $detectedQrCount QRs detected"
                )
                logGridPositions()
            }
            else -> {
                // Poor grid setup
                Toast.makeText(
                                this,
                                "❌ Poor QR Grid Setup. Only $detectedQrCount/9 codes detected. Please reposition QR codes and try again.",
                                Toast.LENGTH_SHORT
                        )
                        .show()

                Log.d("QRGridCalibration", "❌ POOR grid setup: only $detectedQrCount QRs detected")
            }
        }
    }

    /** Find the center QR code from detected positions */
    private fun findCenterQrCode(positions: Map<String, Offset>): String? {
        if (positions.isEmpty()) return null

        // Calculate centroid of all detected QR codes
        val avgX = positions.values.map { it.x }.average().toFloat()
        val avgY = positions.values.map { it.y }.average().toFloat()
        val centroid = Offset(avgX, avgY)

        // Find QR code closest to centroid (likely the center one)
        return positions
                .minByOrNull {
                    val dx = it.value.x - centroid.x
                    val dy = it.value.y - centroid.y
                    kotlin.math.sqrt(dx * dx + dy * dy)
                }
                ?.key
    }

    /** Log grid positions for debugging */
    private fun logGridPositions() {
        Log.d("QRGridCalibration", "=== QR GRID POSITIONS ===")
        gridCalibrationPositions.forEach { (qrId, position) ->
            Log.d("QRGridCalibration", "QR '$qrId': (${position.x.toInt()}, ${position.y.toInt()})")
        }

        if (gridCalibrationPositions.size >= 3) {
            val centerQr = findCenterQrCode(gridCalibrationPositions)
            Log.d("QRGridCalibration", "Recommended center QR for tracking: '$centerQr'")

            // Calculate grid stability
            val positions = gridCalibrationPositions.values
            val avgDistance =
                    positions
                            .map { pos1 ->
                                positions
                                        .map { pos2 ->
                                            val dx = pos1.x - pos2.x
                                            val dy = pos1.y - pos2.y
                                            kotlin.math.sqrt(dx * dx + dy * dy)
                                        }
                                        .average()
                            }
                            .average()

            Log.d(
                    "QRGridCalibration",
                    "Grid stability (avg distance): ${avgDistance.toInt()} pixels"
            )

            if (avgDistance > 200) {
                Log.d("QRGridCalibration", "✅ Good QR spacing for stable tracking")
            } else {
                Log.d("QRGridCalibration", "⚠️ QR codes too close - consider wider spacing")
            }
        }
    }

    /** Toggle the alignment guide visibility */
    private fun toggleAlignmentGuide() {
        showAlignmentGuide = !showAlignmentGuide
    }

    @Composable
    fun QRAlignmentGuide(modifier: Modifier = Modifier) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2

            // Draw center crosshair
            val crosshairLength = 40f
            val crosshairColor = Color.White.copy(alpha = 0.8f)

            // Horizontal line
            drawLine(
                    color = crosshairColor,
                    start = Offset(centerX - crosshairLength, centerY),
                    end = Offset(centerX + crosshairLength, centerY),
                    strokeWidth = 3f
            )

            // Vertical line
            drawLine(
                    color = crosshairColor,
                    start = Offset(centerX, centerY - crosshairLength),
                    end = Offset(centerX, centerY + crosshairLength),
                    strokeWidth = 3f
            )

            // Center dot
            drawCircle(
                    color = Color.Red.copy(alpha = 0.9f),
                    radius = 8f,
                    center = Offset(centerX, centerY)
            )

            // Outer circle guide
            drawCircle(
                    color = Color.White.copy(alpha = 0.6f),
                    radius = 60f,
                    center = Offset(centerX, centerY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        breathingClassifier.close()
        yoloChestDetector?.release()
        yoloChestDetector = null
        chestTracker = null
    }

    override fun onResume() {
        super.onResume()
        // Enable NFC foreground dispatch when app is in foreground
        if (nfcManager.isNFCAvailable() && nfcManager.isNFCEnabled()) {
            nfcManager.enableForegroundDispatch(this)
            Log.d("MainActivity", "✅ NFC foreground dispatch enabled")
        }
    }

    override fun onPause() {
        super.onPause()
        // Disable NFC foreground dispatch when app goes to background
        if (nfcManager.isNFCAvailable()) {
            nfcManager.disableForegroundDispatch(this)
            Log.d("MainActivity", "✅ NFC foreground dispatch disabled")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "📱 onNewIntent called with action: ${intent.action}")

        // Handle NFC intents
        handleNFCIntent(intent)

        // Set the new intent so getIntent() returns it
        setIntent(intent)
    }

    /** Handle NFC intents to read patient data from NFC tags */
    private fun handleNFCIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        Log.d("MainActivity", "🏷️ Handling intent with action: $action")

        // Only process NFC intents
        if (action == android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED ||
                        action == android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED
        ) {

            Log.d("MainActivity", "📖 NFC intent detected")

            val patientData = nfcManager.handleIntent(intent)

            if (patientData != null) {
                Log.d("MainActivity", "✅ Patient data read from NFC: $patientData")

                // Convert NFC data to PatientMetadata and update ViewModel
                val patientMetadata =
                        PatientMetadata(
                                id = patientData.id,
                                age = patientData.age,
                                gender = patientData.gender,
                                healthStatus = patientData.healthStatus,
                                additionalNotes =
                                        "Loaded from NFC tag at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(patientData.timestamp))}"
                        )

                // Update the ViewModel with the NFC data
                viewModel.updatePatientMetadata(patientMetadata)

                // Show success message to user
                Toast.makeText(
                                this,
                                "✅ Patient data loaded from NFC tag!\nPatient: ${patientData.id}",
                                Toast.LENGTH_LONG
                        )
                        .show()

                Log.d("MainActivity", "✅ Patient metadata updated from NFC tag")
            } else {
                Log.w("MainActivity", "⚠️ No patient data found in NFC tag")
                Toast.makeText(this, "⚠️ No patient data found in NFC tag", Toast.LENGTH_SHORT)
                        .show()
            }
        }
    }
}
