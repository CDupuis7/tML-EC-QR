# YOLO Chest Detection Integration Steps

## Overview

This document outlines the remaining steps to complete the integration of YOLO chest detection into your respiratory monitoring app. The integration will allow users to choose between QR code tracking and YOLO-based chest region tracking.

## Current Status

✅ **Completed:**

- YOLO model (`qr_yolov5_tiny.tflite`) is available in assets
- `YoloChestDetector.kt` class is implemented
- `ChestTracker.kt` class is implemented
- `ChestOverlay.kt` for UI visualization is implemented
- `TrackingMode` enum is defined in `models.kt`
- `prepareForYoloTracking()` method added to MainViewModel
- Tracking mode state variables added to MainViewModel
- UI button for "Start YOLO Tracking" exists in MainScreen

❌ **Remaining Steps:**

## Step 1: Add YOLO Detector Variables to MainActivity

Add these variables to the MainActivity class (after the breathing classifier):

```kotlin
// YOLO chest detection components
private var yoloChestDetector: YoloChestDetector? = null
private var chestTracker: ChestTracker? = null
```

## Step 2: Initialize YOLO Components in onCreate()

Add this initialization after the breathing classifier initialization:

```kotlin
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
```

## Step 3: Modify Image Processing to Support Both Tracking Modes

Update the `processImage()` method to handle both QR and YOLO tracking:

```kotlin
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
```

## Step 4: Add YOLO Chest Detection Processing Method

Add this new method to MainActivity:

```kotlin
private fun processYoloChestDetection(bitmap: Bitmap, rotation: Int) {
    try {
        val chestDetection = yoloChestDetector?.detectChestRegion(bitmap)

        if (chestDetection != null) {
            // Update ViewModel with chest detection
            viewModel.updateChestDetection(chestDetection)

            // Process chest movement for breathing analysis
            val currentTime = System.currentTimeMillis()
            chestTracker?.let { tracker ->
                val breathingData = tracker.processChestMovement(
                    chestDetection,
                    currentTime,
                    viewModel.isRecording.value
                )

                // Update breathing data in ViewModel
                breathingData?.let { data ->
                    viewModel.updateBreathingData(
                        phase = when (data.breathingPhase.lowercase()) {
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
```

## Step 5: Add Chest Detection Overlay to UI

Update the previewView section in MainActivity's setContent to include chest overlay:

```kotlin
previewView = {
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

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

                // QR alignment guides
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

        // Breathing phase indicator (common for both modes)
        val breathingPhase by viewModel.currentBreathingPhase.collectAsState()
        val confidence by viewModel.breathingConfidence.collectAsState()
        val currentVelocity by viewModel.currentVelocity.collectAsState()

        if (breathingPhase != "Unknown") {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
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
                        color = when (breathingPhase.lowercase()) {
                            "inhaling" -> Color(0xFF4CAF50)
                            "exhaling" -> Color(0xFF2196F3)
                            "pause" -> Color(0xFFFFC107)
                            else -> Color(0xFFFFFFFF)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (breathingPhase.lowercase() == "pause")
                            FontWeight.Bold else FontWeight.Normal
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
```

## Step 6: Update RecordingScreen to Show Current Tracking Mode

Modify the RecordingScreen to display which tracking mode is active:

```kotlin
// In RecordingScreen composable, add tracking mode indicator
val trackingMode by viewModel.currentTrackingMode.collectAsState()

Text(
    text = "Tracking Mode: ${trackingMode.name.replace("_", " ")}",
    style = MaterialTheme.typography.bodyMedium,
    color = Color.Blue,
    modifier = Modifier.padding(8.dp)
)
```

## Step 7: Add Cleanup in onDestroy()

Update the onDestroy method to clean up YOLO resources:

```kotlin
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
```

## Step 8: Test the Integration

1. **Build and run the app**
2. **Test QR tracking mode:**
   - Click "Start QR Tracking"
   - Verify QR code detection and breathing analysis works
3. **Test YOLO tracking mode:**
   - Click "Start YOLO Tracking"
   - Verify chest detection overlay appears
   - Check that breathing analysis works with chest movement
4. **Test mode switching:**
   - Switch between modes and verify UI updates correctly
   - Ensure no crashes when switching modes

## Step 9: Handle Edge Cases

Add error handling for:

- YOLO model loading failures
- No chest detected scenarios
- Camera permission issues
- Memory management for large bitmaps

## Step 10: Performance Optimization

Consider these optimizations:

- Reduce YOLO inference frequency (every 2-3 frames)
- Resize input images for faster processing
- Use background thread for YOLO processing
- Cache detection results for smoother UI

## Troubleshooting

**Common Issues:**

1. **YOLO model not found:** Ensure `qr_yolov5_tiny.tflite` is in `app/src/main/assets/`
2. **Memory issues:** Reduce image resolution before YOLO processing
3. **UI not updating:** Check StateFlow collection in Compose
4. **Detection not working:** Verify camera permissions and lighting conditions

**Debug Logging:**
Enable detailed logging by adding these tags to your logcat filter:

- `YoloChestDetector`
- `ChestTracker`
- `MainActivity`
- `YoloProcessing`

## Expected Results

After completing these steps:

- Users can choose between QR and YOLO tracking modes
- Both modes provide breathing analysis
- UI shows appropriate overlays for each mode
- Smooth transitions between tracking modes
- Robust error handling and performance
