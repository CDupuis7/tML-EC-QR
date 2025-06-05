package com.example.tml_ec_qr_scan

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Manager class for Health Connect operations to read heart rate and SpO₂ data from Samsung Galaxy
 * Watch 4 via Samsung Health and Health Connect API
 */
class HealthConnectManager(private val context: Context) {

    companion object {
        private const val TAG = "HealthConnectManager"
        private const val DATA_UPDATE_INTERVAL_MS = 5000L // 5 seconds

        // Samsung Health optimized settings
        private const val HEART_RATE_LOOKBACK_MINUTES =
                15L // Samsung Health updates more frequently
        private const val SPO2_LOOKBACK_HOURS = 12L // Samsung SpO₂ is primarily sleep-based
        private const val SPO2_EXTENDED_LOOKBACK_HOURS = 48L // Extended search for sleep SpO₂
    }

    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }
    private val sessionStartTime = Instant.now()
    private val sessionHealthData = mutableListOf<HealthData>()

    // Required permissions for heart rate and oxygen saturation
    private val permissions =
            setOf(
                    HealthPermission.getReadPermission(HeartRateRecord::class),
                    HealthPermission.getReadPermission(OxygenSaturationRecord::class)
            )

    /** Check if Health Connect is available on this device */
    suspend fun isAvailable(): Boolean {
        return try {
            when (HealthConnectClient.getSdkStatus(context)) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    Log.d(TAG, "Health Connect is available for Samsung Health integration")
                    true
                }
                HealthConnectClient.SDK_UNAVAILABLE -> {
                    Log.w(TAG, "Health Connect is not available on this device")
                    false
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    Log.w(TAG, "Health Connect is available but requires provider update")
                    false
                }
                else -> {
                    Log.w(TAG, "Unknown Health Connect SDK status")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Health Connect availability", e)
            false
        }
    }

    /** Check if we have the required permissions */
    suspend fun hasPermissions(): Boolean {
        return try {
            val grantedPermissions =
                    healthConnectClient.permissionController.getGrantedPermissions()
            val hasAllPermissions = permissions.all { it in grantedPermissions }

            // Enhanced logging for Samsung Health debugging
            Log.d(TAG, "=== SAMSUNG HEALTH CONNECT PERMISSIONS DEBUG ===")
            Log.d(TAG, "Required permissions: $permissions")
            Log.d(TAG, "Granted permissions: $grantedPermissions")
            Log.d(TAG, "Has all required permissions: $hasAllPermissions")

            // Check individual permissions
            permissions.forEach { permission ->
                val isGranted = permission in grantedPermissions
                Log.d(
                        TAG,
                        "Permission $permission: ${if (isGranted) "✅ GRANTED" else "❌ NOT GRANTED"}"
                )
            }

            hasAllPermissions
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }

    /** Request Health Connect permissions */
    suspend fun requestPermissions(): Set<String> {
        return try {
            val permissionController = healthConnectClient.permissionController
            // This would typically be called from an Activity context with permission launcher
            Log.d(TAG, "Permissions need to be requested from Activity context")
            emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permissions", e)
            emptySet()
        }
    }

    /** Get the most recent heart rate reading - optimized for Samsung Health */
    private suspend fun getLatestHeartRate(): Double? {
        return try {
            val endTime = Instant.now()
            val startTime = endTime.minus(HEART_RATE_LOOKBACK_MINUTES, ChronoUnit.MINUTES)

            Log.d(TAG, "=== SAMSUNG HEALTH - HEART RATE DATA SEARCH ===")
            Log.d(
                    TAG,
                    "Searching from: $startTime to: $endTime (${HEART_RATE_LOOKBACK_MINUTES}min window)"
            )

            val request =
                    ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )

            val response = healthConnectClient.readRecords(request)
            Log.d(TAG, "Found ${response.records.size} heart rate records from Samsung Health")

            // Samsung Health specific logging
            response.records.forEach { record ->
                Log.d(
                        TAG,
                        "Samsung HR Record: startTime=${record.startTime}, endTime=${record.endTime}, samples=${record.samples.size}"
                )
                if (record.samples.isNotEmpty()) {
                    val firstSample = record.samples.first()
                    val lastSample = record.samples.last()
                    Log.d(
                            TAG,
                            "  Sample range: ${firstSample.time}(${firstSample.beatsPerMinute}) to ${lastSample.time}(${lastSample.beatsPerMinute})"
                    )
                }
            }

            // Get the most recent sample from all records
            val allSamples = response.records.flatMap { it.samples }
            val latestSample = allSamples.maxByOrNull { it.time }

            latestSample?.let { sample ->
                val heartRate = sample.beatsPerMinute.toDouble()
                Log.d(
                        TAG,
                        "✅ Latest Samsung Health heart rate: $heartRate bpm (from ${sample.time})"
                )
                heartRate
            }
                    ?: run {
                        Log.d(
                                TAG,
                                "❌ No Samsung Health heart rate records found in the specified time range"
                        )
                        null
                    }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading heart rate from Samsung Health via Health Connect", e)
            null
        }
    }

    /** Get the most recent SpO₂ reading - optimized for Samsung Health sleep tracking */
    private suspend fun getLatestSpO2(): Double? {
        return try {
            val endTime = Instant.now()
            val startTime = endTime.minus(SPO2_LOOKBACK_HOURS, ChronoUnit.HOURS)

            Log.d(TAG, "=== SAMSUNG HEALTH - SPO2 DATA SEARCH ===")
            Log.d(TAG, "Searching from: $startTime to: $endTime (${SPO2_LOOKBACK_HOURS}h window)")

            val request =
                    ReadRecordsRequest(
                            recordType = OxygenSaturationRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )

            val response = healthConnectClient.readRecords(request)
            Log.d(TAG, "Found ${response.records.size} SpO₂ records from Samsung Health")

            if (response.records.isEmpty()) {
                // Try extended search for sleep-based SpO₂ data
                Log.d(
                        TAG,
                        "No recent SpO₂ data, extending search to ${SPO2_EXTENDED_LOOKBACK_HOURS}h for sleep data"
                )
                return getExtendedSpO2Search()
            }

            // Samsung Health specific logging
            response.records.forEach { record ->
                Log.d(
                        TAG,
                        "Samsung SpO₂ Record: time=${record.time}, percentage=${record.percentage.value}%"
                )
            }

            val latestRecord = response.records.maxByOrNull { it.time }

            latestRecord?.let { record ->
                val spO2 = record.percentage.value.toDouble()
                Log.d(TAG, "✅ Latest Samsung Health SpO₂: $spO2% (from ${record.time})")
                spO2
            }
                    ?: run {
                        Log.d(TAG, "❌ No Samsung Health SpO₂ records found")
                        null
                    }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SpO₂ from Samsung Health via Health Connect", e)
            null
        }
    }

    /** Extended SpO₂ search for sleep-based Samsung Health data */
    private suspend fun getExtendedSpO2Search(): Double? {
        return try {
            val endTime = Instant.now()
            val startTime = endTime.minus(SPO2_EXTENDED_LOOKBACK_HOURS, ChronoUnit.HOURS)

            Log.d(TAG, "=== EXTENDED SAMSUNG SPO2 SEARCH (SLEEP DATA) ===")
            Log.d(TAG, "Extended search from: $startTime to: $endTime")

            val request =
                    ReadRecordsRequest(
                            recordType = OxygenSaturationRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )

            val response = healthConnectClient.readRecords(request)
            Log.d(TAG, "Extended search found ${response.records.size} SpO₂ records")

            val latestRecord = response.records.maxByOrNull { it.time }
            latestRecord?.let { record ->
                val spO2 = record.percentage.value.toDouble()
                val hoursAgo = ChronoUnit.HOURS.between(record.time, Instant.now())
                Log.d(TAG, "✅ Found Samsung Sleep SpO₂: $spO2% (from ${hoursAgo}h ago)")
                spO2
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in extended SpO₂ search", e)
            null
        }
    }

    /** Check if SpO₂ data is available (Samsung Galaxy Watch 4 specific) */
    suspend fun isSpO2DataAvailable(): Boolean {
        return try {
            val endTime = Instant.now()
            val startTime = endTime.minus(SPO2_EXTENDED_LOOKBACK_HOURS, ChronoUnit.HOURS)

            val request =
                    ReadRecordsRequest(
                            recordType = OxygenSaturationRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )

            val response = healthConnectClient.readRecords(request)
            val hasSpO2Data = response.records.isNotEmpty()

            Log.d(
                    TAG,
                    "SpO₂ availability check: ${if (hasSpO2Data) "✅ Available" else "❌ Not available"}"
            )
            if (hasSpO2Data) {
                val latestRecord = response.records.maxByOrNull { it.time }
                val hoursAgo =
                        latestRecord?.let { ChronoUnit.HOURS.between(it.time, Instant.now()) }
                Log.d(TAG, "Most recent SpO₂ data is ${hoursAgo}h old")
            } else {
                Log.d(TAG, "Samsung Galaxy Watch 4 SpO₂ is primarily collected during sleep")
                Log.d(TAG, "Ensure sleep tracking is enabled and check after sleep periods")
            }

            hasSpO2Data
        } catch (e: Exception) {
            Log.e(TAG, "Error checking SpO₂ availability", e)
            false
        }
    }

    /** Fallback to simulated data when real data is not available */
    private fun generateFallbackHeartRate(): Double {
        val baseHeartRate = 72.0
        val variation = (-12..18).random()
        return (baseHeartRate + variation).coerceIn(55.0, 105.0)
    }

    private fun generateFallbackSpO2(): Double {
        val baseSpO2 = 98.0
        val variation = (-2..2).random()
        return (baseSpO2 + variation).coerceIn(94.0, 100.0)
    }

    /**
     * Get current health data (real from Samsung Health via Health Connect or fallback simulated)
     */
    suspend fun getCurrentHealthData(): HealthData {
        return try {
            // Samsung Health specific sync check
            if (System.currentTimeMillis() % 300000 < 5000) { // Check every 5 minutes
                val syncStatus = checkSamsungHealthSync()
                if (syncStatus == "SYNC_ISSUE_DETECTED") {
                    Log.w(TAG, "🔴 SAMSUNG HEALTH SYNC ISSUE DETECTED")
                    Log.w(TAG, "Check Samsung Health app and Health Connect permissions")
                    Log.w(TAG, "Using simulated data as fallback until sync is restored")
                }
            }

            // Try to get real data from Samsung Health
            val realHeartRate = getLatestHeartRate()
            val realSpO2 = getLatestSpO2()

            // Use real data if available, otherwise use fallback
            val heartRate =
                    realHeartRate
                            ?: run {
                                Log.d(
                                        TAG,
                                        "No recent Samsung Health heart rate data, using fallback"
                                )
                                generateFallbackHeartRate()
                            }

            val spO2 =
                    realSpO2
                            ?: run {
                                Log.d(TAG, "No recent Samsung Health SpO₂ data, using fallback")
                                generateFallbackSpO2()
                            }

            val healthData =
                    HealthData(
                            heartRate = heartRate,
                            spO2 = spO2,
                            timestamp = Instant.now(),
                            isRealData = realHeartRate != null && realSpO2 != null
                    )

            // Store for session averaging
            sessionHealthData.add(healthData)

            val dataSource = if (healthData.isRealData) "Samsung Health" else "Simulated"
            Log.d(TAG, "[$dataSource] Health data: HR=${heartRate.toInt()}, SpO₂=${spO2.toInt()}%")

            healthData
        } catch (e: Exception) {
            Log.e(TAG, "Error getting health data from Samsung Health", e)
            HealthData()
        }
    }

    /**
     * Create a continuous flow of health data updates Updates every 5 seconds with real Health
     * Connect data when available
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
            // Try to get real historical data from Health Connect
            val heartRateRecords = getHeartRateRecordsForPeriod(startTime, endTime)
            val spO2Records = getSpO2RecordsForPeriod(startTime, endTime)

            // Convert to HealthData objects
            val realHealthData = combineHealthRecords(heartRateRecords, spO2Records)

            if (realHealthData.isNotEmpty()) {
                Log.d(TAG, "Retrieved ${realHealthData.size} real health data points for period")
                realHealthData
            } else {
                // Fallback to session data if no real data available
                Log.d(TAG, "No real data for period, using session data")
                sessionHealthData.filter { healthData ->
                    !healthData.timestamp.isBefore(startTime) &&
                            !healthData.timestamp.isAfter(endTime)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading health data for period", e)
            sessionHealthData.filter { healthData ->
                !healthData.timestamp.isBefore(startTime) && !healthData.timestamp.isAfter(endTime)
            }
        }
    }

    /** Get heart rate records for a specific time period */
    private suspend fun getHeartRateRecordsForPeriod(
            startTime: Instant,
            endTime: Instant
    ): List<HeartRateRecord> {
        return try {
            val request =
                    ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )
            val response = healthConnectClient.readRecords(request)
            response.records
        } catch (e: Exception) {
            Log.e(TAG, "Error reading heart rate records for period", e)
            emptyList()
        }
    }

    /** Get SpO₂ records for a specific time period */
    private suspend fun getSpO2RecordsForPeriod(
            startTime: Instant,
            endTime: Instant
    ): List<OxygenSaturationRecord> {
        return try {
            val request =
                    ReadRecordsRequest(
                            recordType = OxygenSaturationRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )
            val response = healthConnectClient.readRecords(request)
            response.records
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SpO₂ records for period", e)
            emptyList()
        }
    }

    /** Combine heart rate and SpO₂ records into HealthData objects */
    private fun combineHealthRecords(
            heartRateRecords: List<HeartRateRecord>,
            spO2Records: List<OxygenSaturationRecord>
    ): List<HealthData> {
        val healthDataList = mutableListOf<HealthData>()

        // Create time-based mapping
        val heartRateMap = mutableMapOf<Long, Double>()
        heartRateRecords.forEach { record ->
            record.samples.forEach { sample ->
                val timeKey = sample.time.epochSecond / 60 // Group by minute
                heartRateMap[timeKey] = sample.beatsPerMinute.toDouble()
            }
        }

        val spO2Map = mutableMapOf<Long, Double>()
        spO2Records.forEach { record ->
            val timeKey = record.time.epochSecond / 60 // Group by minute
            spO2Map[timeKey] = record.percentage.value.toDouble()
        }

        // Combine data points
        val allTimeKeys = (heartRateMap.keys + spO2Map.keys).toSet()
        allTimeKeys.forEach { timeKey ->
            val heartRate = heartRateMap[timeKey]
            val spO2 = spO2Map[timeKey]

            if (heartRate != null || spO2 != null) {
                healthDataList.add(
                        HealthData(
                                heartRate = heartRate,
                                spO2 = spO2,
                                timestamp = Instant.ofEpochSecond(timeKey * 60),
                                isRealData = true
                        )
                )
            }
        }

        return healthDataList.sortedBy { it.timestamp }
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

        val hasRealData = healthDataList.any { it.isRealData }

        return HealthData(
                heartRate = avgHeartRate,
                spO2 = avgSpO2,
                timestamp = Instant.now(),
                isRealData = hasRealData
        )
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

    /** Check if Health Connect permissions are granted and request if needed */
    suspend fun checkAndRequestPermissions(): Boolean {
        return if (hasPermissions()) {
            true
        } else {
            Log.d(TAG, "Health Connect permissions not granted")
            // In a real app, you would launch the permission request here
            false
        }
    }

    /**
     * Check if the user is experiencing Samsung Health sync issues This helps diagnose connectivity
     * problems between Samsung Galaxy Watch 4 and Samsung Health
     */
    suspend fun checkSamsungHealthSync(): String {
        try {
            // Try to get some recent data to test sync status
            val endTime = Instant.now()
            val startTime = endTime.minus(2, ChronoUnit.HOURS) // Check last 2 hours

            val heartRateRequest =
                    ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )

            val heartRateResponse = healthConnectClient.readRecords(heartRateRequest)
            val hasRecentHeartRate = heartRateResponse.records.isNotEmpty()

            val spO2Request =
                    ReadRecordsRequest(
                            recordType = OxygenSaturationRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )

            val spO2Response = healthConnectClient.readRecords(spO2Request)
            val hasRecentSpO2 = spO2Response.records.isNotEmpty()

            return when {
                !hasRecentHeartRate && !hasRecentSpO2 -> {
                    Log.w(TAG, "⚠️ SAMSUNG HEALTH SYNC ISSUE DETECTED: No recent health data found")
                    Log.w(
                            TAG,
                            "This appears to be a Samsung Health sync issue affecting Galaxy Watch users"
                    )
                    Log.w(TAG, "📋 TROUBLESHOOTING STEPS:")
                    Log.w(TAG, "1. Restart your phone AND Samsung Galaxy Watch")
                    Log.w(TAG, "2. Toggle Bluetooth OFF/ON")
                    Log.w(TAG, "3. Check for Samsung Health app updates")
                    Log.w(TAG, "4. Try manual sync in Samsung Health app (pull down)")
                    Log.w(TAG, "5. Verify Health Connect permissions for Samsung Health")
                    Log.w(TAG, "6. If persistent, may need to unpair/re-pair watch")
                    "SYNC_ISSUE_DETECTED"
                }
                hasRecentHeartRate && !hasRecentSpO2 -> {
                    Log.d(
                            TAG,
                            "✅ Heart rate syncing, but SpO₂ missing (normal - SpO₂ is primarily collected during sleep)"
                    )
                    "PARTIAL_SYNC"
                }
                !hasRecentHeartRate && hasRecentSpO2 -> {
                    Log.d(
                            TAG,
                            "✅ SpO₂ syncing, but heart rate missing (unusual for Samsung Health)"
                    )
                    "PARTIAL_SYNC"
                }
                else -> {
                    Log.d(
                            TAG,
                            "✅ Both heart rate and SpO₂ data found - Samsung Health sync appears healthy"
                    )
                    "SYNC_HEALTHY"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Samsung Health sync status", e)
            return "SYNC_CHECK_FAILED"
        }
    }
}
