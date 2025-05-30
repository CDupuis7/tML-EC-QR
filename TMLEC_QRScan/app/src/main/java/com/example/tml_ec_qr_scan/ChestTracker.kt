package com.example.tml_ec_qr_scan

import android.util.Log
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tracks chest movement over time and calculates breathing parameters Similar to QR tracking but
 * uses YOLO-detected chest regions
 */
class ChestTracker {
    private val chestHistory = mutableListOf<ChestTrackingPoint>()
    private var lastChestPosition: Offset? = null
    private var lastUpdateTime = 0L
    private var breathingPhase = "unknown"
    private var lastPhaseChangeTime = 0L

    // Smoothing parameters - reduced for better responsiveness
    private val maxHistorySize = 20 // Keep last 20 detections for smoothing
    private val smoothingWindow = 3 // Use last 3 points for velocity calculation (more responsive)

    // Breathing detection thresholds (will be calibrated) - Made more sensitive for YOLO tracking
    private var velocityThresholds =
            CalibrationThresholds(
                    inhaleThreshold = -2f, // Even more sensitive for YOLO tracking
                    exhaleThreshold = 2f, // Even more sensitive for YOLO tracking
                    pauseThresholdLow = -1f, // Narrower pause zone
                    pauseThresholdHigh = 1f // Narrower pause zone
            )

    /** Simple overload that returns just the breathing phase (for integration compatibility) */
    fun updateChestPosition(chestDetection: YoloChestDetector.ChestDetection): String {
        val dataPoint = updateChestPositionDetailed(chestDetection)
        return dataPoint?.breathingPhase ?: "unknown"
    }

    /**
     * Updates chest tracking with new detection Returns respiratory data point if tracking is
     * successful
     */
    fun updateChestPositionDetailed(
            chestDetection: YoloChestDetector.ChestDetection,
            timestamp: Long = System.currentTimeMillis()
    ): RespiratoryDataPoint? {
        val currentPosition = chestDetection.centerPoint

        // Apply smoothing to reduce noise
        val smoothedPosition = applySmoothingFilter(currentPosition, timestamp)

        // Calculate velocity
        val velocity = calculateVelocity(smoothedPosition, timestamp)

        // Update breathing phase based on vertical movement
        val newBreathingPhase = determineBreathingPhase(velocity.y)
        if (newBreathingPhase != breathingPhase) {
            Log.d(
                    "ChestTracker",
                    "🔄 Breathing phase changed: $breathingPhase → $newBreathingPhase (velocity: ${String.format("%.2f", velocity.y)})"
            )
            breathingPhase = newBreathingPhase
            lastPhaseChangeTime = timestamp
        }

        // Debug: Log velocity and thresholds periodically
        if (timestamp % 1000 < 100) { // Log every ~1 second
            Log.d(
                    "ChestTracker",
                    "🔍 Velocity: ${String.format("%.2f", velocity.y)}, " +
                            "Thresholds: inhale<${velocityThresholds.inhaleThreshold}, " +
                            "exhale>${velocityThresholds.exhaleThreshold}, " +
                            "pause[${velocityThresholds.pauseThresholdLow}, ${velocityThresholds.pauseThresholdHigh}], " +
                            "Current phase: $breathingPhase"
            )
        }

        // Calculate amplitude (distance from initial position)
        val amplitude = calculateAmplitude(smoothedPosition)

        // Store tracking point
        val trackingPoint =
                ChestTrackingPoint(
                        position = smoothedPosition,
                        timestamp = timestamp,
                        velocity = velocity,
                        confidence = chestDetection.confidence
                )

        chestHistory.add(trackingPoint)
        if (chestHistory.size > maxHistorySize) {
            chestHistory.removeAt(0)
        }

        lastChestPosition = smoothedPosition
        lastUpdateTime = timestamp

        Log.d(
                "ChestTracker",
                "Chest tracking: pos=(${smoothedPosition.x.toInt()}, ${smoothedPosition.y.toInt()}), " +
                        "velocity=${String.format("%.2f", velocity.y)}, phase=$breathingPhase, " +
                        "amplitude=${String.format("%.2f", amplitude)}"
        )

        return RespiratoryDataPoint(
                timestamp = timestamp,
                position = smoothedPosition,
                qrId = "CHEST_YOLO", // Identifier for YOLO-based tracking
                movement = if (abs(velocity.y) > 2f) "moving" else "stationary",
                breathingPhase = breathingPhase,
                amplitude = amplitude,
                velocity = velocity.y
        )
    }

    private fun applySmoothingFilter(currentPosition: Offset, timestamp: Long): Offset {
        if (chestHistory.isEmpty()) {
            return currentPosition
        }

        // Simple moving average smoothing
        val recentPoints = chestHistory.takeLast(smoothingWindow)
        val avgX =
                (recentPoints.sumOf { it.position.x.toDouble() } + currentPosition.x) /
                        (recentPoints.size + 1)
        val avgY =
                (recentPoints.sumOf { it.position.y.toDouble() } + currentPosition.y) /
                        (recentPoints.size + 1)

        return Offset(avgX.toFloat(), avgY.toFloat())
    }

