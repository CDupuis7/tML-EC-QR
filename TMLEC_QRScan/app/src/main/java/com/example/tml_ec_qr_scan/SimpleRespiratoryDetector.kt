package com.example.tml_ec_qr_scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.geometry.Offset
import boofcv.android.ConvertBitmap
import boofcv.factory.fiducial.FactoryFiducial
import boofcv.struct.image.GrayU8
import kotlin.math.sqrt

/**
 * Simplified respiratory monitoring detector that works reliably No reflection issues - direct
 * BoofCV usage for medical QR tracking
 */
class SimpleRespiratoryDetector(private val context: Context) {
    private val qrDetector = FactoryFiducial.qrcode(null, GrayU8::class.java)

    // Medical parameters for 200px QR codes
    private val expectedQRSize = 200f
    private val minValidQRSize = 130f // 65% of expected
    private val maxValidQRSize = 270f // 135% of expected
    private val breathingMovementThreshold = 25f

    // Tracking
    private var previousDetections = mutableListOf<TrackedQR>()
    private var frameCount = 0

    private val TAG = "SimpleRespiratoryDetector"

    data class QRDetection(
            val boundingBox: RectF,
            val centerPoint: Offset,
            val content: String,
            val size: Float,
            val region: String
    )

    data class TrackedQR(
            var detection: QRDetection,
            val trackingId: Int,
            var framesVisible: Int = 1,
            var isStable: Boolean = false,
            var movement: Float = 0f
    )

