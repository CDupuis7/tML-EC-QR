package com.example.tml_ec_qr_scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * A simple audio waveform visualization for respiratory sounds. This is a placeholder
 * implementation that doesn't need any external libraries.
 */
@Composable
fun AudioWaveView(
        modifier: Modifier = Modifier,
        waveColor: Color = Color(0xFF4CAF50),
        isRecording: Boolean = false
) {
    // Generate placeholder waveform data
    val amplitudes =
            remember(isRecording) {
                if (isRecording) {
                    // Generate random amplitude data when recording
                    List(40) { Random.nextFloat() * 0.7f + 0.3f }
                } else {
                    // Flat line when not recording
                    List(40) { 0.5f }
                }
            }

    Canvas(modifier = modifier.fillMaxWidth().height(80.dp)) {
        val width = size.width
        val height = size.height
        val lineSpacing = width / (amplitudes.size - 1)
        val midPoint = height / 2

        // Draw the center line
        drawLine(
                color = waveColor.copy(alpha = 0.3f),
                start = Offset(0f, midPoint),
                end = Offset(width, midPoint),
                strokeWidth = 1f
        )

        // Draw the waveform
        for (i in 0 until amplitudes.size - 1) {
            val startX = i * lineSpacing
            val endX = (i + 1) * lineSpacing

            val startAmplitude = amplitudes[i] * height
            val endAmplitude = amplitudes[i + 1] * height

            val startY = midPoint - (startAmplitude - height / 2)
            val endY = midPoint - (endAmplitude - height / 2)

            drawLine(
                    color = waveColor,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
            )
        }
    }
}
