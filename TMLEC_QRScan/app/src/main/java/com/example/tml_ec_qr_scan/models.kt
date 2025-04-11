package com.example.tml_ec_qr_scan

import androidx.compose.ui.geometry.Offset
import kotlin.math.pow
import kotlin.math.sqrt

data class BoofCvQrDetection(val rawValue: String?, val center: Offset, val corners: List<Offset>)

data class TrackedPoint(
        val center: Offset,
        val lastUpdateTime: Long,
        val velocity: Offset,
        val isLocked: Boolean = false,
        val initialPosition: Offset? = null
)

data class RespiratoryDataPoint(
        val timestamp: Long,
        val position: Offset,
        val qrId: String,
        val movement: String = "unknown",
        val breathingPhase: String = "unknown",
        val amplitude: Float = 0f,
        val velocity: Float = 0f
)

data class BreathingMetrics(
        val breathingRate: Float, // breaths per minute
        val averageAmplitude: Float, // average chest movement
        val maxAmplitude: Float, // maximum chest movement
        val minAmplitude: Float, // minimum chest movement
        val breathCount: Int // total number of breaths
)

data class PatientMetadata(
        val id: String,
        val age: Int,
        val gender: String,
        val healthStatus: String,
        val additionalNotes: String = ""
)

/**
 * Data class for storing calibrated velocity thresholds for breathing phase detection. These
 * thresholds are adapted to the patient's specific breathing pattern during calibration.
 */
data class CalibrationThresholds(
        val inhaleThreshold: Float, // Upper threshold for inhaling (negative value)
        val exhaleThreshold: Float, // Lower threshold for exhaling (positive value)
        var pauseThresholdLow: Float, // Lower bound for pause detection
        var pauseThresholdHigh: Float // Upper bound for pause detection
)

fun Offset.distanceTo(other: Offset): Float {
        return sqrt((this.x - other.x).pow(2) + (this.y - other.y).pow(2))
}

// Not needed since we're using kotlin.math.pow directly
// fun Float.pow(n: Int): Float = kotlin.math.pow(this.toDouble(), n.toDouble()).toFloat()
