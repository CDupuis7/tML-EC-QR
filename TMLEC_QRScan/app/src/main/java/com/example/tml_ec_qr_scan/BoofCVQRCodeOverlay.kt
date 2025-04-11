package com.example.tml_ec_qr_scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.min

/**
 * Composable that renders an overlay showing detected QR codes and their information
 *
 * @param qrDetections List of QR code detections from BoofCV
 * @param imageWidth Width of the analyzed image
 * @param imageHeight Height of the analyzed image
 * @param rotationDegrees Rotation of the image in degrees
 * @param modifier Modifier for the Canvas
 */
@Composable
fun BoofCVQRCodeOverlay(
        qrDetections: List<BoofCvQrDetection>,
        imageWidth: Float,
        imageHeight: Float,
        rotationDegrees: Int,
        modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Scale factors to map image coordinates to canvas coordinates
        val scaleX = canvasWidth / imageWidth
        val scaleY = canvasHeight / imageHeight

        // Use the smaller scale to ensure the image fits within the canvas
        val scale = min(scaleX, scaleY)

        // Center the image in the canvas
        val offsetX = (canvasWidth - imageWidth * scale) / 2
        val offsetY = (canvasHeight - imageHeight * scale) / 2

        // Draw each QR code detection
        qrDetections.forEach { detection ->
            // Transform coordinates based on image rotation and scaling
            val center =
                    Offset(
                            x = detection.center.x * scale + offsetX,
                            y = detection.center.y * scale + offsetY
                    )

            // Draw bounding box by connecting the corner points
            val corners =
                    detection.corners.map { corner ->
                        Offset(x = corner.x * scale + offsetX, y = corner.y * scale + offsetY)
                    }

            // Draw the QR code corners connected in a quadrilateral
            for (i in corners.indices) {
                val start = corners[i]
                val end = corners[(i + 1) % corners.size]

                drawLine(color = Color.Green, start = start, end = end, strokeWidth = 3f)
            }

            // Draw center point
            drawCircle(color = Color.Red, radius = 8f, center = center)

            // Draw value text if available
            detection.rawValue?.let { value ->
                drawContext.canvas.nativeCanvas.apply {
                    val textPaint =
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.GREEN
                                textSize = 32f
                                isFakeBoldText = true
                                setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                            }

                    // Draw QR value text above the detection
                    drawText(value, center.x, center.y - 30f, textPaint)
                }
            }

            // Draw outline around the QR code
            drawCircle(
                    color = Color.Yellow,
                    radius = 60f,
                    center = center,
                    style = Stroke(width = 2f)
            )
        }
    }
}
