/*
 * YOLO Integration Code Snippets
 * Apply these changes to complete the YOLO chest detection integration
 */

// ============================================================================
// 1. ADD TO MainActivity.kt CLASS VARIABLES (after breathing classifier)
// ============================================================================

// YOLO chest detection components
private var yoloChestDetector: YoloChestDetector? = null
private var chestTracker: ChestTracker? = null

// ============================================================================
// 2. ADD TO MainActivity.onCreate() (after breathing classifier init)
// ============================================================================

// Initialize YOLO chest detector
try {
    yoloChestDetector = YoloChestDetector(this)
    chestTracker = ChestTracker()
    Log.d("MainActivity", "YOLO chest detector initialized")
} catch (e: Exception) {
    Log.e("MainActivity", "Failed to initialize YOLO detector: ${e.message}")
    yoloChestDetector = null
    chestTracker = null
}

// ============================================================================
// 3. REPLACE processImage() METHOD IN MainActivity.kt
// ============================================================================

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processImage(
        imageProxy: ImageProxy,
        callback: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit
) {
    try {
        val bitmap = convertImageProxyToBitmap(imageProxy) ?: return
        val rotation = imageProxy.imageInfo.rotationDegrees

        // Get current tracking mode from ViewModel
        val trackingMode = viewModel.currentTrackingMode.value

        when (trackingMode) {
            TrackingMode.QR_TRACKING -> {
                // Existing QR detection logic
                val detections = detectQrCodesBoof(bitmap)
                callback(detections, bitmap.width.toFloat(), bitmap.height.toFloat(), rotation)
            }
            TrackingMode.YOLO_TRACKING -> {
                // YOLO chest detection
                processYoloChestDetection(bitmap, rotation)
                // Still call callback with empty QR detections for UI consistency
                callback(emptyList(), bitmap.width.toFloat(), bitmap.height.toFloat(), rotation)
            }
        }
    } catch (e: Exception) {
        Log.e("CameraProcess", "Error processing image", e)
    } finally {
        imageProxy.close()
    }
}

// ============================================================================
// 4. ADD NEW METHOD TO MainActivity.kt
// ============================================================================

private fun processYoloChestDetection(bitmap: Bitmap, rotation: Int) {
    try {
        val chestDetection = yoloChestDetector?.detectChestRegion(bitmap)

        if (chestDetection != null) {
            // Update ViewModel with chest detection
            viewModel.updateChestDetection(chestDetection)

            // Process chest movement for breathing analysis
            val currentTime = System.currentTimeMillis()
            chestTracker?.let { tracker ->
                val breathingData =
                        tracker.processChestMovement(
                                chestDetection,
                                currentTime,
                                viewModel.isRecording.value
                        )

                // Update breathing data in ViewModel
                breathingData?.let { data ->
                    viewModel.updateBreathingData(
                            phase =
                                    when (data.breathingPhase.lowercase()) {
                                        "inhaling" -> -1
                                        "exhaling" -> 1
                                        else -> 0
                                    },
                            confidence = data.confidence,
                            velocity = data.velocity
                    )

                    // Add respiratory data point if recording
                    if (viewModel.isRecording.value) {
                        viewModel.addRespiratoryDataPoint(data)
                    }
                }
            }
        } else {
            // No chest detected, clear detection state
            viewModel.updateChestDetection(null)
        }
    } catch (e: Exception) {
        Log.e("YoloProcessing", "Error processing YOLO chest detection: ${e.message}")
    }
}

// ============================================================================
// 5. UPDATE onDestroy() METHOD IN MainActivity.kt
// ============================================================================

override fun onDestroy() {
    super.onDestroy()
    releaseCamera()
    cameraExecutor.shutdown()

    // Clean up breathing classifier
    breathingClassifier.close()

    // Clean up YOLO detector
    yoloChestDetector?.close()
    yoloChestDetector = null
    chestTracker = null
}

// ============================================================================
// 6. UPDATE previewView SECTION IN MainActivity.setContent
// ============================================================================

previewView = {
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Get current tracking mode
        val trackingMode by viewModel.currentTrackingMode.collectAsState()

        when (trackingMode) {
            TrackingMode.QR_TRACKING -> {
                // Existing QR code overlay
                if (qrDetections.isNotEmpty()) {
                    BoofCVQRCodeOverlay(
                            qrDetections = qrDetections,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            rotationDegrees = rotationDegrees,
                            modifier = Modifier.fillMaxSize()
                    )
                }

                // QR alignment guides (existing Canvas code)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val centerX = size.width / 2
                    val centerY = size.height / 2

                    // Draw crosshair and center dot
                    drawLine(
                            color = Color.Red.copy(alpha = 0.7f),
                            start = Offset(centerX - 40, centerY),
                            end = Offset(centerX + 40, centerY),
                            strokeWidth = 2f
                    )
                    drawLine(
                            color = Color.Red.copy(alpha = 0.7f),
                            start = Offset(centerX, centerY - 40),
                            end = Offset(centerX, centerY + 40),
                            strokeWidth = 2f
                    )
                    drawCircle(
                            color = Color.Red.copy(alpha = 0.9f),
                            radius = 12f,
                            center = Offset(centerX, centerY)
                    )
                }
            }
            TrackingMode.YOLO_TRACKING -> {
                // YOLO chest detection overlay
                val chestDetection by viewModel.chestDetection.collectAsState()
                chestDetection?.let { detection ->
                    ChestOverlay(
                            chestDetection = detection,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Enhanced breathing phase indicator (common for both modes)
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
                                        "inhaling" -> Color(0xFF4CAF50)
                                        "exhaling" -> Color(0xFF2196F3)
                                        "pause" -> Color(0xFFFFC107)
                                        else -> Color(0xFFFFFFFF)
                                    },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight =
                                    if (breathingPhase.lowercase() == "pause") FontWeight.Bold
                                    else FontWeight.Normal
                    )

                    Text(
                            text = "Velocity: ${String.format("%.2f", currentVelocity)}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                            text = "Mode: ${trackingMode.name.replace("_", " ")}",
                            color = Color.Yellow,
                            style = MaterialTheme.typography.bodySmall
                    )

                    if (viewModel.isRecording.collectAsState().value) {
                        Text(
                                text = "Recording Active",
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 7. OPTIONAL: ADD TO RecordingScreen.kt FOR TRACKING MODE INDICATOR
// ============================================================================

// Add this inside RecordingScreen composable
val trackingMode by viewModel.currentTrackingMode.collectAsState()

Text(
        text = "Tracking Mode: ${trackingMode.name.replace("_", " ")}",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.Blue,
        modifier = Modifier.padding(8.dp)
)

// ============================================================================
// INTEGRATION CHECKLIST:
// ============================================================================
/*
□ Add YOLO detector variables to MainActivity class
□ Initialize YOLO components in onCreate()
□ Update processImage() method to handle both tracking modes
□ Add processYoloChestDetection() method
□ Update UI to show chest overlay for YOLO mode
□ Add cleanup in onDestroy()
□ Test both QR and YOLO tracking modes
□ Verify smooth mode switching
□ Check breathing analysis works in both modes
□ Ensure proper error handling
*/
