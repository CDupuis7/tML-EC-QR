package com.example.tml_ec_qr_scan

import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Manager class for Health Connect operations to read heart rate and SpO₂ data Currently using
 * simulated data for testing - replace with real Health Connect API when available
 */
class HealthConnectManager(private val context: Context) {

    companion object {
        private const val TAG = "HealthConnectManager"
        private const val DATA_UPDATE_INTERVAL_MS = 5000L // 5 seconds
    }

    private val sessionStartTime = Instant.now()
    private val sessionHealthData = mutableListOf<HealthData>()

    /** Check if Health Connect is available on this device */
    suspend fun isAvailable(): Boolean {
        // Simulate Health Connect availability - in real implementation this would check actual
        // Health Connect
        return true
    }

    /** Check if we have the required permissions */
    suspend fun hasPermissions(): Boolean {
        // Simulate permissions granted - in real implementation this would check actual permissions
        return true
    }

    /** Request Health Connect permissions */
    suspend fun requestPermissions(): Set<String> {
        // Simulate granted permissions
        return setOf("READ_HEART_RATE", "READ_OXYGEN_SATURATION")
    }

    /** Generate realistic simulated heart rate (60-100 bpm normal range) */
    private fun generateSimulatedHeartRate(): Double {
        // Generate realistic heart rate with some variation
        val baseHeartRate = 72.0
        val variation = Random.nextDouble(-12.0, 18.0) // ±12-18 bpm variation
        return (baseHeartRate + variation).coerceIn(55.0, 105.0)
    }

    /** Generate realistic simulated SpO₂ (95-100% normal range) */
    private fun generateSimulatedSpO2(): Double {
        // Generate realistic SpO₂ with minimal variation
        val baseSpO2 = 98.0
        val variation = Random.nextDouble(-2.0, 2.0) // ±2% variation
        return (baseSpO2 + variation).coerceIn(94.0, 100.0)
    }

    /** Get current health data (simulated heart rate and SpO₂) */
    suspend fun getCurrentHealthData(): HealthData {
        return try {
            val heartRate = generateSimulatedHeartRate()
            val spO2 = generateSimulatedSpO2()

            val healthData =
                    HealthData(heartRate = heartRate, spO2 = spO2, timestamp = Instant.now())

            // Store for session averaging
            sessionHealthData.add(healthData)

            Log.d(TAG, "Generated health data: HR=${heartRate.toInt()}, SpO₂=${spO2.toInt()}%")
            healthData
        } catch (e: Exception) {
            Log.e(TAG, "Error generating health data", e)
            HealthData()
        }
    }

    /**
     * Create a continuous flow of health data updates Updates every 5 seconds with simulated data
     */
    fun getHealthDataFlow(): Flow<HealthData> = flow {
        while (true) {
            try {
                val healthData = getCurrentHealthData()
                emit(healthData)
                delay(DATA_UPDATE_INTERVAL_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Error in health data flow", e)
                emit(HealthData()) // Emit empty data on error
                delay(DATA_UPDATE_INTERVAL_MS)
            }
        }
    }

    /** Get health data for a specific time period (for session averaging) */
    suspend fun getHealthDataForPeriod(startTime: Instant, endTime: Instant): List<HealthData> {
        return try {
            // Filter session data by time period
            sessionHealthData.filter { healthData ->
                !healthData.timestamp.isBefore(startTime) && !healthData.timestamp.isAfter(endTime)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading health data for period", e)
            emptyList()
        }
    }

    /** Calculate average health data from a list of readings */
    fun calculateAverageHealthData(healthDataList: List<HealthData>): HealthData {
        if (healthDataList.isEmpty()) return HealthData()

        val validHeartRates = healthDataList.mapNotNull { it.heartRate }
        val validSpO2Values = healthDataList.mapNotNull { it.spO2 }

        val avgHeartRate =
                if (validHeartRates.isNotEmpty()) {
                    validHeartRates.average()
                } else null

        val avgSpO2 =
                if (validSpO2Values.isNotEmpty()) {
                    validSpO2Values.average()
                } else null

        return HealthData(heartRate = avgHeartRate, spO2 = avgSpO2, timestamp = Instant.now())
    }

    /** Get session average health data */
    fun getSessionAverageHealthData(): HealthData {
        return calculateAverageHealthData(sessionHealthData)
    }

    /** Clear session data (call when starting a new session) */
    fun clearSessionData() {
        sessionHealthData.clear()
        Log.d(TAG, "Session health data cleared")
    }

    /** Get total session duration */
    fun getSessionDuration(): Long {
        return sessionStartTime.until(Instant.now(), ChronoUnit.SECONDS)
    }
}
