package com.example.tml_ec_qr_scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import java.util.concurrent.Executors

//class QRCodeTracker(
//        context: Context,
//        private val onDetectionsUpdated:
//                (List<YoloQRDetector.DetectionResult>, Float, Float, Int) -> Unit,
//        private val onTrackingData: (Offset, Float, Long) -> Unit
//) : ImageAnalysis.Analyzer {
//    private val yoloDetector = YoloQRDetector(context)
//    private val executor = Executors.newSingleThreadExecutor()
//    private var lastProcessingTimeMs = 0L
//    private val processingThrottleMs = 50 // Process at most every 50ms
//
//    private var lastDetectionCenter: Offset? = null
//    private var lastDetectionTime: Long = 0
//    private var isCalibrating = false
//    private var calibrationStartTime: Long = 0
//    private val calibrationDurationMs = 5000 // 5 seconds calibration
//    private val TAG = "QRCodeTracker"
//
//    private val velocityFilter =
//            KalmanFilter(processNoise = 0.1f, measurementNoise = 0.1f, errorCovariance = 1f)
//
//    fun startCalibration() {
//        isCalibrating = true
//        calibrationStartTime = System.currentTimeMillis()
//        lastDetectionCenter = null
//        lastDetectionTime = 0
//        Log.d(TAG, "Starting calibration phase")
//    }
//
//    fun stopCalibration() {
//        isCalibrating = false
//        Log.d(TAG, "Calibration phase completed")
//    }
//
//    override fun analyze(image: ImageProxy) {
//        val currentTime = System.currentTimeMillis()
//
//        // Throttle processing
//        if (currentTime - lastProcessingTimeMs < processingThrottleMs) {
//            image.close()
//            return
//        }
//
//        lastProcessingTimeMs = currentTime
//
//        executor.execute {
//            try {
//                val bitmap = image.toBitmap()
//                if (bitmap != null) {
//                    // Run YOLO detection
//                    val detections = yoloDetector.detect(bitmap, image.imageInfo.rotationDegrees)
//                    Log.d(TAG, "YOLO detected ${detections.size} QR codes")
//
//                    // Update UI with detections
//                    onDetectionsUpdated(
//                            detections,
//                            bitmap.width.toFloat(),
//                            bitmap.height.toFloat(),
//                            image.imageInfo.rotationDegrees
//                    )
//
//                    // Process tracking data if we have detections
//                    if (detections.isNotEmpty()) {
//                        processTrackingData(detections, currentTime)
//                    }
//
//                    // Check if calibration phase is complete
//                    if (isCalibrating && currentTime - calibrationStartTime > calibrationDurationMs
//                    ) {
//                        stopCalibration()
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error analyzing image: ${e.message}")
//                e.printStackTrace()
//            } finally {
//                image.close()
//            }
//        }
//    }
//
//    private fun processTrackingData(
//            detections: List<YoloQRDetector.DetectionResult>,
//            currentTime: Long
//    ) {
//        // Get the detection with highest confidence
//        val highestConfidenceDetection = detections.maxByOrNull { it.confidence }
//
//        if (highestConfidenceDetection != null) {
//            val center =
//                    Offset(
//                            (highestConfidenceDetection.boundingBox.left +
//                                    highestConfidenceDetection.boundingBox.right) / 2,
//                            (highestConfidenceDetection.boundingBox.top +
//                                    highestConfidenceDetection.boundingBox.bottom) / 2
//                    )
//
//            // Calculate velocity if we have previous detection
//            lastDetectionCenter?.let { lastCenter ->
//                if (lastDetectionTime > 0) {
//                    val timeDeltaSeconds = (currentTime - lastDetectionTime) / 1000f
//
//                    // Calculate vertical velocity (positive is downward movement)
//                    val rawVelocity = (center.y - lastCenter.y) / timeDeltaSeconds
//
//                    // Apply Kalman filter to smooth velocity
//                    val filteredVelocity = velocityFilter.update(rawVelocity)
//
//                    // Only report tracking data if not in calibration phase
//                    if (!isCalibrating) {
//                        onTrackingData(center, filteredVelocity, currentTime)
//                    }
//                }
//            }
//
//            lastDetectionCenter = center
//            lastDetectionTime = currentTime
//        }
//    }
//
//    private fun ImageProxy.toBitmap(): Bitmap? {
//        val buffer = planes[0].buffer
//        val data = ByteArray(buffer.remaining())
//        buffer.get(data)
//
//        return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)?.let { bitmap ->
//            // Rotate the bitmap if needed
//            if (imageInfo.rotationDegrees != 0) {
//                val matrix = Matrix()
//                matrix.postRotate(imageInfo.rotationDegrees.toFloat())
//                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
//            } else {
//                bitmap
//            }
//        }
//    }
//
//    fun release() {
//        executor.shutdown()
//        yoloDetector.close()
//    }
//}
