package com.example.tml_ec_qr_scan

import java.time.Instant

/** Data class to hold health monitoring values from Health Connect */
data class HealthData(
        val heartRate: Double? = null, // BPM (beats per minute)
        val spO2: Double? = null, // Oxygen saturation percentage
        val timestamp: Instant = Instant.now(),
        val isRealData: Boolean = false // Track if data comes from real Health Connect sources
) {
    /** Check if we have valid health data */
    fun isValid(): Boolean = heartRate != null || spO2 != null

    /** Format heart rate for display */
    fun getHeartRateDisplay(): String = heartRate?.let { "${it.toInt()} bpm" } ?: "-- bpm"

    /** Format SpO₂ for display */
    fun getSpO2Display(): String = spO2?.let { "${it.toInt()}%" } ?: "--%"

    /** Get health status interpretation */
    fun getHealthStatus(): String {
        val hrStatus =
                heartRate?.let { hr ->
                    when {
                        hr < 60 -> "Low HR"
                        hr > 100 -> "High HR"
                        else -> "Normal HR"
                    }
                }

        val spO2Status =
                spO2?.let { spo2 ->
                    when {
                        spo2 < 95 -> "Low O₂"
                        else -> "Normal O₂"
                    }
                }

        return listOfNotNull(hrStatus, spO2Status).joinToString(", ").ifEmpty { "No Data" }
    }

    /** Get data source indicator for debugging */
    fun getDataSourceDisplay(): String = if (isRealData) "📱 Real" else "🔄 Sim"
}
