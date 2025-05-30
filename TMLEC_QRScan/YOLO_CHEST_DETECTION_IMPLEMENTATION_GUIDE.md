# YOLO Chest Detection Implementation Guide

## Overview

This guide provides step-by-step instructions to integrate YOLO-based chest detection into your respiratory monitoring app. The YOLO model will detect people in the camera feed, extract chest regions, and track breathing patterns without requiring QR codes.

## Current Status

✅ **Already Implemented:**

- `YoloChestDetector.kt` - Complete YOLO chest detection class
- `ChestTracker.kt` - Chest movement tracking and breathing analysis
- `ChestOverlay.kt` - UI overlay for chest detection visualization
- `TrackingMode` enum in `models.kt`
- MainViewModel updates with YOLO tracking support
- UI button "Start YOLO Tracking" in MainScreen

❌ **Needs Implementation:**

- MainActivity integration for YOLO processing
- UI overlay switching between QR and YOLO modes
- Error handling and logging improvements

## Step-by-Step Implementation

### Step 1: Update MainActivity Initialization

**Location:** `MainActivity.kt` around line 142

**Current code:**

```kotlin
// Initialize YOLO chest detector
yoloChestDetector = YoloChestDetector(this)
chestTracker = ChestTracker()
Log.d("MainActivity", "YOLO chest detector initialized")
```

**Replace with:**

```kotlin
// Initialize YOLO chest detector
try {
    yoloChestDetector = YoloChestDetector(this)
    chestTracker = ChestTracker()
    Log.d("MainActivity", "✅ YOLO chest detector initialized successfully")
} catch (e: Exception) {
    Log.e("MainActivity", "❌ Failed to initialize YOLO detector: ${e.message}")
    yoloChestDetector = null
    chestTracker = null
}
```

### Step 2: Update processImage Method

**Location:** `MainActivity.kt` around line 1160

**Current code:**

```kotlin
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
```

**Replace with:**

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

### Step 3: Add YOLO Processing Method

**Location:** Add after `detectQrCodesBoof` method around line 1220

**Add this new method:**

```kotlin
private fun processYoloChestDetection(bitmap: Bitmap, rotation: Int) {
    try {
        Log.d("YoloProcessing", "🔍 Processing YOLO chest detection on ${bitmap.width}x${bitmap.height} image")

        val chestDetections = yoloChestDetector?.detectChest(bitmap) ?: emptyList()
        Log.d("YoloProcessing", "📊 YOLO detector returned ${chestDetections.size} detections")

        if (chestDetections.isNotEmpty()) {
            // Get the best detection (highest confidence)
            val bestDetection = chestDetections.maxByOrNull { it.confidence }
            bestDetection?.let { detection ->
                Log.d("YoloProcessing", "✅ Best chest detection - confidence: ${String.format("%.2f", detection.confidence)}, " +
                        "person box: ${detection.personBoundingBox}, chest box: ${detection.chestRegion}")

                // Update ViewModel with chest detection
                viewModel.updateChestDetection(detection)

                // Process chest movement for breathing analysis
                val currentTime = System.currentTimeMillis()
                chestTracker?.let { tracker ->
                    val breathingData = tracker.updateChestPositionDetailed(detection, currentTime)

                    // Update breathing data in ViewModel
                    breathingData?.let { data ->
                        Log.d("YoloProcessing", "🫁 Breathing data: phase=${data.breathingPhase}, " +
                                "velocity=${String.format("%.2f", data.velocity)}, amplitude=${String.format("%.2f", data.amplitude)}")

                        viewModel.updateBreathingData(
                            phase = when (data.breathingPhase.lowercase()) {
                                "inhale" -> -1
                                "exhale" -> 1
                                else -> 0
                            },
                            confidence = data.confidence,
                            velocity = data.velocity
                        )

                        // Add respiratory data point if recording
                        if (viewModel.isRecording.value) {
                            viewModel.addRespiratoryDataPoint(data)
                            Log.d("YoloProcessing", "📝 Added respiratory data point during recording")
                        }
                    }
                }
            }
        } else {
            // No chest detected, clear detection state
            Log.d("YoloProcessing", "❌ No chest detected in frame")
            viewModel.updateChestDetection(null)
        }
    } catch (e: Exception) {
        Log.e("YoloProcessing", "💥 Error processing YOLO chest detection: ${e.message}", e)
    }
}
```