    private fun calculateVelocity(currentPosition: Offset, timestamp: Long): Offset {
        val lastPosition = lastChestPosition
        if (lastPosition == null || lastUpdateTime == 0L) {
            return Offset(0f, 0f)
        }

        val deltaTime = (timestamp - lastUpdateTime) / 1000f // Convert to seconds
        if (deltaTime <= 0) {
            return Offset(0f, 0f)
        }

        val deltaX = currentPosition.x - lastPosition.x
        val deltaY = currentPosition.y - lastPosition.y

        // Scale velocity for better sensitivity - chest movements are typically smaller than QR
        // movements
        val scaleFactor = 10f // Amplify small chest movements
        return Offset((deltaX / deltaTime) * scaleFactor, (deltaY / deltaTime) * scaleFactor)
    }

    private fun determineBreathingPhase(verticalVelocity: Float): String {
        return when {
            verticalVelocity < velocityThresholds.inhaleThreshold -> "inhaling"
            verticalVelocity > velocityThresholds.exhaleThreshold -> "exhaling"
            verticalVelocity >= velocityThresholds.pauseThresholdLow &&
                    verticalVelocity <= velocityThresholds.pauseThresholdHigh -> "pause"
            else -> "pause" // Default to pause instead of transition
        }
    }

    private fun calculateAmplitude(currentPosition: Offset): Float {
        if (chestHistory.isEmpty()) return 0f

        // Calculate amplitude as distance from the average position
        val avgPosition = calculateAveragePosition()
        return sqrt(
                (currentPosition.x - avgPosition.x) * (currentPosition.x - avgPosition.x) +
                        (currentPosition.y - avgPosition.y) * (currentPosition.y - avgPosition.y)
        )
    }

    private fun calculateAveragePosition(): Offset {
        if (chestHistory.isEmpty()) return Offset(0f, 0f)

        val avgX = chestHistory.map { it.position.x }.average().toFloat()
        val avgY = chestHistory.map { it.position.y }.average().toFloat()
        return Offset(avgX, avgY)
    }

    /** Calibrates velocity thresholds based on collected data */
    fun calibrateThresholds(calibrationData: List<Float>) {
        if (calibrationData.size < 10) {
            Log.w("ChestTracker", "Insufficient calibration data: ${calibrationData.size} points")
            return
        }

        val sortedVelocities = calibrationData.sorted()
        val q25 = sortedVelocities[(sortedVelocities.size * 0.25).toInt()]
        val q75 = sortedVelocities[(sortedVelocities.size * 0.75).toInt()]
        val median = sortedVelocities[sortedVelocities.size / 2]

        // Set thresholds based on velocity distribution
        velocityThresholds =
                CalibrationThresholds(
                        inhaleThreshold = q25 * 1.2f, // 20% beyond 25th percentile
                        exhaleThreshold = q75 * 1.2f, // 20% beyond 75th percentile
                        pauseThresholdLow = median * 0.3f,
                        pauseThresholdHigh = median * 0.7f
                )

        Log.d("ChestTracker", "Calibrated thresholds: $velocityThresholds")
    }

    /** Gets current breathing metrics */
    fun getBreathingMetrics(): BreathingMetrics {
        if (chestHistory.size < 10) {
            return BreathingMetrics(0f, 0f, 0f, 0f, 0)
        }

        val amplitudes = chestHistory.map { calculateAmplitude(it.position) }
        val velocities = chestHistory.map { abs(it.velocity.y) }

        // Count breathing cycles (simplified)
        val breathCount = countBreathingCycles()

        // Calculate breathing rate (breaths per minute)
        val timeSpanMinutes =
                (chestHistory.last().timestamp - chestHistory.first().timestamp) / 60000f
        val breathingRate = if (timeSpanMinutes > 0) breathCount / timeSpanMinutes else 0f

        return BreathingMetrics(
                breathingRate = breathingRate,
                averageAmplitude = amplitudes.average().toFloat(),
                maxAmplitude = amplitudes.maxOrNull() ?: 0f,
                minAmplitude = amplitudes.minOrNull() ?: 0f,
                breathCount = breathCount
        )
    }

    private fun countBreathingCycles(): Int {
        // Simple cycle counting based on phase changes
        var cycles = 0
        var lastPhase = ""
        var inCycle = false

        for (point in chestHistory) {
            val phase = determineBreathingPhase(point.velocity.y)
            if (phase != lastPhase) {
                if (phase == "inhale" && !inCycle) {
                    inCycle = true
                } else if (phase == "exhale" && inCycle) {
                    cycles++
                    inCycle = false
                }
                lastPhase = phase
            }
        }

        return cycles
    }

    /** Gets current breathing phase */
    fun getCurrentBreathingPhase(): String = breathingPhase

    /** Gets current velocity */
    fun getCurrentVelocity(): Float {
        return chestHistory.lastOrNull()?.velocity?.y ?: 0f
    }

    /** Resets tracking history */
    fun reset() {
        chestHistory.clear()
        lastChestPosition = null
        lastUpdateTime = 0L
        breathingPhase = "unknown"
        lastPhaseChangeTime = 0L
        Log.d("ChestTracker", "Chest tracker reset")
    }

    /** Data class for chest tracking points */
    data class ChestTrackingPoint(
            val position: Offset,
            val timestamp: Long,
            val velocity: Offset,
            val confidence: Float
    )
}
