# YOLO Front Camera Implementation & UI Fixes

## Overview

This document outlines the implementation of front camera functionality for YOLO tracking and fixes for UI text issues in the respiratory monitoring app.

## ✅ Completed Changes

### 1. MainViewModel.kt Updates

**Added camera facing state variables:**

```kotlin
// Camera facing state for YOLO tracking
private val _isFrontCamera = MutableStateFlow(false)
val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()
```

**Added camera control methods:**

```kotlin
/** Toggle between front and back camera for YOLO tracking */
fun toggleCamera() {
    viewModelScope.launch {
        _isFrontCamera.value = !_isFrontCamera.value
        Log.d("MainViewModel", "Camera switched to: ${if (_isFrontCamera.value) "Front" else "Back"}")
    }
}

/** Update chest detection from YOLO detector */
fun updateChestDetection(detection: YoloChestDetector.ChestDetection?) {
    _chestDetection.value = detection
}
```

### 2. MainActivity.kt Updates

**Added YOLO components:**

```kotlin
// YOLO chest detection components
private var yoloChestDetector: YoloChestDetector? = null
private var chestTracker: ChestTracker? = null
```

**Added YOLO initialization:**

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

### 3. MainScreen.kt Updates

**Added camera toggle button:**

```kotlin
// Camera toggle button for YOLO tracking
val isFrontCamera by viewModel.isFrontCamera.collectAsState()
Button(
    onClick = { viewModel.toggleCamera() },
    modifier = Modifier.fillMaxWidth().height(56.dp),
    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B))
) {
    Text("Camera: ${if (isFrontCamera) "Front" else "Back"}")
}
```

## ❌ Remaining Tasks

### 1. Camera Selector Update in MainActivity.kt

**Current Issue:** The camera selector is hardcoded to back camera.

**Location:** Around line 816 in `bindCameraUseCases()` method

**Current Code:**

```kotlin
val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
```

**Required Fix:**

```kotlin
// Support front/back camera switching for YOLO tracking
val isFrontCamera by viewModel.isFrontCamera.collectAsState()
val cameraSelector = if (isFrontCamera) {
    CameraSelector.DEFAULT_FRONT_CAMERA
} else {
    CameraSelector.DEFAULT_BACK_CAMERA
}
```

### 2. Dynamic UI Text Update

**Current Issue:** The alignment text always says "Align QR code here" regardless of tracking mode.

**Location:** Around line 387 in MainActivity.kt

**Current Code:**

```kotlin
drawText(
    "Align QR code here",
    centerX,
    centerY - 60,
    textPaint
)
```

**Required Fix:**

```kotlin
// Get current tracking mode to show appropriate text
val trackingMode = viewModel.currentTrackingMode.value
val instructionText = when (trackingMode) {
    TrackingMode.QR_TRACKING -> "Align QR code here"
    TrackingMode.YOLO_TRACKING -> "Position camera towards chest"
}

drawText(
    instructionText,
    centerX,
    centerY - 60,
    textPaint
)
```

### 3. YOLO Processing Integration

**Required:** Add YOLO chest detection processing to the image analysis pipeline.

**Location:** In `processImage()` method around line 950

**Required Addition:**

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

### 4. ChestOverlay Integration

**Current Issue:** ChestOverlay is not being used in the UI.

**Location:** In the previewView section of MainActivity.kt

**Required Addition:**

```kotlin
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
        // QR alignment guides...
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

## 🔧 How to Test

1. **Front Camera Functionality:**

   - Start YOLO tracking
   - Tap the "Camera: Back" button to switch to "Camera: Front"
   - Verify the camera view switches to front camera
   - Test chest detection with front camera

2. **UI Text Changes:**

   - Start QR tracking → should show "Align QR code here"
   - Start YOLO tracking → should show "Position camera towards chest"

3. **ChestOverlay Functionality:**
   - Start YOLO tracking
   - Point camera at a person
   - Verify green box appears around person
   - Verify cyan box appears around chest region
   - Verify breathing phase updates in real-time

## 📱 User Experience

With these changes, users will be able to:

1. **Use front camera for self-testing:** Toggle to front camera to monitor their own breathing
2. **Clear instructions:** See appropriate text based on tracking mode
3. **Visual feedback:** See chest detection overlays when using YOLO tracking
4. **Seamless switching:** Switch between QR and YOLO tracking modes easily

## 🚨 Important Notes

- The camera switching requires rebinding the camera use cases
- Front camera may have different orientation handling
- ChestOverlay provides visual feedback for YOLO detection accuracy
- The text changes improve user experience by providing mode-specific instructions

## 🔄 Next Steps

1. Apply the remaining camera selector fix
2. Apply the dynamic text fix
3. Integrate YOLO processing pipeline
4. Add ChestOverlay to the UI
5. Test thoroughly with both front and back cameras
6. Verify breathing detection works with both tracking modes
