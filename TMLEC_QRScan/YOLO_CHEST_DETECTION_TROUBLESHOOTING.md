# YOLO Chest Detection Troubleshooting Guide

## Current Issues Analysis

Based on your description, the main problems are:

1. **Chest area not being detected** - YOLO model is not finding people/chest regions
2. **Breathing patterns not being determined** - No inhale/exhale detection
3. **Missing log messages** - Can't verify if YOLO model loaded correctly

## Root Cause Analysis

### Issue 1: YOLO Model Not Loading Properly

**Problem:** The current MainActivity initialization doesn't have proper error handling.

**Current Code:**

```kotlin
yoloChestDetector = YoloChestDetector(this)
chestTracker = ChestTracker()
Log.d("MainActivity", "YOLO chest detector initialized")
```

**Solution:** Update with proper error handling and logging:

```kotlin
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

### Issue 2: processImage Method Not Updated

**Problem:** The current `processImage` method only handles QR detection, not YOLO tracking.

**Current Code:**

```kotlin
private fun processImage(imageProxy: ImageProxy, callback: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit) {
    try {
        val bitmap = convertImageProxyToBitmap(imageProxy) ?: return
        val rotation = imageProxy.imageInfo.rotationDegrees
        val detections = detectQrCodesBoof(bitmap)  // Only QR detection
        callback(detections, bitmap.width.toFloat(), bitmap.height.toFloat(), rotation)
    } catch (e: Exception) {
        Log.e("CameraProcess", "Error processing image", e)
    } finally {
        imageProxy.close()
    }
}
```

**Solution:** Add tracking mode switching:

```kotlin
private fun processImage(imageProxy: ImageProxy, callback: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit) {
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

### Issue 3: Missing YOLO Processing Method

**Problem:** No method to handle YOLO chest detection and breathing analysis.

**Solution:** Add this method after `detectQrCodesBoof`:

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
                Log.d("YoloProcessing", "✅ Best chest detection - confidence: ${detection.confidence}, " +
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
                                "velocity=${data.velocity}, amplitude=${data.amplitude}")

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

### Issue 4: UI Overlay Not Switching

**Problem:** The UI still shows QR overlay even in YOLO mode.

**Current Code:**

```kotlin
// Draw QR code overlay
if (qrDetections.isNotEmpty()) {
    BoofCVQRCodeOverlay(...)
}
```

**Solution:** Add mode-based overlay switching:

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

## Step-by-Step Debugging Process

### Step 1: Verify Model Loading

1. Run the app and check logcat for these messages:

```
🔄 Starting YOLO model loading...
📁 Attempting to load yolov8s_coco.tflite
📦 Model file loaded: YOLOv8s
✅ Model loaded successfully: YOLOv8s
📊 Input shape: [1, 3, 640, 640]
📊 Output shape: [1, 84, 8400]
```

2. If you see errors, check:
   - Is `yolov8s_coco.tflite` in the `assets` folder?
   - Is the file corrupted? (Should be ~43MB)

### Step 2: Test YOLO Tracking Mode

1. Open the app
2. Enter patient information
3. Tap "Start YOLO Tracking"
4. Point camera at a person (full body visible)
5. Check logcat for:

```
🔍 Processing YOLO chest detection on 1280x720 image
📊 YOLO detector returned 1 detections
✅ Best chest detection - confidence: 0.85
```

### Step 3: Verify Breathing Analysis

1. If chest detection works, look for:

```
🫁 Breathing data: phase=inhale, velocity=-5.23, amplitude=12.45
📝 Added respiratory data point during recording
```

2. Visual verification:
   - Blue box around person
   - Green box around chest
   - Red dot at chest center
   - Breathing phase indicator updating

## Common Issues and Solutions

### Issue: "No chest detected in frame"

**Possible Causes:**

1. **Person not visible** - Ensure full body or upper body is in frame
2. **Poor lighting** - YOLO works better in good lighting conditions
3. **Distance too close/far** - Optimal range is 1-3 meters
4. **Low confidence threshold** - Person detection confidence below 0.5

**Solutions:**

1. Improve lighting conditions
2. Adjust camera distance
3. Lower confidence threshold in `YoloChestDetector.kt`:

```kotlin
private val confidenceThreshold = 0.3f  // Reduced from 0.5f
```

### Issue: "Breathing patterns not detected"

**Possible Causes:**

1. **Chest movement too subtle** - Natural breathing might be too small to detect
2. **Velocity thresholds too high** - Default thresholds might not match user's breathing
3. **Insufficient tracking history** - Need several frames to establish pattern

**Solutions:**

1. Ask user to breathe more deeply during calibration
2. Adjust velocity thresholds in `ChestTracker.kt`:

```kotlin
private var velocityThresholds = CalibrationThresholds(
    inhaleThreshold = -4f,    // Reduced from -8f
    exhaleThreshold = 4f,     // Reduced from 8f
    pauseThresholdLow = -2f,  // Reduced from -3f
    pauseThresholdHigh = 2f   // Reduced from 3f
)
```

### Issue: "Model loading failed"

**Possible Causes:**

1. **Missing model file** - `yolov8s_coco.tflite` not in assets
2. **Insufficient memory** - Model too large for device
3. **TensorFlow Lite version mismatch**

**Solutions:**

1. Verify model file exists and is correct size (43MB)
2. Try smaller model or reduce input resolution
3. Update TensorFlow Lite dependency

## Performance Optimization

### For Better Detection Rate:

1. **Lower confidence threshold**: 0.5 → 0.3
2. **Increase input resolution**: 640 → 832
3. **Process every frame** instead of skipping

### For Better Performance:

1. **Higher confidence threshold**: 0.5 → 0.7
2. **Reduce input resolution**: 640 → 416
3. **Process every 2nd frame**

### For Better Breathing Sensitivity:

1. **Reduce velocity thresholds** by 50%
2. **Increase smoothing window**: 5 → 10 frames
3. **Use front camera** for closer detection

## Expected Log Output (Working System)

When everything works correctly, you should see this sequence:

```
✅ YOLO chest detector initialized successfully
📦 Model file loaded: YOLOv8s
✅ Model loaded successfully: YOLOv8s
🔍 Processing YOLO chest detection on 1280x720 image
📊 YOLO detector returned 1 detections
✅ Best chest detection - confidence: 0.87
🫁 Breathing data: phase=inhale, velocity=-6.45, amplitude=15.23
📝 Added respiratory data point during recording
```

This indicates:

- ✅ Model loaded successfully
- ✅ Person detected with high confidence
- ✅ Chest region extracted
- ✅ Breathing pattern analyzed
- ✅ Data recorded for analysis

Follow this guide step by step to identify and fix the specific issues in your implementation.
