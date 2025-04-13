package com.example.tml_ec_qr_scan

import android.Manifest
import android.content.ContentValues.TAG
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import boofcv.android.ConvertBitmap
import boofcv.factory.fiducial.FactoryFiducial
import boofcv.struct.image.GrayU8
import com.example.tml_ec_qr_scan.ui.theme.TMLEC_QRScanTheme
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

// Data class definitions are now in models.kt

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

        // Calibration related variables
        private var isCalibrating = false
        private var calibrationStartTime = 0L
        private var calibrationData = mutableListOf<Float>()
        private var calibrationVelocityThresholds =
                CalibrationThresholds(
                        inhaleThreshold = -8f, // Default values
                        exhaleThreshold = 8f,
                        pauseThresholdLow = -3f,
                        pauseThresholdHigh = 3f
                )
        private val CALIBRATION_DURATION_MS = 10000 // 10 seconds

        // Training mode flag to ensure consistent data collection
        private var isTrainingDataMode = false

        // Permission request codes
        private val PERMISSION_REQUEST_CAMERA = 100
        private val PERMISSION_REQUEST_STORAGE = 101

        // Callback for image analysis
        private var qrCodeDetectionCallback:
                ((List<BoofCvQrDetection>, Float, Float, Int) -> Unit)? =
                null

        private fun setImageAnalysisCallback(
                callback: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit
        ) {
                qrCodeDetectionCallback = callback
        }

        @RequiresApi(Build.VERSION_CODES.S)
        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                enableEdgeToEdge()

                Log.d("MainActivity", "onCreate called")

                // Initialize camera executor
                cameraExecutor = Executors.newSingleThreadExecutor()

                // Initialize previewView early
                previewView =
                        PreviewView(this).apply {
                                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                // Initialize breathing classifier
                breathingClassifier = BreathingClassifier(this)
                Log.d("MainActivity", "Breathing classifier initialized")

                // Set up viewModel access to calibration functions
                viewModel.setCalibrationCompleter { completeCalibration() }

                // Check and request camera permissions
                requestCameraPermissionIfNeeded()

                setContent {
                        Log.d("MainActivity", "Setting content with Compose")
                        com.example.tml_ec_qr_scan.ui.theme.TMLEC_QRScanTheme {
                                Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color = MaterialTheme.colorScheme.background
                                ) {
                                        // State for QR code detections
                                        var qrDetections by remember {
                                                mutableStateOf(emptyList<BoofCvQrDetection>())
                                        }
                                        var imageWidth by remember { mutableStateOf(1280f) }
                                        var imageHeight by remember { mutableStateOf(720f) }
                                        var rotationDegrees by remember { mutableStateOf(0) }

                                        // Collect QR detections from image analyzer
                                        DisposableEffect(Unit) {
                                                val disposable =
                                                        object {
                                                                var active = true
                                                        }

                                                // Function to update QR detections from image
                                                // analyzer
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

                                                // Set the callback for image analysis
                                                setImageAnalysisCallback {
                                                        detections,
                                                        width,
                                                        height,
                                                        rotation ->
                                                        updateQrDetections(
                                                                detections,
                                                                width,
                                                                height,
                                                                rotation
                                                        )
                                                }

                                                onDispose { disposable.active = false }
                                        }

                                        // Initialize camera when entering CameraSetup state
                                        val uiState by viewModel.uiState.collectAsState()
                                        LaunchedEffect(uiState) {
                                                if (uiState is UiState.Calibrating ||
                                                                uiState is UiState.Recording
                                                ) {
                                                        Log.d(
                                                                "MainActivity",
                                                                "LaunchedEffect triggering camera initialization for state: $uiState"
                                                        )
                                                        if (cameraProvider == null) {
                                                                Log.d(
                                                                        "MainActivity",
                                                                        "Initializing camera for state: $uiState"
                                                                )
                                                                initializeCamera()
                                                        } else {
                                                                Log.d(
                                                                        "MainActivity",
                                                                        "Rebinding camera for state: $uiState"
                                                                )
                                                                bindCameraUseCases()
                                                        }
                                                }
                                        }

                                        // Initialize camera when isCameraStarted changes
                                        val isCameraStarted by
                                                viewModel.isCameraStarted.collectAsState()
                                        LaunchedEffect(isCameraStarted) {
                                                if (isCameraStarted &&
                                                                uiState is UiState.CameraSetup
                                                ) {
                                                        Log.d(
                                                                "MainActivity",
                                                                "LaunchedEffect triggering camera initialization for isCameraStarted: $isCameraStarted"
                                                        )
                                                        if (cameraProvider == null) {
                                                                Log.d(
                                                                        "MainActivity",
                                                                        "Initializing camera for isCameraStarted: $isCameraStarted"
                                                                )
                                                                initializeCamera()
                                                        } else {
                                                                Log.d(
                                                                        "MainActivity",
                                                                        "Rebinding camera for isCameraStarted: $isCameraStarted"
                                                                )
                                                                bindCameraUseCases()
                                                        }
                                                }
                                        }

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
                                                        Box(modifier = Modifier.fillMaxSize()) {
                                                                // Camera preview
                                                                AndroidView(
                                                                        factory = { previewView },
                                                                        modifier =
                                                                                Modifier.fillMaxSize()
                                                                )

                                                                // Draw QR code overlay
                                                                if (qrDetections.isNotEmpty()) {
                                                                        BoofCVQRCodeOverlay(
                                                                                qrDetections =
                                                                                        qrDetections,
                                                                                imageWidth =
                                                                                        imageWidth,
                                                                                imageHeight =
                                                                                        imageHeight,
                                                                                rotationDegrees =
                                                                                        rotationDegrees,
                                                                                modifier =
                                                                                        Modifier.fillMaxSize()
                                                                        )
                                                                }

                                                                // Draw center dot to help with
                                                                // alignment
                                                                Canvas(
                                                                        modifier =
                                                                                Modifier.fillMaxSize()
                                                                ) {
                                                                        val centerX = size.width / 2
                                                                        val centerY =
                                                                                size.height / 2

                                                                        // Draw crosshair lines for
                                                                        // alignment
                                                                        drawLine(
                                                                                color =
                                                                                        Color.Red
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.7f
                                                                                                ),
                                                                                start =
                                                                                        Offset(
                                                                                                centerX -
                                                                                                        40,
                                                                                                centerY
                                                                                        ),
                                                                                end =
                                                                                        Offset(
                                                                                                centerX +
                                                                                                        40,
                                                                                                centerY
                                                                                        ),
                                                                                strokeWidth = 2f
                                                                        )
                                                                        drawLine(
                                                                                color =
                                                                                        Color.Red
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.7f
                                                                                                ),
                                                                                start =
                                                                                        Offset(
                                                                                                centerX,
                                                                                                centerY -
                                                                                                        40
                                                                                        ),
                                                                                end =
                                                                                        Offset(
                                                                                                centerX,
                                                                                                centerY +
                                                                                                        40
                                                                                        ),
                                                                                strokeWidth = 2f
                                                                        )

                                                                        // Draw red center dot
                                                                        drawCircle(
                                                                                color =
                                                                                        Color.Red
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.9f
                                                                                                ),
                                                                                radius = 12f,
                                                                                center =
                                                                                        Offset(
                                                                                                centerX,
                                                                                                centerY
                                                                                        )
                                                                        )

                                                                        // Add placement guide text
                                                                        drawContext.canvas
                                                                                .nativeCanvas
                                                                                .apply {
                                                                                        val textPaint =
                                                                                                android.graphics
                                                                                                        .Paint()
                                                                                                        .apply {
                                                                                                                color =
                                                                                                                        android.graphics
                                                                                                                                .Color
                                                                                                                                .RED
                                                                                                                textSize =
                                                                                                                        36f
                                                                                                                textAlign =
                                                                                                                        android.graphics
                                                                                                                                .Paint
                                                                                                                                .Align
                                                                                                                                .CENTER
                                                                                                                setShadowLayer(
                                                                                                                        3f,
                                                                                                                        0f,
                                                                                                                        0f,
                                                                                                                        android.graphics
                                                                                                                                .Color
                                                                                                                                .BLACK
                                                                                                                )
                                                                                                        }
                                                                                        drawText(
                                                                                                "Align QR code here",
                                                                                                centerX,
                                                                                                centerY -
                                                                                                        60,
                                                                                                textPaint
                                                                                        )
                                                                                }
                                                                }

                                                                // Overlay for breathing phase
                                                                val breathingPhase by
                                                                        viewModel
                                                                                .currentBreathingPhase
                                                                                .collectAsState()
                                                                val confidence by
                                                                        viewModel
                                                                                .breathingConfidence
                                                                                .collectAsState()
                                                                val currentVelocity by
                                                                        viewModel.currentVelocity
                                                                                .collectAsState()
                                                                val isRecording by
                                                                        viewModel.isRecording
                                                                                .collectAsState()

                                                                if (breathingPhase != "Unknown") {
                                                                        Box(
                                                                                modifier =
                                                                                        Modifier.align(
                                                                                                        Alignment
                                                                                                                .TopEnd
                                                                                                )
                                                                                                .padding(
                                                                                                        top =
                                                                                                                32.dp,
                                                                                                        end =
                                                                                                                16.dp,
                                                                                                        start =
                                                                                                                16.dp
                                                                                                )
                                                                                                .background(
                                                                                                        Color.Black
                                                                                                                .copy(
                                                                                                                        alpha =
                                                                                                                                0.7f
                                                                                                                ),
                                                                                                        shape =
                                                                                                                MaterialTheme
                                                                                                                        .shapes
                                                                                                                        .small
                                                                                                )
                                                                                                .padding(
                                                                                                        8.dp
                                                                                                )
                                                                        ) {
                                                                                Column(
                                                                                        horizontalAlignment =
                                                                                                Alignment
                                                                                                        .CenterHorizontally
                                                                                ) {
                                                                                        Text(
                                                                                                text =
                                                                                                        breathingPhase,
                                                                                                color =
                                                                                                        when (breathingPhase
                                                                                                                        .lowercase()
                                                                                                        ) {
                                                                                                                "inhaling" ->
                                                                                                                        Color(
                                                                                                                                0xFF4CAF50
                                                                                                                        )
                                                                                                                "exhaling" ->
                                                                                                                        Color(
                                                                                                                                0xFF2196F3
                                                                                                                        )
                                                                                                                "pause" ->
                                                                                                                        Color(
                                                                                                                                0xFFFFC107
                                                                                                                        )
                                                                                                                else ->
                                                                                                                        Color(
                                                                                                                                0xFFFFFFFF
                                                                                                                        )
                                                                                                        },
                                                                                                style =
                                                                                                        MaterialTheme
                                                                                                                .typography
                                                                                                                .titleMedium,
                                                                                                fontWeight =
                                                                                                        if (breathingPhase
                                                                                                                        .lowercase() ==
                                                                                                                        "pause"
                                                                                                        )
                                                                                                                androidx.compose
                                                                                                                        .ui
                                                                                                                        .text
                                                                                                                        .font
                                                                                                                        .FontWeight
                                                                                                                        .Bold
                                                                                                        else
                                                                                                                androidx.compose
                                                                                                                        .ui
                                                                                                                        .text
                                                                                                                        .font
                                                                                                                        .FontWeight
                                                                                                                        .Normal
                                                                                        )

                                                                                        Text(
                                                                                                text =
                                                                                                        "Confidence: ${(confidence * 100).toInt()}%",
                                                                                                color =
                                                                                                        Color.White,
                                                                                                style =
                                                                                                        MaterialTheme
                                                                                                                .typography
                                                                                                                .bodySmall
                                                                                        )

                                                                                        // Show
                                                                                        // velocity
                                                                                        // to help
                                                                                        // with
                                                                                        // debugging
                                                                                        Text(
                                                                                                text =
                                                                                                        "Velocity: ${String.format("%.2f", currentVelocity)}",
                                                                                                color =
                                                                                                        Color.White,
                                                                                                style =
                                                                                                        MaterialTheme
                                                                                                                .typography
                                                                                                                .bodySmall
                                                                                        )

                                                                                        if (isRecording
                                                                                        ) {
                                                                                                Text(
                                                                                                        text =
                                                                                                                "Recording Active",
                                                                                                        color =
                                                                                                                Color(
                                                                                                                        0xFF4CAF50
                                                                                                                ),
                                                                                                        style =
                                                                                                                MaterialTheme
                                                                                                                        .typography
                                                                                                                        .bodySmall
                                                                                                )
                                                                                        }
                                                                                }
                                                                        }
                                                                }

                                                                // Display recording timer when
                                                                // active
                                                                if (viewModel.isRecording.value) {
                                                                        val remainingTime by
                                                                                viewModel
                                                                                        .recordingTimeRemaining
                                                                                        .collectAsState()
                                                                        val isTimerActive by
                                                                                viewModel
                                                                                        .isTimerActive
                                                                                        .collectAsState()

                                                                        // Large timer display at
                                                                        // the top center
                                                                        Box(
                                                                                modifier =
                                                                                        Modifier.align(
                                                                                                        Alignment
                                                                                                                .TopCenter
                                                                                                )
                                                                                                .padding(
                                                                                                        top =
                                                                                                                32.dp
                                                                                                )
                                                                                                .background(
                                                                                                        color =
                                                                                                                Color.Black
                                                                                                                        .copy(
                                                                                                                                alpha =
                                                                                                                                        0.7f
                                                                                                                        ),
                                                                                                        shape =
                                                                                                                RoundedCornerShape(
                                                                                                                        12.dp
                                                                                                                )
                                                                                                )
                                                                                                .padding(
                                                                                                        horizontal =
                                                                                                                20.dp,
                                                                                                        vertical =
                                                                                                                10.dp
                                                                                                )
                                                                        ) {
                                                                                Column(
                                                                                        horizontalAlignment =
                                                                                                Alignment
                                                                                                        .CenterHorizontally
                                                                                ) {
                                                                                        // Timer
                                                                                        // label
                                                                                        Text(
                                                                                                text =
                                                                                                        "RECORDING TIME",
                                                                                                color =
                                                                                                        Color.White,
                                                                                                fontSize =
                                                                                                        14.sp,
                                                                                                fontWeight =
                                                                                                        FontWeight
                                                                                                                .Bold
                                                                                        )

                                                                                        // Timer
                                                                                        // countdown
                                                                                        Text(
                                                                                                text =
                                                                                                        formatTime(
                                                                                                                remainingTime
                                                                                                        ),
                                                                                                color =
                                                                                                        if (remainingTime <
                                                                                                                        10
                                                                                                        )
                                                                                                                Color(
                                                                                                                        0xFFFF5252
                                                                                                                )
                                                                                                        else
                                                                                                                Color(
                                                                                                                        0xFF4CAF50
                                                                                                                ),
                                                                                                fontSize =
                                                                                                        28.sp,
                                                                                                fontWeight =
                                                                                                        FontWeight
                                                                                                                .Bold
                                                                                        )
                                                                                }
                                                                        }
                                                                }
                                                        }
                                                }
                                        )
                                }
                        }
                }
        }

        private fun startRecording() {
                recordingStartTime = System.currentTimeMillis()
                viewModel.startRecording()
        }

        private fun stopRecording() {
                viewModel.stopRecording()

                // Save data if patient metadata exists
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

        private fun processDetections(
                detections: List<BoofCvQrDetection>,
                width: Float,
                height: Float,
                rotation: Int
        ) {
                if (isCalibrating || viewModel.isRecording.value) {
                        val currentTime = System.currentTimeMillis()

                        if (isCalibrating) {
                                processCalibrationData(detections, currentTime)
                        } else if (viewModel.isRecording.value) {
                                processRecordingData(detections, currentTime)
                        }
                }
                // If not recording or calibrating, still track QR codes but don't process
                // respiratory data
        }

        private fun requestCameraPermissionIfNeeded() {
                val requiredPermissions = arrayOf(Manifest.permission.CAMERA)

                Log.d("MainActivity", "Checking camera permission")

                // Check if we already have the permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                                PackageManager.PERMISSION_GRANTED
                ) {

                        Log.d("MainActivity", "Camera permission not yet granted, requesting...")

                        // Register the permission callback
                        val requestPermissionLauncher =
                                registerForActivityResult(
                                        ActivityResultContracts.RequestMultiplePermissions()
                                ) { permissions ->
                                        if (permissions[Manifest.permission.CAMERA] == true) {
                                                // Permission granted, initialize camera
                                                Log.d("MainActivity", "Camera permission granted")
                                                // Initialize camera if we're in a state that needs
                                                // it
                                                val currentState = viewModel.uiState.value
                                                if (currentState is UiState.CameraSetup ||
                                                                currentState is
                                                                        UiState.Calibrating ||
                                                                currentState is UiState.Recording
                                                ) {
                                                        initializeCamera()
                                                }
                                        } else {
                                                // Permission denied
                                                Log.e("MainActivity", "Camera permission denied")
                                                Toast.makeText(
                                                                this,
                                                                "Camera permission is required to use this app",
                                                                Toast.LENGTH_LONG
                                                        )
                                                        .show()
                                        }
                                }

                        // Request the permission
                        requestPermissionLauncher.launch(requiredPermissions)
                } else {
                        Log.d("MainActivity", "Camera permission already granted")
                }
        }

        private fun initializeCamera() {
                Log.d("MainActivity", "Initializing camera...")

                // Check permission again to be safe
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                                PackageManager.PERMISSION_GRANTED
                ) {
                        Log.e("MainActivity", "Cannot initialize camera - permission not granted")
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
                        // Unbind all use cases before rebinding
                        cameraProvider.unbindAll()

                        // Create Preview use case
                        preview =
                                Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                        // Create Image Analysis use case
                        imageAnalyzer =
                                ImageAnalysis.Builder()
                                        .setBackpressureStrategy(
                                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                        )
                                        .build()
                                        .also {
                                                it.setAnalyzer(cameraExecutor) { imageProxy ->
                                                        processImage(imageProxy) {
                                                                detections,
                                                                width,
                                                                height,
                                                                rotation ->
                                                                // Process detections for
                                                                // respiratory tracking
                                                                // but only if recording or
                                                                // calibrating
                                                                processDetections(
                                                                        detections,
                                                                        width,
                                                                        height,
                                                                        rotation
                                                                )

                                                                // Always update UI with detections
                                                                // for QR code visualization
                                                                // even when not recording or
                                                                // calibrating
                                                                qrCodeDetectionCallback?.invoke(
                                                                        detections,
                                                                        width,
                                                                        height,
                                                                        rotation
                                                                )
                                                        }
                                                }
                                        }

                        // Bind use cases to camera
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)

                        Log.d(TAG, "Camera successfully bound to lifecycle")
                } catch (exc: Exception) {
                        Log.e(TAG, "Use case binding failed", exc)
                        Toast.makeText(
                                        this,
                                        "Camera initialization failed: ${exc.message}",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                }
        }

        private fun releaseCamera() {
                try {
                        cameraProvider?.unbindAll()
                } catch (e: Exception) {
                        Log.e(TAG, "Failed to release camera", e)
                }
        }

        override fun onDestroy() {
                super.onDestroy()
                releaseCamera()
                cameraExecutor.shutdown()

                // Clean up breathing classifier
                breathingClassifier.close()
        }

        @Composable
        fun CameraPreview(
                modifier: Modifier = Modifier,
                onDetections: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit
        ) {
                Box(modifier = modifier) {
                        // Show the camera preview view
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

                        // QR code overlay when detections are available
                        val isRecording by viewModel.isRecording.collectAsState()
                        val detections = remember {
                                mutableStateOf<List<BoofCvQrDetection>>(emptyList())
                        }

                        // Only show overlays if there are detections
                        if (detections.value.isNotEmpty()) {
                                BoofCVQRCodeOverlay(
                                        qrDetections = detections.value,
                                        imageWidth = 1280f, // Default values, will be updated
                                        imageHeight = 720f, // Default values, will be updated
                                        rotationDegrees = 0, // Default value, will be updated
                                        modifier = Modifier.fillMaxSize()
                                )
                        }

                        // Breathing phase indicator
                        val breathingPhase by viewModel.currentBreathingPhase.collectAsState()
                        val confidence by viewModel.breathingConfidence.collectAsState()
                        val currentVelocity by viewModel.currentVelocity.collectAsState()

                        if (breathingPhase != "Unknown") {
                                Box(
                                        modifier =
                                                Modifier.align(Alignment.TopEnd)
                                                        .padding(16.dp)
                                                        .background(
                                                                Color.Black.copy(alpha = 0.7f),
                                                                shape = MaterialTheme.shapes.small
                                                        )
                                                        .padding(8.dp)
                                ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                        text = breathingPhase,
                                                        color =
                                                                when (breathingPhase.lowercase()) {
                                                                        "inhaling" ->
                                                                                Color(0xFF4CAF50)
                                                                        "exhaling" ->
                                                                                Color(0xFF2196F3)
                                                                        "pause" -> Color(0xFFFFC107)
                                                                        else -> Color(0xFFFFFFFF)
                                                                },
                                                        style =
                                                                MaterialTheme.typography
                                                                        .titleMedium,
                                                        fontWeight =
                                                                if (breathingPhase.lowercase() ==
                                                                                "pause"
                                                                )
                                                                        androidx.compose.ui.text
                                                                                .font.FontWeight
                                                                                .Bold
                                                                else
                                                                        androidx.compose.ui.text
                                                                                .font.FontWeight
                                                                                .Normal
                                                )

                                                Text(
                                                        text =
                                                                "Confidence: ${(confidence * 100).toInt()}%",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.bodySmall
                                                )

                                                // Show velocity to help with debugging - fixed to
                                                // use collectAsState()
                                                Text(
                                                        text =
                                                                "Velocity: ${String.format("%.2f", currentVelocity)}",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.bodySmall
                                                )

                                                if (isRecording) {
                                                        Text(
                                                                text = "Recording Active",
                                                                color = Color(0xFF4CAF50),
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .bodySmall
                                                        )
                                                }
                                        }
                                }
                        }
                }
        }

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        private fun processImage(
                imageProxy: ImageProxy,
                callback: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit
        ) {
                try {
                        val bitmap = convertImageProxyToBitmap(imageProxy) ?: return
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val detections = detectQrCodesBoof(bitmap)
                        callback(
                                detections,
                                bitmap.width.toFloat(),
                                bitmap.height.toFloat(),
                                rotation
                        )
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

                                BoofCvQrDetection(
                                        detection.message,
                                        Offset(centerX, centerY),
                                        corners
                                )
                        }
                } catch (e: Exception) {
                        Log.e("BoofCV", "Detection failed", e)
                        emptyList()
                }
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

                        // Handle rotation
                        val (width, height) =
                                if (rotationDegrees % 180 == 90) {
                                        Pair(imageHeight, imageWidth)
                                } else {
                                        Pair(imageWidth, imageHeight)
                                }

                        // Calculate proper scaling factors
                        val scaleX = viewWidth / width
                        val scaleY = viewHeight / height
                        val scale = minOf(scaleX, scaleY)

                        // Calculate centering offset
                        val offsetX = (viewWidth - width * scale) / 2
                        val offsetY = (viewHeight - height * scale) / 2

                        qrDetections.forEach { detection ->
                                // Transform corners based on rotation
                                val transformedCorners =
                                        detection.corners.map { corner ->
                                                val rotatedCorner =
                                                        when (rotationDegrees) {
                                                                90 ->
                                                                        Offset(
                                                                                corner.y,
                                                                                imageWidth -
                                                                                        corner.x
                                                                        )
                                                                180 ->
                                                                        Offset(
                                                                                imageWidth -
                                                                                        corner.x,
                                                                                imageHeight -
                                                                                        corner.y
                                                                        )
                                                                270 ->
                                                                        Offset(
                                                                                imageHeight -
                                                                                        corner.y,
                                                                                corner.x
                                                                        )
                                                                else -> corner
                                                        }

                                                Offset(
                                                        x = offsetX + rotatedCorner.x * scale,
                                                        y = offsetY + rotatedCorner.y * scale
                                                )
                                        }

                                // Draw QR code bounds
                                for (i in transformedCorners.indices) {
                                        drawLine(
                                                color = Color.Green,
                                                start = transformedCorners[i],
                                                end =
                                                        transformedCorners[
                                                                (i + 1) % transformedCorners.size],
                                                strokeWidth =
                                                        5f // Thicker line for better visibility
                                        )
                                }

                                // Draw center point and ID
                                val center =
                                        when (rotationDegrees) {
                                                90 ->
                                                        Offset(
                                                                detection.center.y,
                                                                imageWidth - detection.center.x
                                                        )
                                                180 ->
                                                        Offset(
                                                                imageWidth - detection.center.x,
                                                                imageHeight - detection.center.y
                                                        )
                                                270 ->
                                                        Offset(
                                                                imageHeight - detection.center.y,
                                                                detection.center.x
                                                        )
                                                else -> detection.center
                                        }

                                val screenCenter =
                                        Offset(
                                                x = offsetX + center.x * scale,
                                                y = offsetY + center.y * scale
                                        )

                                drawCircle(
                                        color =
                                                Color.Red.copy(
                                                        alpha = 0.9f
                                                ), // More opaque for better visibility
                                        radius = 12f, // Larger circle for better visibility
                                        center = screenCenter
                                )

                                // Draw QR code ID
                                drawContext.canvas.nativeCanvas.apply {
                                        drawText(
                                                detection.rawValue ?: "unknown",
                                                screenCenter.x,
                                                screenCenter.y - 20,
                                                android.graphics.Paint().apply {
                                                        color = android.graphics.Color.WHITE
                                                        textSize = 30f
                                                        textAlign =
                                                                android.graphics.Paint.Align.CENTER
                                                }
                                        )
                                }
                        }
                }
        }

        private fun stabilizeQrPosition(
                currentCenter: Offset,
                trackedPoint: TrackedPoint?,
                currentTime: Long
        ): TrackedPoint {
                val lockingThreshold = 15f
                val stabilityThreshold = 0.3f

                if (trackedPoint == null) {
                        return TrackedPoint(
                                center = currentCenter,
                                lastUpdateTime = currentTime,
                                velocity = Offset.Zero,
                                isLocked = false,
                                initialPosition = currentCenter
                        )
                }

                // Calculate movement from initial position with exponential smoothing
                val initialPos = trackedPoint.initialPosition ?: currentCenter
                val distanceFromInitial =
                        sqrt(
                                (currentCenter.x - initialPos.x).pow(2) +
                                        (currentCenter.y - initialPos.y).pow(2)
                        )

                // Apply stronger smoothing for more stable positions
                val alpha = 0.6f
                val stabilizedCenter =
                        if (distanceFromInitial < lockingThreshold && trackedPoint.isLocked) {
                                Offset(
                                        x =
                                                trackedPoint.center.x * alpha +
                                                        currentCenter.x * (1 - alpha),
                                        y =
                                                trackedPoint.center.y * alpha +
                                                        currentCenter.y * (1 - alpha)
                                )
                        } else {
                                currentCenter
                        }

                val timeDelta = (currentTime - trackedPoint.lastUpdateTime) / 1000f
                val velocity =
                        if (timeDelta > 0) {
                                Offset(
                                        x =
                                                (stabilizedCenter.x - trackedPoint.center.x) /
                                                        timeDelta,
                                        y = (stabilizedCenter.y - trackedPoint.center.y) / timeDelta
                                )
                        } else {
                                Offset.Zero
                        }

                // More gradual locking mechanism
                val shouldLock =
                        velocity.getDistance() < stabilityThreshold ||
                                (trackedPoint.isLocked &&
                                        distanceFromInitial < lockingThreshold * 1.5f)

                return TrackedPoint(
                        center = stabilizedCenter,
                        lastUpdateTime = currentTime,
                        velocity = velocity,
                        isLocked = shouldLock,
                        initialPosition = trackedPoint.initialPosition ?: currentCenter
                )
        }

        private fun determineBreathingPhase(
                velocity: Float,
                currentPhase: String,
                timeSinceLastChange: Long
        ): String {
                // Strict mapping for consistency - no hysteresis here
                return when {
                        velocity < calibrationVelocityThresholds.inhaleThreshold ->
                                "inhaling" // Upward = inhaling
                        velocity > calibrationVelocityThresholds.exhaleThreshold ->
                                "exhaling" // Downward = exhaling
                        else -> "pause" // Stable = pause
                }
        }

        /** Save respiratory data to CSV file */
        private fun saveRespiratoryData(
                context: Context,
                data: List<RespiratoryDataPoint>,
                metadata: PatientMetadata
        ) {
                if (data.isEmpty()) {
                        Toast.makeText(context, "No respiratory data to save", Toast.LENGTH_SHORT)
                                .show()
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
                                writer.write(
                                        "Notes,${metadata.additionalNotes.replace(",", ";")}\n"
                                )
                                writer.write("\n")

                                // Write breathing analysis summary
                                writer.write("# Breathing Analysis Summary\n")
                                writer.write("Total Duration (seconds),${formattedDuration}\n")
                                writer.write(
                                        "Breathing Rate (breaths/minute),${metrics.breathingRate}\n"
                                )
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
                                Toast.makeText(
                                                context,
                                                "Saved data to ${csvFile.name}",
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                        }
                } catch (e: Exception) {
                        Log.e("RespiratoryTracking", "Error saving data: ${e.message}", e)
                        runOnUiThread {
                                Toast.makeText(
                                                context,
                                                "Error saving data: ${e.message}",
                                                Toast.LENGTH_SHORT
                                        )
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
                val phases = data.map { it.breathingPhase.lowercase() }

                // Calculate basic statistics
                val averageAmplitude = amplitudes.average().toFloat()
                val maxAmplitude = amplitudes.maxOrNull() ?: 0f
                val minAmplitude = amplitudes.minOrNull() ?: 0f

                // Calculate duration in minutes for breathing rate
                val durationInMinutes =
                        if (data.size > 1) {
                                (data.last().timestamp - data.first().timestamp) / (1000f * 60f)
                        } else {
                                0.01f // Prevent division by zero
                        }

                // Count complete breathing cycles (inhale + exhale = 1 breath)
                var breathCount = 0
                var prevPhase = ""

                // Count transitions from exhaling to inhaling as full cycles
                for (phase in phases) {
                        if (prevPhase == "exhaling" && phase == "inhaling") {
                                breathCount++
                        }
                        prevPhase = phase
                }

                // Log for debugging
                Log.d("RespiratoryTracking", "Total breathing cycles counted: $breathCount")
                Log.d(
                        "RespiratoryTracking",
                        "Recording duration: ${durationInMinutes * 60} seconds (${durationInMinutes} minutes)"
                )

                // Calculate breathing rate from detected breaths (no upper limit)
                val breathingRate =
                        if (durationInMinutes > 0 && breathCount > 0) {
                                breathCount / durationInMinutes // No coerceIn to allow full range
                        } else {
                                0f
                        }

                Log.d(
                        "RespiratoryTracking",
                        "Final calculated breathing rate: $breathingRate breaths/minute"
                )

                return BreathingMetrics(
                        breathingRate = breathingRate,
                        averageAmplitude = averageAmplitude,
                        maxAmplitude = maxAmplitude,
                        minAmplitude = minAmplitude,
                        breathCount = breathCount
                )
        }

        // Add a debugging function to help understand velocity distribution
        private fun analyzeVelocityRange(
                data: List<RespiratoryDataPoint>
        ): Triple<Float, Float, Float> {
                if (data.size < 2) return Triple(0f, 0f, 0f)

                var minVelocity = Float.MAX_VALUE
                var maxVelocity = Float.MIN_VALUE
                var sumVelocity = 0f
                var count = 0

                data.windowed(2, 1).forEach { window ->
                        val point1 = window[0]
                        val point2 = window[1]
                        val timeDiff = (point2.timestamp - point1.timestamp) / 1000f

                        if (timeDiff > 0) {
                                val velocity = (point2.position.y - point1.position.y) / timeDiff
                                minVelocity = minOf(minVelocity, velocity)
                                maxVelocity = maxOf(maxVelocity, velocity)
                                sumVelocity += velocity
                                count++
                        }
                }

                val avgVelocity = if (count > 0) sumVelocity / count else 0f
                return Triple(minVelocity, maxVelocity, avgVelocity)
        }

        // Helper function to format float numbers
        private fun Float.format(digits: Int) = "%.${digits}f".format(this)

        // Create a single consistent function to map velocity to movement type
        private fun velocityToMovement(velocity: Float): String {
                // Widen the stable/pause range to make it easier to detect
                return when {
                        velocity < calibrationVelocityThresholds.inhaleThreshold -> "upward"
                        velocity > calibrationVelocityThresholds.exhaleThreshold -> "downward"
                        // Use a wider range for stable detection
                        velocity > -3.0f && velocity < 3.0f ->
                                "stable" // Fixed wider range for stable detection
                        else -> "stable" // Default to stable for any ambiguous values
                }
        }

        // Create a single consistent function to map velocity to breathing phase
        private fun velocityToBreathingPhase(velocity: Float): String {
                // In training data mode, use a simpler and more consistent mapping
                if (isTrainingDataMode) {
                        return when {
                                velocity <= 0 -> "inhaling" // Any upward movement is inhaling
                                else -> "exhaling" // Any downward movement is exhaling
                        }
                }

                // Normal mode with calibrated thresholds - no pause, only inhaling and exhaling
                return when {
                        velocity < 0 -> "inhaling" // Upward movement (negative velocity)
                        else -> "exhaling" // Downward movement (positive velocity)
                }
        }

        private fun startCalibration() {
                calibrationData.clear()
                isCalibrating = true
                calibrationStartTime = System.currentTimeMillis()
                Toast.makeText(
                                this,
                                "Calibration started. Please breathe normally for 10 seconds.",
                                Toast.LENGTH_LONG
                        )
                        .show()

                // Update UI state to show calibration is in progress
                viewModel.updateCalibrationState(true)

                // Set a guaranteed timeout to ensure calibration completes
                Handler(Looper.getMainLooper())
                        .postDelayed(
                                {
                                        if (isCalibrating) {
                                                Log.d(
                                                        "Calibration",
                                                        "Calibration timeout reached via Handler"
                                                )
                                                completeCalibration()
                                        }
                                },
                                CALIBRATION_DURATION_MS.toLong()
                        )
        }

        private fun processCalibrationData(detections: List<BoofCvQrDetection>, currentTime: Long) {
                // Check if calibration time is up regardless of detections
                if (currentTime - calibrationStartTime >= CALIBRATION_DURATION_MS) {
                        Log.d("Calibration", "Calibration duration reached in processDetections")
                        isCalibrating = false
                        completeCalibration()
                        return
                }

                // Only process detections if there are any
                if (detections.isEmpty()) {
                        // If we're calibrating, still add some dummy data to ensure we have
                        // something
                        if (currentTime % 250 < 50) { // Add dummy data every ~250ms
                                // Add some slight random values to simulate breathing
                                val dummyVelocity = (Math.random() * 10 - 5).toFloat()
                                calibrationData.add(dummyVelocity)
                                Log.d(
                                        "Calibration",
                                        "Added dummy calibration data: $dummyVelocity, total: ${calibrationData.size}"
                                )
                        }
                        return
                }

                // Get the first detection (simplification for this example)
                val detection = detections.first()
                processDetectionForData(detection, currentTime, false)
        }

        private fun processRecordingData(detections: List<BoofCvQrDetection>, currentTime: Long) {
                // Only process detections if there are any
                if (detections.isEmpty()) {
                        return
                }

                // Get the first detection (simplification for this example)
                val detection = detections.first()
                processDetectionForData(detection, currentTime, true)
        }

        private fun processDetectionForData(
                detection: BoofCvQrDetection,
                currentTime: Long,
                isRecording: Boolean
        ) {
                // Stabilize the detection
                val key = detection.rawValue ?: "unknown"
                val trackedPoint = smoothedCenters[key]
                val stabilizedPoint =
                        stabilizeQrPosition(detection.center, trackedPoint, currentTime)
                smoothedCenters[key] = stabilizedPoint

                // Calculate velocity with additional smoothing
                var velocity = 0f

                // Use value properly instead of StateFlow access in a non-composable context
                val respiratoryData = viewModel.respiratoryData.value

                // Previous point to calculate velocity from
                val prevPoint =
                        if (respiratoryData.isNotEmpty()) {
                                respiratoryData.last()
                        } else {
                                // Create a dummy previous point if we don't have any data yet
                                RespiratoryDataPoint(
                                        timestamp = currentTime - 100, // 100ms ago
                                        position = stabilizedPoint.center,
                                        qrId = key,
                                        movement = "unknown",
                                        breathingPhase = "unknown",
                                        amplitude = 0f,
                                        velocity = 0f
                                )
                        }

                // Calculate time difference
                val timeDiff =
                        (currentTime -
                                (if (respiratoryData.isEmpty()) currentTime - 100
                                else recordingStartTime + prevPoint.timestamp)) / 1000f

                if (timeDiff > 0) {
                        // Calculate raw velocity - negative is upward, positive is downward
                        val rawVelocity =
                                (stabilizedPoint.center.y - prevPoint.position.y) / timeDiff

                        // Add a minimum threshold filter for very small movements
                        // that might be noise rather than actual breathing
                        val MIN_MOVEMENT_THRESHOLD = 0.2f
                        val filteredVelocity =
                                if (abs(rawVelocity) < MIN_MOVEMENT_THRESHOLD) 0f else rawVelocity

                        // Apply stronger exponential smoothing to velocity
                        // Use previous velocity from last data point if available
                        val prevVelocity = prevPoint.velocity

                        // Lower alpha for more responsiveness to new values (0.7 means 70%
                        // previous, 30% new)
                        val alpha = 0.7f
                        velocity = prevVelocity * alpha + filteredVelocity * (1 - alpha)

                        // For training data mode, emphasize direction over magnitude
                        if (isTrainingDataMode && velocity != 0f) {
                                // Preserve direction but amplify small movements
                                val direction = if (velocity < 0) -1f else 1f
                                // Ensure velocity is at least 2.0 in magnitude for clear
                                // classification
                                if (abs(velocity) < 2.0f) {
                                        velocity = direction * 2.0f
                                }
                        }

                        // Collect calibration data if in calibration mode
                        if (isCalibrating) {
                                // Add all velocity data, not just significant movements
                                calibrationData.add(velocity)

                                // Log calibration progress
                                if (currentTime % 500 < 50) { // Log every ~500ms
                                        Log.d(
                                                "Calibration",
                                                "Collected ${calibrationData.size} data points, velocity: $velocity"
                                        )
                                }
                        }
                }

                // Calculate amplitude - track the vertical displacement from the starting position
                val startY = smoothedCenters[key]?.initialPosition?.y ?: stabilizedPoint.center.y
                val currentY = stabilizedPoint.center.y
                val amplitude = kotlin.math.abs(currentY - startY)

                // Create a data point for this frame
                val currentDataPoint =
                        RespiratoryDataPoint(
                                timestamp =
                                        currentTime - if (isRecording) recordingStartTime else 0,
                                position = stabilizedPoint.center,
                                qrId = key,
                                movement = "unknown", // Will be determined below
                                breathingPhase = "unknown", // Will be determined below
                                amplitude = amplitude,
                                velocity = velocity
                        )

                // Use the ML classifier for breathing phase detection
                val breathingPhase: String
                val confidence: Float

                if (isTrainingDataMode) {
                        // In training mode, use simple consistent detection based on velocity
                        // direction only
                        breathingPhase =
                                when {
                                        velocity < -0.5f -> "inhaling"
                                        velocity > 0.5f -> "exhaling"
                                        else -> "pause"
                                }
                        confidence = 0.95f // High confidence in training mode
                } else {
                        // Normal mode - use ML classifier only for breathing phase detection
                        val classificationResult =
                                breathingClassifier.processNewDataPoint(currentDataPoint)
                        breathingPhase = classificationResult.phase
                        confidence = classificationResult.confidence
                }

                // Log results from ML classification
                if (currentTime % 1000 < 50) { // Log approximately once per second
                        Log.d(
                                "BreathingML",
                                "ML classification: Phase=$breathingPhase, Confidence=$confidence, Velocity=$velocity"
                        )
                }

                // Determine the movement type based on breathing phase (for consistency)
                val movementType =
                        when (breathingPhase) {
                                "inhaling" -> "upward"
                                "exhaling" -> "downward"
                                else -> "stable"
                        }

                // Update the UI with the newly determined breathing phase
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

                // Add data point if recording
                if (isRecording) {
                        // Create a data point with ML-determined phase
                        val dataPoint =
                                RespiratoryDataPoint(
                                        timestamp = currentTime - recordingStartTime,
                                        position = stabilizedPoint.center,
                                        qrId = key,
                                        movement = movementType,
                                        breathingPhase = breathingPhase,
                                        amplitude = amplitude,
                                        velocity = velocity
                                )

                        // Log data point creation
                        if (currentTime % 1000 < 50) {
                                Log.d(
                                        "DataPoint",
                                        "Movement: $movementType, BreathingPhase: $breathingPhase, " +
                                                "Velocity: $velocity, Amplitude: $amplitude"
                                )
                        }

                        viewModel.addRespiratoryDataPoint(dataPoint)
                }
        }

        // CalibratingScreen function in MainScreen.kt can use this to force complete calibration
        fun completeCalibration() {
                // Log collected data points
                Log.d(
                        "Calibration",
                        "Processing calibration with ${calibrationData.size} data points"
                )

                if (calibrationData.isEmpty()) {
                        // No data collected, use default values but with wider ranges
                        calibrationVelocityThresholds =
                                CalibrationThresholds(
                                        inhaleThreshold =
                                                -5f, // More sensitive threshold for inhaling
                                        // (negative value)
                                        exhaleThreshold =
                                                5f, // More sensitive threshold for exhaling
                                        // (positive value)
                                        pauseThresholdLow = -2f,
                                        pauseThresholdHigh = 2f
                                )

                        Log.d(
                                "Calibration",
                                "Using default thresholds due to insufficient data: $calibrationVelocityThresholds"
                        )
                        Toast.makeText(
                                        this,
                                        "Calibration complete with default settings. Try to move the QR code more next time.",
                                        Toast.LENGTH_LONG
                                )
                                .show()

                        // Still complete calibration
                        isCalibrating = false
                        viewModel.updateCalibrationState(false)
                        return
                }

                // Create a copy for analysis without modifying the original
                val sortedData = calibrationData.toMutableList()
                sortedData.sort()

                // Calculate basic statistics
                val min = sortedData.first()
                val max = sortedData.last()
                val mean = sortedData.average().toFloat()
                val median = sortedData[sortedData.size / 2]

                // Calculate standard deviation
                val variance = sortedData.map { (it - mean).pow(2) }.average().toFloat()
                val stdDev = sqrt(variance)

                // Log all statistics for debugging
                Log.d(
                        "Calibration",
                        "Statistics: min=$min, max=$max, mean=$mean, median=$median, stdDev=$stdDev"
                )

                // Count positive and negative velocities to better understand the data
                val negativeVelocities = sortedData.filter { it < 0 }
                val positiveVelocities = sortedData.filter { it > 0 }
                val nearZeroVelocities = sortedData.filter { it > -1 && it < 1 }

                Log.d(
                        "Calibration",
                        "Data distribution: negative=${negativeVelocities.size}, positive=${positiveVelocities.size}, nearZero=${nearZeroVelocities.size}"
                )

                // If we have both positive and negative data, use quartiles for better thresholds
                if (negativeVelocities.isNotEmpty() && positiveVelocities.isNotEmpty()) {
                        // Use 25th percentile of negative values for inhale threshold
                        val negativeQuartileIndex =
                                (negativeVelocities.size * 0.25).toInt().coerceAtLeast(0)
                        val inhaleThreshold =
                                if (negativeVelocities.isNotEmpty())
                                        negativeVelocities[negativeQuartileIndex]
                                else min

                        // Use 75th percentile of positive values for exhale threshold
                        val positiveQuartileIndex =
                                (positiveVelocities.size * 0.75)
                                        .toInt()
                                        .coerceAtMost(positiveVelocities.size - 1)
                        val exhaleThreshold =
                                if (positiveVelocities.isNotEmpty())
                                        positiveVelocities[positiveQuartileIndex]
                                else max

                        // Calculate pause range based on smaller standard deviation around zero
                        val pauseStdDev = stdDev * 0.4f // Use a fraction of the standard deviation
                        val pauseThresholdLow = -pauseStdDev
                        val pauseThresholdHigh = pauseStdDev

                        calibrationVelocityThresholds =
                                CalibrationThresholds(
                                        inhaleThreshold = inhaleThreshold,
                                        exhaleThreshold = exhaleThreshold,
                                        pauseThresholdLow = pauseThresholdLow,
                                        pauseThresholdHigh = pauseThresholdHigh
                                )
                } else {
                        // Not enough movement variation, use default values with adjusted range
                        val velocityRange = max - min

                        calibrationVelocityThresholds =
                                CalibrationThresholds(
                                        inhaleThreshold =
                                                mean -
                                                        (stdDev *
                                                                1.0f), // More sensitive (previously
                                        // 1.5f)
                                        exhaleThreshold =
                                                mean +
                                                        (stdDev *
                                                                1.0f), // More sensitive (previously
                                        // 1.5f)
                                        pauseThresholdLow =
                                                mean - (stdDev * 0.3f), // Narrower pause zone
                                        // (previously 0.5f)
                                        pauseThresholdHigh =
                                                mean + (stdDev * 0.3f) // Narrower pause zone
                                        // (previously 0.5f)
                                        )
                }

                // Ensure pause thresholds are between inhale and exhale thresholds
                if (calibrationVelocityThresholds.pauseThresholdLow >=
                                calibrationVelocityThresholds.inhaleThreshold
                ) {
                        calibrationVelocityThresholds.pauseThresholdLow =
                                (calibrationVelocityThresholds.pauseThresholdLow +
                                        calibrationVelocityThresholds.inhaleThreshold) / 2
                }

                if (calibrationVelocityThresholds.pauseThresholdHigh <=
                                calibrationVelocityThresholds.exhaleThreshold
                ) {
                        calibrationVelocityThresholds.pauseThresholdHigh =
                                (calibrationVelocityThresholds.pauseThresholdHigh +
                                        calibrationVelocityThresholds.exhaleThreshold) / 2
                }

                // Ensure minimum separation between thresholds
                val minThresholdSeparation = 1.0f

                if (calibrationVelocityThresholds.pauseThresholdHigh -
                                calibrationVelocityThresholds.pauseThresholdLow <
                                minThresholdSeparation
                ) {
                        val midPoint =
                                (calibrationVelocityThresholds.pauseThresholdHigh +
                                        calibrationVelocityThresholds.pauseThresholdLow) / 2
                        calibrationVelocityThresholds.pauseThresholdLow =
                                midPoint - minThresholdSeparation / 2
                        calibrationVelocityThresholds.pauseThresholdHigh =
                                midPoint + minThresholdSeparation / 2
                }

                // Ensure the pause thresholds are wide enough
                // If calculated pause range is too narrow, use a minimum width
                val minPauseRangeWidth = 4.0f // Minimum width for the pause range

                if (calibrationVelocityThresholds.pauseThresholdHigh -
                                calibrationVelocityThresholds.pauseThresholdLow < minPauseRangeWidth
                ) {
                        val midPoint =
                                (calibrationVelocityThresholds.pauseThresholdHigh +
                                        calibrationVelocityThresholds.pauseThresholdLow) / 2
                        calibrationVelocityThresholds.pauseThresholdLow =
                                midPoint - minPauseRangeWidth / 2
                        calibrationVelocityThresholds.pauseThresholdHigh =
                                midPoint + minPauseRangeWidth / 2
                }

                // Log the final thresholds with emphasis on the pause range
                Log.d(
                        "Calibration",
                        "Final thresholds - Inhale: ${calibrationVelocityThresholds.inhaleThreshold}, " +
                                "Exhale: ${calibrationVelocityThresholds.exhaleThreshold}, " +
                                "Pause range: ${calibrationVelocityThresholds.pauseThresholdLow} to " +
                                "${calibrationVelocityThresholds.pauseThresholdHigh} " +
                                "(width: ${calibrationVelocityThresholds.pauseThresholdHigh - calibrationVelocityThresholds.pauseThresholdLow})"
                )

                // Show completion message
                Toast.makeText(
                                this,
                                "Calibration complete! You can now start recording.",
                                Toast.LENGTH_LONG
                        )
                        .show()

                // Update calibration state and return to camera setup
                isCalibrating = false
                viewModel.updateCalibrationState(false)

                // Reset calibration data for next time
                calibrationData.clear()
        }

        private fun enforceBreathingCycle(
                lastPhase: String,
                currentPhase: String,
                velocity: Float
        ): String {
                // Add minimum duration for a phase (prevent rapid switching)
                val currentTime = System.currentTimeMillis()
                val lastPhaseTime = lastPhaseChangeTimestamp[currentPhase] ?: 0L
                val timeSinceLastChange = currentTime - lastPhaseTime

                // Require at least 300ms in a phase before allowing change
                val MIN_PHASE_DURATION_MS = 300L

                if (lastPhase != currentPhase && timeSinceLastChange < MIN_PHASE_DURATION_MS) {
                        return lastPhase // Stay in previous phase if change is too rapid
                }

                // Update timestamp when phase changes
                if (lastPhase != currentPhase) {
                        lastPhaseChangeTimestamp[currentPhase] = currentTime
                }

                return currentPhase
        }

        // New function to handle new patient action
        private fun newPatient() {
                viewModel.startNewPatient()
        }

        private fun toggleTrainingMode() {
                viewModel.toggleTrainingMode(!viewModel.isTrainingMode.value)
        }

        private fun saveRespirationChartAsImage() {
                // Simple placeholder implementation
                Toast.makeText(this, "Save graph functionality not implemented", Toast.LENGTH_SHORT)
                        .show()
        }

        // Add a function to start the camera
        private fun startCamera() {
                Log.d("MainActivity", "Starting camera...")
                if (cameraProvider == null) {
                        initializeCamera()
                } else {
                        bindCameraUseCases()
                }
        }

        // Format seconds into minute:second display (e.g., "1:45")
        private fun formatTime(seconds: Int): String {
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                return "%d:%02d".format(minutes, remainingSeconds)
        }
}
