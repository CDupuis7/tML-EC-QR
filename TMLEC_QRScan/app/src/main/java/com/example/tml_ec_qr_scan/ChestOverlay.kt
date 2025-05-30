package com.example.tml_ec_qr_scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas

/** Overlay for visualizing YOLO chest detection and tracking */
@Composable
fun ChestOverlay(
        chestDetection: YoloChestDetector.ChestDetection?,
        imageWidth: Float,
        imageHeight: Float,
        rotationDegrees: Int,
        modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val viewWidth = size.width
        val viewHeight = size.height

        // Handle rotation
        val (width, height) =
                if (rotationDegrees % 180 == 90) {
                    Pair(imageHeight, imageWidth)
                } else {
                    Pair(imageWidth, imageHeight)
                }

        // Calculate proper scaling factors
        val scaleX = viewWidth / width
        val scaleY = viewHeight / height
        val scale = kotlin.math.min(scaleX, scaleY)

        // Calculate centering offset
        val offsetX = (viewWidth - width * scale) / 2f
        val offsetY = (viewHeight - height * scale) / 2f

        chestDetection?.let { detection ->
            // Transform coordinates based on rotation
            val transformedChestBox =
                    transformBoundingBox(
                            detection.boundingBox,
                            rotationDegrees,
                            imageWidth,
                            imageHeight,
                            scale,
                            offsetX,
                            offsetY
                    )

            val transformedPersonBox =
                    transformBoundingBox(
                            detection.personBoundingBox,
                            rotationDegrees,
                            imageWidth,
                            imageHeight,
                            scale,
                            offsetX,
                            offsetY
                    )

            val transformedCenter =
                    transformPoint(
                            detection.centerPoint,
                            rotationDegrees,
                            imageWidth,
                            imageHeight,
                            scale,
                            offsetX,
                            offsetY
                    )

            // Draw person bounding box (light blue)
            drawRect(
                    color = Color.Blue.copy(alpha = 0.3f),
                    topLeft = Offset(transformedPersonBox.left, transformedPersonBox.top),
                    size =
                            androidx.compose.ui.geometry.Size(
                                    transformedPersonBox.width(),
                                    transformedPersonBox.height()
                            ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )

            // Draw chest region bounding box (green)
            drawRect(
                    color = Color.Green.copy(alpha = 0.7f),
                    topLeft = Offset(transformedChestBox.left, transformedChestBox.top),
                    size =
                            androidx.compose.ui.geometry.Size(
                                    transformedChestBox.width(),
                                    transformedChestBox.height()
                            ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
            )

            // Draw chest center point (red)
            drawCircle(
                    color = Color.Red.copy(alpha = 0.9f),
                    radius = 15f,
                    center = transformedCenter
            )

            // Draw confidence and labels
            drawContext.canvas.nativeCanvas.apply {
                val textPaint =
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 32f
                            textAlign = android.graphics.Paint.Align.LEFT
                            setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                        }

                // Person detection label
                drawText(
                        "Person: ${String.format("%.2f", detection.confidence)}",
                        transformedPersonBox.left,
                        transformedPersonBox.top - 10,
                        textPaint
                )

                // Chest region label
                drawText(
                        "Chest Region",
                        transformedChestBox.left,
                        transformedChestBox.top - 10,
                        textPaint.apply { color = android.graphics.Color.GREEN }
                )

                // Center coordinates
                drawText(
                        "Center: (${transformedCenter.x.toInt()}, ${transformedCenter.y.toInt()})",
                        transformedCenter.x - 100,
                        transformedCenter.y + 40,
                        textPaint.apply {
                            color = android.graphics.Color.RED
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                )
            }
        }
    }
}

/** Transform bounding box coordinates based on rotation */
private fun transformBoundingBox(
        boundingBox: android.graphics.RectF,
        rotationDegrees: Int,
        imageWidth: Float,
        imageHeight: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float
): android.graphics.RectF {
    val corners =
            listOf(
                    Offset(boundingBox.left, boundingBox.top),
                    Offset(boundingBox.right, boundingBox.top),
                    Offset(boundingBox.right, boundingBox.bottom),
                    Offset(boundingBox.left, boundingBox.bottom)
            )

    val transformedCorners =
            corners.map { corner ->
                transformPoint(
                        corner,
                        rotationDegrees,
                        imageWidth,
                        imageHeight,
                        scale,
                        offsetX,
                        offsetY
                )
            }

    val minX = transformedCorners.minOf { it.x }
    val maxX = transformedCorners.maxOf { it.x }
    val minY = transformedCorners.minOf { it.y }
    val maxY = transformedCorners.maxOf { it.y }

    return android.graphics.RectF(minX, minY, maxX, maxY)
}

/** Transform point coordinates based on rotation */
private fun transformPoint(
        point: Offset,
        rotationDegrees: Int,
        imageWidth: Float,
        imageHeight: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float
): Offset {
    val rotatedPoint =
            when (rotationDegrees) {
                90 -> Offset(point.y, imageWidth - point.x)
                180 -> Offset(imageWidth - point.x, imageHeight - point.y)
                270 -> Offset(imageHeight - point.y, point.x)
                else -> point
            }

    return Offset(x = offsetX + rotatedPoint.x * scale, y = offsetY + rotatedPoint.y * scale)
}
