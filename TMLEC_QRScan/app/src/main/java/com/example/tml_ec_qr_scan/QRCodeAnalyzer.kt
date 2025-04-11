package com.example.tml_ec_qr_scan

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import boofcv.android.ConvertBitmap
import boofcv.factory.fiducial.ConfigQrCode
import boofcv.factory.fiducial.FactoryFiducial
import boofcv.struct.image.GrayU8
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class QRCodeAnalyzer(private val callback: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit) :
        ImageAnalysis.Analyzer {

    private val executor = Executors.newSingleThreadExecutor()
    private var lastProcessingTimeMs = 0L
    private val processingThrottleMs = 50 // Process at most every 50ms

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // Throttle processing to reduce CPU load
        if (currentTime - lastProcessingTimeMs < processingThrottleMs) {
            image.close()
            return
        }

        lastProcessingTimeMs = currentTime

        executor.execute {
            try {
                // Convert ImageProxy to Bitmap for BoofCV processing
                val bitmap = imageProxyToBitmap(image)

                if (bitmap != null) {
                    // Convert to BoofCV format
                    val input = GrayU8(bitmap.width, bitmap.height)
                    ConvertBitmap.bitmapToGray(bitmap, input, null)

                    // Detect QR codes
                    val detector = FactoryFiducial.qrcode(ConfigQrCode(), GrayU8::class.java)
                    detector.process(input)

                    // Process results
                    val detections = mutableListOf<BoofCvQrDetection>()

                    for (i in 0 until detector.detections.size) {
                        val qr = detector.detections[i]

                        // Get vertices of the QR code
                        val cornersList = mutableListOf<Offset>()
                        val quad = qr.bounds
                        for (j in 0 until quad.size()) {
                            val p = quad.get(j)
                            cornersList.add(Offset(p.x.toFloat(), p.y.toFloat()))
                        }

                        // Calculate center (average of all corners)
                        var sumX = 0f
                        var sumY = 0f
                        for (corner in cornersList) {
                            sumX += corner.x
                            sumY += corner.y
                        }
                        val centerX = sumX / cornersList.size
                        val centerY = sumY / cornersList.size
                        val center = Offset(centerX, centerY)

                        detections.add(BoofCvQrDetection(qr.message, center, cornersList))
                    }

                    // Pass detections to callback
                    callback(
                            detections,
                            bitmap.width.toFloat(),
                            bitmap.height.toFloat(),
                            image.imageInfo.rotationDegrees
                    )
                }
            } catch (e: Exception) {
                Log.e("QRCodeAnalyzer", "Error analyzing image: ${e.message}")
            } finally {
                image.close()
            }
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val buffer = imageProxy.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        // For YUV format, convert to JPEG first
        if (imageProxy.format == ImageFormat.YUV_420_888) {
            val width = imageProxy.width
            val height = imageProxy.height

            val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
            val imageBytes = out.toByteArray()

            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }

        // For other formats, try direct decoding
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    fun calculateBreathingPhase(velocity: Float, thresholds: CalibrationThresholds): String {
        return when {
            velocity < thresholds.inhaleThreshold -> "Inhaling"
            velocity > thresholds.exhaleThreshold -> "Exhaling"
            velocity in thresholds.pauseThresholdLow..thresholds.pauseThresholdHigh -> "Pause"
            else -> "Unknown"
        }
    }
}