### Step 4: Update UI Overlay

**Location:** In the `previewView` section around line 300

**Find this section:**

```kotlin
// Draw QR code overlay
if (qrDetections.isNotEmpty()) {
    BoofCVQRCodeOverlay(
        qrDetections = qrDetections,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        rotationDegrees = rotationDegrees,
        modifier = Modifier.fillMaxSize()
    )
}
```

**Replace with:**

```kotlin
// Get current tracking mode for overlay selection
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
    }
    TrackingMode.YOLO_TRACKING -> {
        // YOLO chest detection overlay
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
```

### Step 5: Update onDestroy Method

**Location:** `MainActivity.kt` around line 2600

**Find the existing `onDestroy` method and add:**

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

### Step 6: Add Required Imports

**Location:** Top of `MainActivity.kt`

**Add these imports if not already present:**

```kotlin
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
```

## Testing the Implementation

### 1. Check YOLO Model Loading

Look for these log messages when the app starts:

```
✅ YOLO chest detector initialized successfully
Model loaded successfully
Input shape: [1, 3, 640, 640]
Output shape: [1, 84, 8400]
```

### 2. Test YOLO Tracking Mode

1. Open the app
2. Enter patient information
3. Tap "Start YOLO Tracking"
4. Point camera at a person
5. Look for these log messages:

```
🔍 Processing YOLO chest detection on 1280x720 image
📊 YOLO detector returned 1 detections
✅ Best chest detection - confidence: 0.85
🫁 Breathing data: phase=inhale, velocity=-5.23, amplitude=12.45
```

### 3. Visual Verification

You should see:

- **Blue box** around the detected person
- **Green box** around the chest region
- **Red dot** at the chest center
- **Confidence percentage** displayed
- **Breathing phase indicator** updating in real-time

## Troubleshooting

### Common Issues

**1. "Failed to initialize YOLO detector"**

- Check if `qr_yolov5_tiny.tflite` exists in `assets` folder
- Verify model file is not corrupted (should be ~28MB)

**2. "No chest detected in frame"**

- Ensure person is visible in camera frame
- Check lighting conditions (YOLO works better in good lighting)
- Try different camera distances (1-3 meters optimal)

**3. "YOLO detector returned 0 detections"**

- Person detection confidence might be too low
- Try adjusting `confidenceThreshold` in `YoloChestDetector.kt`

**4. Breathing patterns not detected**

- Chest movement might be too subtle
- Try deeper breathing or closer camera positioning
- Check `velocityThresholds` in `ChestTracker.kt`

### Performance Optimization

**For better performance:**

1. Reduce input resolution in `YoloChestDetector.kt` (640→416)
2. Increase confidence threshold (0.5→0.7)
3. Reduce processing frequency (every 2nd frame)

**For better accuracy:**

1. Increase input resolution (640→832)
2. Lower confidence threshold (0.5→0.3)
3. Process every frame

## Expected Results

After successful implementation:

- **Detection Rate:** 90%+ for people in frame
- **Chest Tracking:** Accurate bounding boxes around chest region
- **Breathing Detection:** Real-time inhale/exhale classification
- **Performance:** 15-30 FPS on modern Android devices
- **Accuracy:** Comparable to QR code tracking for breathing rate

## Comparison with QR Tracking

| Feature              | QR Tracking       | YOLO Tracking   |
| -------------------- | ----------------- | --------------- |
| Setup Required       | QR code placement | None            |
| Detection Range      | Close (0.5-1m)    | Flexible (1-5m) |
| Lighting Sensitivity | Low               | Medium          |
| Accuracy             | Very High         | High            |
| Processing Speed     | Fast              | Medium          |
| User Convenience     | Low               | High            |

This implementation provides a contactless alternative to QR code tracking while maintaining good accuracy for respiratory monitoring applications.