    /** Main detection method - replaces your YOLO detector */
    fun detectQRCodes(bitmap: Bitmap): List<QRDetection> {
        frameCount++
        Log.d(TAG, "Frame $frameCount: Starting QR detection")

        try {
            // Convert to grayscale for BoofCV
            val grayImage = GrayU8(bitmap.width, bitmap.height)
            ConvertBitmap.bitmapToGray(bitmap, grayImage, null)

            // Detect QR codes
            qrDetector.process(grayImage)

            Log.d(TAG, "Frame $frameCount: Found ${qrDetector.detections.size} QR codes")

            // Convert to our format
            val detections = mutableListOf<QRDetection>()

            for (detection in qrDetector.detections) {
                try {
                    val bounds = detection.bounds
                    val message = detection.message

                    if (message.isNotBlank()) {
                        // Calculate bounding box from polygon
                        var minX = Float.MAX_VALUE
                        var minY = Float.MAX_VALUE
                        var maxX = Float.MIN_VALUE
                        var maxY = Float.MIN_VALUE

                        for (i in 0 until bounds.size()) {
                            val point = bounds.get(i)
                            val x = point.x.toFloat()
                            val y = point.y.toFloat()

                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }

                        val width = maxX - minX
                        val height = maxY - minY
                        val size = (width + height) / 2f
                        val centerX = (minX + maxX) / 2f
                        val centerY = (minY + maxY) / 2f

                        // Medical validation for 200px QR codes
                        if (size >= minValidQRSize &&
                                        size <= maxValidQRSize &&
                                        width > 0 &&
                                        height > 0 &&
                                        centerX > 50 &&
                                        centerY > 50 &&
                                        centerX < bitmap.width - 50 &&
                                        centerY < bitmap.height - 50
                        ) {

                            val region =
                                    getRespiratoryRegion(
                                            centerX,
                                            centerY,
                                            bitmap.width,
                                            bitmap.height
                                    )

                            detections.add(
                                    QRDetection(
                                            boundingBox = RectF(minX, minY, maxX, maxY),
                                            centerPoint = Offset(centerX, centerY),
                                            content = message,
                                            size = size,
                                            region = region
                                    )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing QR detection: ${e.message}")
                }
            }

            // Track detections for breathing analysis
            val trackedDetections = trackDetections(detections)
            val stableDetections = trackedDetections.filter { it.isStable }.map { it.detection }

            Log.d(TAG, "Frame $frameCount: ${stableDetections.size} stable QR codes")
            return stableDetections
        } catch (e: Exception) {
            Log.e(TAG, "Frame $frameCount: Detection failed: ${e.message}")
            return emptyList()
        }
    }

    private fun getRespiratoryRegion(x: Float, y: Float, width: Int, height: Int): String {
        val regionWidth = width / 3f
        val regionHeight = height / 3f

        return when {
            y < regionHeight ->
                    when {
                        x < regionWidth -> "UPPER_LEFT"
                        x < regionWidth * 2 -> "UPPER_CENTER"
                        else -> "UPPER_RIGHT"
                    }
            y < regionHeight * 2 ->
                    when {
                        x < regionWidth -> "MIDDLE_LEFT"
                        x < regionWidth * 2 -> "MIDDLE_CENTER"
                        else -> "MIDDLE_RIGHT"
                    }
            else ->
                    when {
                        x < regionWidth -> "LOWER_LEFT"
                        x < regionWidth * 2 -> "LOWER_CENTER"
                        else -> "LOWER_RIGHT"
                    }
        }
    }

    private fun trackDetections(newDetections: List<QRDetection>): List<TrackedQR> {
        val tracked = mutableListOf<TrackedQR>()
        val nextId = (previousDetections.maxOfOrNull { it.trackingId } ?: 0) + 1

        // Update existing tracks
        for (prevTrack in previousDetections) {
            var bestMatch: QRDetection? = null
            var minDistance = breathingMovementThreshold * 2

            for (newDetection in newDetections) {
                val distance =
                        sqrt(
                                (newDetection.centerPoint.x - prevTrack.detection.centerPoint.x) *
                                        (newDetection.centerPoint.x -
                                                prevTrack.detection.centerPoint.x) +
                                        (newDetection.centerPoint.y -
                                                prevTrack.detection.centerPoint.y) *
                                                (newDetection.centerPoint.y -
                                                        prevTrack.detection.centerPoint.y)
                        )

                if (distance < minDistance && newDetection.region == prevTrack.detection.region) {
                    minDistance = distance
                    bestMatch = newDetection
                }
            }

            if (bestMatch != null) {
                tracked.add(
                        TrackedQR(
                                detection = bestMatch,
                                trackingId = prevTrack.trackingId,
                                framesVisible = prevTrack.framesVisible + 1,
                                isStable = prevTrack.framesVisible >= 3,
                                movement = minDistance
                        )
                )
            }
        }

        // Add new detections
        for (detection in newDetections) {
            if (!tracked.any {
                        sqrt(
                                (it.detection.centerPoint.x - detection.centerPoint.x) *
                                        (it.detection.centerPoint.x - detection.centerPoint.x) +
                                        (it.detection.centerPoint.y - detection.centerPoint.y) *
                                                (it.detection.centerPoint.y -
                                                        detection.centerPoint.y)
                        ) < breathingMovementThreshold
                    }
            ) {
                tracked.add(TrackedQR(detection = detection, trackingId = nextId + tracked.size))
            }
        }

        previousDetections = tracked
        return tracked
    }

    /** Analyze breathing patterns from tracked QR movements */
    fun analyzeBreathing(): BreathingStatus {
        val movements = previousDetections.filter { it.isStable }.map { it.movement }
        val avgMovement = if (movements.isNotEmpty()) movements.average().toFloat() else 0f
        val maxMovement = movements.maxOrNull() ?: 0f
        val qrCount = movements.size

        val phase =
                when {
                    avgMovement > breathingMovementThreshold -> "INSPIRATION"
                    avgMovement < 5f -> "EXPIRATION_HOLD"
                    else -> "EXPIRATION"
                }

        val quality =
                when {
                    qrCount >= 6 && maxMovement < 50f -> "EXCELLENT"
                    qrCount >= 4 && maxMovement < 75f -> "GOOD"
                    qrCount >= 2 -> "MODERATE"
                    else -> "POOR"
                }

        return BreathingStatus(
                phase = phase,
                quality = quality,
                averageMovement = avgMovement,
                maxMovement = maxMovement,
                detectedQRs = qrCount,
                frameNumber = frameCount
        )
    }

    fun close() {
        Log.d(TAG, "SimpleRespiratoryDetector closed")
    }
}

data class BreathingStatus(
        val phase: String,
        val quality: String,
        val averageMovement: Float,
        val maxMovement: Float,
        val detectedQRs: Int,
        val frameNumber: Int
)
