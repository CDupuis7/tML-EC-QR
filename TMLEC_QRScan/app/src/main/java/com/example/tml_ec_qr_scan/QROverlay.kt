package com.example.tml_ec_qr_scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** QR code overlay view for displaying detected QR codes */
class QROverlay(context: Context) : View(context) {
    var qrCornerPoints: List<BoofCvQrDetection> = emptyList()
    var boundingBox: Float = 0f

    private val qrCodePaint =
            Paint().apply {
                color = Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = 8f // Thicker lines for better visibility
                isAntiAlias = true
            }

    private val centerPaint =
            Paint().apply {
                color = Color.RED
                style = Paint.Style.FILL
                isAntiAlias = true
            }

    private val textPaint =
            Paint().apply {
                color = Color.WHITE
                textSize = 36f // Larger text for better visibility
                isAntiAlias = true
                setShadowLayer(4f, 2f, 2f, Color.BLACK) // Add shadow for better visibility
            }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        try {
            // Draw status text first to indicate the overlay is working
            val detectionCount = qrCornerPoints.size
            textPaint.textSize = 48f
            canvas.drawText(
                    "QR Detection: ${if (detectionCount > 0) "Active (${detectionCount})" else "Searching..."}",
                    50f,
                    100f,
                    textPaint
            )

            // Draw each QR code detection
            for ((index, detection) in qrCornerPoints.withIndex()) {
                // Draw the corners as a polygon
                if (detection.corners.isNotEmpty()) {
                    val path = Path()
                    val firstCorner = detection.corners[0]
                    path.moveTo(firstCorner.x, firstCorner.y)

                    for (i in 1 until detection.corners.size) {
                        val corner = detection.corners[i]
                        path.lineTo(corner.x, corner.y)
                    }

                    // Close the path back to the first point
                    path.close()
                    canvas.drawPath(path, qrCodePaint)
                }

                // Draw the center point (larger)
                canvas.drawCircle(detection.center.x, detection.center.y, 15f, centerPaint)

                // Draw QR ID number
                textPaint.textSize = 36f
                canvas.drawText(
                        "${index + 1}",
                        detection.center.x - 10f,
                        detection.center.y - 20f,
                        textPaint
                )
            }
        } catch (e: Exception) {
            // Fail gracefully if there's an error in drawing
            canvas.drawText("Error in QR detection: ${e.message}", 50f, 150f, textPaint)
        }
    }
}
