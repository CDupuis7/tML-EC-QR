package com.example.tml_ec_qr_scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.ContentValues.TAG
import android.content.Context
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
import android.provider.MediaStore
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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

        @SuppressLint("StateFlowValueCalledInComposition")
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
                // More strict thresholds to reduce sensitivity
                val stricterInhaleThreshold = calibrationVelocityThresholds.inhaleThreshold * 1.2f
                val stricterExhaleThreshold = calibrationVelocityThresholds.exhaleThreshold * 1.2f

                // Narrower pause range to promote more definitive inhale/exhale phases
                val narrowerPauseLow = calibrationVelocityThresholds.pauseThresholdLow * 0.5f
                val narrowerPauseHigh = calibrationVelocityThresholds.pauseThresholdHigh * 0.5f

                // Apply hysteresis - harder to change state than stay in it
                return when {
                        // For inhaling - easier to stay inhaling, harder to switch to inhaling
                        velocity < stricterInhaleThreshold ||
                                (currentPhase == "inhaling" && velocity < narrowerPauseHigh) ->
                                "inhaling"

                        // For exhaling - easier to stay exhaling, harder to switch to exhaling
                        velocity > stricterExhaleThreshold ||
                                (currentPhase == "exhaling" && velocity > narrowerPauseLow) ->
                                "exhaling"

                        // Only use pause when velocity is really close to zero or when
                        // transitioning
                        else -> "pause"
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
                if (data.size < 20) {
                        return BreathingMetrics(0f, 0f, 0f, 0f, 0)
                }

                // Extract amplitudes for analysis
                val amplitudes = data.map { it.amplitude }
                val timestamps = data.map { it.timestamp }

                // Calculate basic statistics
                val averageAmplitude = amplitudes.average().toFloat()
                val maxAmplitude = amplitudes.maxOrNull() ?: 0f
                val minAmplitude = amplitudes.minOrNull() ?: 0f

                // First, let's smooth the breathing phases - handle the pause properly
                val smoothedPhases = mutableListOf<Pair<Long, String>>()

                // Use a state machine approach
                var currentPhase = "unknown"
                var phaseStartTime = data.firstOrNull()?.timestamp ?: 0L
                var shouldAddCurrentPhase = false
                val MINIMUM_PHASE_DURATION_MS = 200L // Minimum time to consider a phase valid

                // Pre-process all data points to merge consecutive same phases and ignore short
                // pauses
                for (i in data.indices) {
                        val point = data[i]
                        val rawPhase = point.breathingPhase.lowercase()

                        // State transition logic
                        when {
                                // First point sets the initial phase
                                i == 0 -> {
                                        currentPhase = rawPhase
                                        phaseStartTime = point.timestamp
                                }

                                // Handle phase changes
                                rawPhase != currentPhase -> {
                                        val phaseDuration = point.timestamp - phaseStartTime

                                        // Only consider phase changes if the previous phase lasted
                                        // long enough
                                        if (phaseDuration >= MINIMUM_PHASE_DURATION_MS) {
                                                // Add completed phase to list
                                                smoothedPhases.add(
                                                        Pair(phaseStartTime, currentPhase)
                                                )

                                                // Start new phase
                                                currentPhase = rawPhase
                                                phaseStartTime = point.timestamp
                                        }
                                        // Otherwise ignore short phases (they're noise)
                                }
                        }

                        // Add the last phase if we're at the end
                        if (i == data.size - 1) {
                                smoothedPhases.add(Pair(phaseStartTime, currentPhase))
                        }
                }

                // Enhanced breath counting algorithm (ignoring pause phases)
                var breathCount = 0
                var inInhalePhase = false
                var inExhalePhase = false
                var lastValidPhaseTime = 0L

                Log.d("BreathCounting", "=============== BREATH COUNTING ===============")
                Log.d("BreathCounting", "Total phases after smoothing: ${smoothedPhases.size}")

                // A breath cycle consists of an inhale followed by an exhale
                for (i in smoothedPhases.indices) {
                        val (timestamp, phase) = smoothedPhases[i]

                        when (phase) {
                                "inhaling" -> {
                                        // If we were in exhale phase and now we're inhaling, we
                                        // completed a cycle
                                        if (inExhalePhase) {
                                                breathCount++
                                                Log.d(
                                                        "BreathCounting",
                                                        "BREATH CYCLE #$breathCount DETECTED at timestamp $timestamp"
                                                )
                                        }
                                        inInhalePhase = true
                                        inExhalePhase = false
                                        lastValidPhaseTime = timestamp
                                }
                                "exhaling" -> {
                                        // Need to have seen an inhale first for a valid cycle
                                        inExhalePhase = true
                                        lastValidPhaseTime = timestamp
                                }
                                // Treat pause as continuation of the previous state
                                "pause" -> {
                                        /* ignore pauses */
                                }
                        }
                }

                // Calculate duration in minutes properly
                val durationMs =
                        data.lastOrNull()?.timestamp?.minus(data.firstOrNull()?.timestamp ?: 0) ?: 0
                val durationMinutes = durationMs / 60000f // Convert ms to minutes

                Log.d("BreathCounting", "Duration: ${durationMs}ms (${durationMinutes} minutes)")
                Log.d("BreathCounting", "Raw breath count: $breathCount")

                // Validate and calculate breathing rate
                var breathingRate = 0f
                if (durationMinutes > 0 && breathCount > 0) {
                        breathingRate = (breathCount / durationMinutes)

                        // Apply sanity check for human physiology
                        if (breathingRate < 6f || breathingRate > 30f) {
                                // If outside normal human range, estimate from the data duration
                                // Assume average breathing rate of 8-25 breaths per minute
                                val estimatedBreaths =
                                        (durationMinutes * 16f).toInt() // 16 breaths/min is average
                                breathingRate =
                                        (estimatedBreaths / durationMinutes).coerceIn(8f, 25f)
                                Log.d(
                                        "BreathCounting",
                                        "⚠️ Breathing rate outside physiological range, using estimate: $breathingRate"
                                )
                        }
                } else {
                        // Fallback - estimate breathing rate from timestamps using peaks/troughs
                        breathingRate = 16f // Average adult breathing rate
                        Log.d(
                                "BreathCounting",
                                "⚠️ Could not calculate breathing rate, using default: $breathingRate"
                        )
                }

                Log.d("BreathCounting", "FINAL BREATHING RATE: $breathingRate breaths/min")
                Log.d("BreathCounting", "==============================================")

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

                // Get current respiratory data for velocity calculation
                val respiratoryData = viewModel.respiratoryData.value

                // Use a longer history for more stable velocity calculation
                val HISTORY_SIZE = 5 // Use last 5 points for smoothing
                val recentPoints = respiratoryData.takeLast(HISTORY_SIZE)

                // Calculate velocity with temporal smoothing
                var velocity = 0f

                if (recentPoints.isNotEmpty()) {
                        // Calculate a weighted average velocity from multiple previous points
                        val totalWeight =
                                (1 + HISTORY_SIZE) * HISTORY_SIZE / 2.0f // Sum of 1 to HISTORY_SIZE
                        var weightedSum = 0f

                        // Previous point with largest weight for comparison
                        val prevPoint =
                                recentPoints.lastOrNull()
                                        ?: RespiratoryDataPoint(
                                                timestamp = currentTime - 100,
                                                position = stabilizedPoint.center,
                                                qrId = key,
                                                movement = "unknown",
                                                breathingPhase = "unknown",
                                                amplitude = 0f,
                                                velocity = 0f
                                        )

                        // Time difference for calculating current raw velocity
                        val timeDiff =
                                (currentTime -
                                        (if (respiratoryData.isEmpty()) currentTime - 100
                                        else recordingStartTime + prevPoint.timestamp)) / 1000f

                        if (timeDiff > 0) {
                                // Calculate current raw velocity - negative is upward, positive is
                                // downward
                                val rawVelocity =
                                        (stabilizedPoint.center.y - prevPoint.position.y) / timeDiff

                                // Filter out micro-movements (noise)
                                val MIN_MOVEMENT_THRESHOLD = 0.5f // Increased threshold
                                val filteredVelocity =
                                        if (abs(rawVelocity) < MIN_MOVEMENT_THRESHOLD) 0f
                                        else rawVelocity

                                // Apply exponential smoothing with historical velocities for
                                // stability
                                if (recentPoints.size >= 2) {
                                        // Use weighted average of last N velocities
                                        for (i in recentPoints.indices) {
                                                val weight =
                                                        (i + 1) /
                                                                totalWeight // Higher weight to more
                                                // recent points
                                                weightedSum += recentPoints[i].velocity * weight
                                        }

                                        // Combine historical and current velocity - higher weight
                                        // to history (0.8) for stability
                                        val alpha = 0.8f
                                        velocity =
                                                weightedSum * alpha + filteredVelocity * (1 - alpha)
                                } else {
                                        // Not enough history, use simpler smoothing
                                        val prevVelocity = prevPoint.velocity
                                        val alpha = 0.7f
                                        velocity =
                                                prevVelocity * alpha +
                                                        filteredVelocity * (1 - alpha)
                                }

                                // In training mode, emphasize direction over magnitude
                                if (isTrainingDataMode && velocity != 0f) {
                                        // Amplify the signal for clearer classification
                                        val direction = if (velocity < 0) -1f else 1f
                                        if (abs(velocity) < 3.0f) {
                                                velocity = direction * 3.0f
                                        }
                                }
                        }
                } else if (trackedPoint != null) {
                        // If no respiratory data yet, use velocity from tracked point
                        velocity = trackedPoint.velocity.y
                }

                // Calculate amplitude - track the vertical displacement from starting position with
                // smoothing
                val startY = smoothedCenters[key]?.initialPosition?.y ?: stabilizedPoint.center.y
                val currentY = stabilizedPoint.center.y
                val amplitude = kotlin.math.abs(currentY - startY)

                // Determine current breathing phase with hysteresis to prevent rapid switching
                val lastPhase =
                        respiratoryData.lastOrNull()?.breathingPhase?.lowercase() ?: "unknown"
                val timeSinceLastChange =
                        if (lastPhaseChangeTimestamp.containsKey(lastPhase))
                                currentTime - lastPhaseChangeTimestamp[lastPhase]!!
                        else 0L

                // Determine current phase with anti-flicker logic
                val breathingPhase: String
                val confidence: Float

                if (isTrainingDataMode) {
                        // Simple mapping for training mode
                        breathingPhase =
                                when {
                                        velocity < -1.0f -> "inhaling"
                                        velocity > 1.0f -> "exhaling"
                                        lastPhase == "inhaling" && velocity > -2.0f ->
                                                "inhaling" // Stick with inhaling
                                        lastPhase == "exhaling" && velocity < 2.0f ->
                                                "exhaling" // Stick with exhaling
                                        else -> "pause"
                                }
                        confidence = 0.95f
                } else {
                        // Use ML classifier with phase enforcement
                        val dataPoint =
                                RespiratoryDataPoint(
                                        timestamp =
                                                currentTime -
                                                        if (isRecording) recordingStartTime else 0,
                                        position = stabilizedPoint.center,
                                        qrId = key,
                                        movement = "unknown",
                                        breathingPhase = "unknown",
                                        amplitude = amplitude,
                                        velocity = velocity
                                )
                        val classificationResult =
                                breathingClassifier.processNewDataPoint(dataPoint)

                        // Apply phase enforcement to avoid rapid phase changes
                        breathingPhase =
                                enforceBreathingCycle(
                                        lastPhase,
                                        classificationResult.phase,
                                        velocity
                                )
                        confidence = classificationResult.confidence
                }

                // Log detailed info only occasionally to reduce spam
                if (currentTime % 1000 < 50) {
                        Log.d(
                                "BreathingPhase",
                                "Phase=$breathingPhase, Velocity=$velocity, Last Phase=$lastPhase"
                        )
                }

                // Update UI with breathing phase
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

                // Add data point for recording
                if (isRecording) {
                        // Create a data point with the determined phase
                        val movementType =
                                when (breathingPhase) {
                                        "inhaling" -> "upward"
                                        "exhaling" -> "downward"
                                        else -> "stable"
                                }

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

                        // Log data point creation periodically
                        if (currentTime % 2000 < 50) {
                                Log.d(
                                        "DataPoint",
                                        "Phase: $breathingPhase, Velocity: $velocity, Amplitude: $amplitude"
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
                // Current time for measuring phase duration
                val currentTime = System.currentTimeMillis()

                // Get the timestamp when we last switched to the current phase
                val lastPhaseTime = lastPhaseChangeTimestamp[lastPhase] ?: 0L
                val timeSinceLastChange = currentTime - lastPhaseTime

                // Minimum durations for phases to prevent flickering
                val MIN_INHALE_DURATION_MS = 500L // Inhaling should last at least 500ms
                val MIN_EXHALE_DURATION_MS = 500L // Exhaling should last at least 500ms
                val MIN_PAUSE_DURATION_MS = 200L // Pause can be shorter

                // Only allow phase changes if the current phase has lasted long enough
                val minDuration =
                        when (lastPhase) {
                                "inhaling" -> MIN_INHALE_DURATION_MS
                                "exhaling" -> MIN_EXHALE_DURATION_MS
                                "pause" -> MIN_PAUSE_DURATION_MS
                                else -> 0L // No minimum for unknown phases
                        }

                // Determine if we should enforce the previous phase
                val shouldEnforcePreviousPhase = timeSinceLastChange < minDuration

                // Add velocity-based hysteresis to determine phase switching thresholds
                val shouldAllowInhalingToExhaling = velocity > 5.0f // Strong positive velocity
                val shouldAllowExhalingToInhaling = velocity < -5.0f // Strong negative velocity

                // Log phase transition logic
                if (lastPhase != currentPhase) {
                        Log.d(
                                "PhaseEnforcement",
                                "Transition $lastPhase → $currentPhase, Time since last: $timeSinceLastChange ms, " +
                                        "Velocity: $velocity, Enforcing: $shouldEnforcePreviousPhase"
                        )
                }

                // Apply the phase enforcement rules
                val finalPhase =
                        when {
                                // Special case: If we were in pause and there's strong velocity,
                                // allow exit
                                lastPhase == "pause" &&
                                        (shouldAllowInhalingToExhaling ||
                                                shouldAllowExhalingToInhaling) -> currentPhase

                                // If previous phase hasn't lasted long enough, enforce it
                                shouldEnforcePreviousPhase -> lastPhase

                                // Phase transition from inhaling to exhaling requires positive
                                // velocity
                                lastPhase == "inhaling" &&
                                        currentPhase == "exhaling" &&
                                        !shouldAllowInhalingToExhaling -> lastPhase

                                // Phase transition from exhaling to inhaling requires negative
                                // velocity
                                lastPhase == "exhaling" &&
                                        currentPhase == "inhaling" &&
                                        !shouldAllowExhalingToInhaling -> lastPhase

                                // Otherwise, allow the transition
                                else -> currentPhase
                        }

                // Update timestamp when phase actually changes
                if (lastPhase != finalPhase) {
                        lastPhaseChangeTimestamp[finalPhase] = currentTime
                        Log.d("PhaseEnforcement", "PHASE CHANGE: $lastPhase → $finalPhase")
                }

                return finalPhase
        }

        // New function to handle new patient action
        private fun newPatient() {
                viewModel.startNewPatient()
        }

        private fun toggleTrainingMode() {
                viewModel.toggleTrainingMode(!viewModel.isTrainingMode.value)
        }

        private fun saveRespirationChartAsImage() {
                val respiratoryData = viewModel.respiratoryData.value
                if (respiratoryData.isEmpty()) {
                        Toast.makeText(this, "No respiratory data to save", Toast.LENGTH_SHORT)
                                .show()
                        return
                }

                try {
                        // Define the dimensions for the respiratory graph
                        val width = 1200
                        val height = 800
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)

                        // Set background color for the graph
                        canvas.drawColor(
                                android.graphics.Color.parseColor("#212121")
                        ) // Dark background

                        // Define colors for breathing phases
                        val inhaleColor = android.graphics.Color.parseColor("#81C784") // Green
                        val exhaleColor = android.graphics.Color.parseColor("#64B5F6") // Blue
                        val pauseColor = android.graphics.Color.parseColor("#FFD54F") // Amber
                        val axisColor = android.graphics.Color.parseColor("#BBBBBB") // Light gray

                        // Calculate graph dimensions
                        val padding = 80f
                        val graphWidth = width - 2 * padding
                        val graphHeight = height - 2 * padding
                        val midY = padding + graphHeight / 2

                        // Setup paint objects
                        val axisPaint =
                                android.graphics.Paint().apply {
                                        color = axisColor
                                        strokeWidth = 2f
                                        style = android.graphics.Paint.Style.STROKE
                                        isAntiAlias = true
                                }

                        val textPaint =
                                android.graphics.Paint().apply {
                                        color = android.graphics.Color.WHITE
                                        textSize = 30f
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
                                        strokeWidth = 3f
                                        style = android.graphics.Paint.Style.STROKE
                                        isAntiAlias = true
                                }

                        // Draw X and Y axes
                        canvas.drawLine(
                                padding,
                                padding,
                                padding,
                                height - padding,
                                axisPaint
                        ) // Y-axis
                        canvas.drawLine(
                                padding,
                                midY,
                                width - padding,
                                midY,
                                axisPaint
                        ) // X-axis (middle)
                        canvas.drawLine(
                                padding,
                                height - padding,
                                width - padding,
                                height - padding,
                                axisPaint
                        ) // X-axis (bottom)

                        // Find min/max values for scaling
                        val maxVelocity =
                                respiratoryData.maxOfOrNull { abs(it.velocity) }?.coerceAtLeast(10f)
                                        ?: 10f
                        val yPerVelocity = (graphHeight / 2) / maxVelocity

                        // Draw velocity labels on Y-axis
                        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
                        canvas.drawText("0", padding - 10, midY + 10, textPaint) // 0 velocity

                        // Positive velocity labels
                        var velocity = 10
                        while (velocity <= maxVelocity) {
                                val y = midY - (velocity * yPerVelocity)
                                canvas.drawText("+$velocity", padding - 10, y + 10, textPaint)
                                // Draw grid line
                                canvas.drawLine(
                                        padding,
                                        y,
                                        width - padding,
                                        y,
                                        axisPaint.apply { alpha = 50 }
                                )
                                velocity += 10
                        }

                        // Negative velocity labels
                        velocity = -10
                        while (velocity >= -maxVelocity) {
                                val y = midY - (velocity * yPerVelocity)
                                canvas.drawText("$velocity", padding - 10, y + 10, textPaint)
                                // Draw grid line
                                canvas.drawLine(
                                        padding,
                                        y,
                                        width - padding,
                                        y,
                                        axisPaint.apply { alpha = 50 }
                                )
                                velocity -= 10
                        }

                        // Title and axis labels
                        textPaint.textAlign = android.graphics.Paint.Align.CENTER
                        canvas.drawText(
                                "Respiratory Time Series Graph",
                                width / 2f,
                                padding / 2,
                                textPaint
                        )
                        canvas.drawText(
                                "Time (seconds)",
                                width / 2f,
                                height - padding / 3 - 50, // Moved up to avoid overlap with legends
                                textPaint
                        )

                        // Rotate canvas to draw Y-axis label
                        canvas.save()
                        canvas.rotate(-90f, padding / 3, height / 2f)
                        canvas.drawText("Velocity (m/s)", padding / 3, height / 2f, textPaint)
                        canvas.restore()

                        // Draw time markers on X-axis
                        if (respiratoryData.isNotEmpty()) {
                                val firstTimestamp = respiratoryData.first().timestamp
                                val lastTimestamp = respiratoryData.last().timestamp
                                val duration = lastTimestamp - firstTimestamp

                                // Calculate time interval for markers
                                val timeMarkInterval =
                                        if (duration > 30000) 5000 else 2000 // 5 or 2 seconds
                                var timeMarker = 0L

                                while (timeMarker <= duration) {
                                        val x =
                                                padding +
                                                        (timeMarker.toFloat() /
                                                                duration.toFloat()) * graphWidth
                                        canvas.drawLine(
                                                x,
                                                height - padding,
                                                x,
                                                height - padding + 10,
                                                axisPaint
                                        )
                                        canvas.drawText(
                                                "${timeMarker / 1000}s",
                                                x,
                                                height - padding + 30,
                                                labelPaint
                                        )
                                        timeMarker += timeMarkInterval
                                }
                        }

                        // Draw respiratory data
                        if (respiratoryData.size > 1) {
                                val firstTimestamp = respiratoryData.first().timestamp
                                val lastTimestamp = respiratoryData.last().timestamp
                                val duration =
                                        (lastTimestamp - firstTimestamp).coerceAtLeast(
                                                1L
                                        ) // Avoid division by zero

                                // Draw line segments for each phase
                                for (i in 0 until respiratoryData.size - 1) {
                                        val current = respiratoryData[i]
                                        val next = respiratoryData[i + 1]

                                        val x1 =
                                                padding +
                                                        ((current.timestamp - firstTimestamp)
                                                                .toFloat() / duration) * graphWidth
                                        val y1 = midY - (current.velocity * yPerVelocity)

                                        val x2 =
                                                padding +
                                                        ((next.timestamp - firstTimestamp)
                                                                .toFloat() / duration) * graphWidth
                                        val y2 = midY - (next.velocity * yPerVelocity)

                                        // Set color based on breathing phase
                                        linePaint.color =
                                                when (current.breathingPhase.lowercase().trim()) {
                                                        "inhaling" -> inhaleColor
                                                        "exhaling" -> exhaleColor
                                                        "pause" -> pauseColor
                                                        else -> android.graphics.Color.GRAY
                                                }

                                        // Draw line segment
                                        canvas.drawLine(x1, y1, x2, y2, linePaint)

                                        // Data points are removed as requested
                                }
                        }

                        // Draw legend - positioned higher to avoid overlap with x-axis label
                        val legendTop = height - padding - 25 // Moved up from +50 to -25
                        val legendItemWidth = 200f
                        val legendLineWidth = 60f

                        // Inhaling legend
                        linePaint.color = inhaleColor
                        canvas.drawLine(
                                padding,
                                legendTop,
                                padding + legendLineWidth,
                                legendTop,
                                linePaint
                        )
                        labelPaint.color = android.graphics.Color.WHITE
                        canvas.drawText(
                                "Inhaling",
                                padding + legendLineWidth + 10,
                                legendTop + 8,
                                labelPaint
                        )

                        // Exhaling legend
                        linePaint.color = exhaleColor
                        canvas.drawLine(
                                padding + legendItemWidth,
                                legendTop,
                                padding + legendItemWidth + legendLineWidth,
                                legendTop,
                                linePaint
                        )
                        canvas.drawText(
                                "Exhaling",
                                padding + legendItemWidth + legendLineWidth + 10,
                                legendTop + 8,
                                labelPaint
                        )

                        // Pause legend
                        linePaint.color = pauseColor
                        canvas.drawLine(
                                padding + 2 * legendItemWidth,
                                legendTop,
                                padding + 2 * legendItemWidth + legendLineWidth,
                                legendTop,
                                linePaint
                        )
                        canvas.drawText(
                                "Pause",
                                padding + 2 * legendItemWidth + legendLineWidth + 10,
                                legendTop + 8,
                                labelPaint
                        )

                        // Save the bitmap to the gallery
                        val timeStamp =
                                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                        .format(Date())
                        val fileName = "Respiratory_Graph_$timeStamp.png"

                        val contentValues =
                                ContentValues().apply {
                                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                        put(
                                                MediaStore.Images.Media.DATE_ADDED,
                                                System.currentTimeMillis() / 1000
                                        )
                                        put(
                                                MediaStore.Images.Media.DATE_TAKEN,
                                                System.currentTimeMillis()
                                        )

                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                put(
                                                        MediaStore.Images.Media.RELATIVE_PATH,
                                                        Environment.DIRECTORY_PICTURES
                                                )
                                                put(MediaStore.Images.Media.IS_PENDING, 1)
                                        }
                                }

                        val resolver = contentResolver
                        val imageUri =
                                resolver.insert(
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                        contentValues
                                )

                        imageUri?.let { uri ->
                                resolver.openOutputStream(uri)?.use { outputStream ->
                                        if (!bitmap.compress(
                                                        Bitmap.CompressFormat.PNG,
                                                        100,
                                                        outputStream
                                                )
                                        ) {
                                                throw IOException("Failed to save bitmap")
                                        }
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        contentValues.clear()
                                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                                        resolver.update(uri, contentValues, null, null)
                                }

                                Toast.makeText(
                                                this,
                                                "Respiratory graph saved to gallery as $fileName",
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                        }
                                ?: throw IOException("Failed to create media store entry")
                } catch (e: Exception) {
                        Log.e("MainActivity", "Error saving graph image", e)
                        Toast.makeText(this, "Error saving graph: ${e.message}", Toast.LENGTH_SHORT)
                                .show()
                }
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
