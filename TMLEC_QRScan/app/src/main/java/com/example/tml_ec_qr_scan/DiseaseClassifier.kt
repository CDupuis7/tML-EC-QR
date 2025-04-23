package com.example.tml_ec_qr_scan

import android.content.Context
import android.util.Log
import kotlin.math.pow
import kotlin.math.sqrt

class DiseaseClassifier(private val context: Context) {
        private var loaded = false
        private val classLabels = listOf("Normal", "Crackles", "Wheezes", "Both")

        // Constants for breathing condition detection
        companion object {
                private const val TAG = "DISEASE_CLASSIFIER"

                // Breathing rate thresholds for condition detection
                private const val BRADYPNEA_THRESHOLD = 10f // Below this is bradypnea
                private const val TACHYPNEA_THRESHOLD = 20f // Above this is tachypnea

                // Amplitude variability thresholds
                private const val HIGH_AMPLITUDE_VARIABILITY = 0.4f

                // Duration variability thresholds
                private const val HIGH_DURATION_VARIABILITY = 0.3f
        }

        init {
                // In a real app, we would load a trained model here
                loaded = true
        }

        /**
         * Classifies respiratory data to detect patterns
         * @param respiratoryData List of respiratory data points collected
         * @param breathingMetrics Calculated breathing metrics
         * @param patientMetadata Patient information
         * @return DiagnosisResult containing classification and recommendations
         */
        fun classify(
                respiratoryData: List<RespiratoryDataPoint>,
                breathingMetrics: BreathingMetrics,
                patientMetadata: PatientMetadata
        ): DiagnosisResult {
                // If we haven't loaded our model yet, return an error
                if (!loaded) {
                        return DiagnosisResult(
                                classification = "Error",
                                confidence = 0f,
                                breathingRate = breathingMetrics.breathingRate,
                                irregularityIndex = 0f,
                                recommendations = listOf("Model not loaded. Please try again."),
                                detectedConditions = emptyList(),
                                amplitudeVariability = 0f,
                                durationVariability = 0f
                        )
                }

                try {
                        // Extract ALL required features from respiratory data
                        val amplitudeVariability = calculateAmplitudeVariability(respiratoryData)
                        val durationVariability = calculateDurationVariability(respiratoryData)

                        // Calculate additional respiratory features
                        val avgVelocity = calculateAverageVelocity(respiratoryData)
                        val velocityVariability = calculateVelocityVariability(respiratoryData)
                        val breathCycleDuration = calculateBreathCycleDuration(respiratoryData)

                        // Log all extracted features for debugging
                        Log.d(TAG, "Extracted features from ${respiratoryData.size} data points:")
                        Log.d(
                                TAG,
                                "- Breathing rate: ${breathingMetrics.breathingRate} breaths/min"
                        )
                        Log.d(TAG, "- Amplitude variability: $amplitudeVariability")
                        Log.d(TAG, "- Duration variability: $durationVariability")
                        Log.d(TAG, "- Average velocity: $avgVelocity")
                        Log.d(TAG, "- Velocity variability: $velocityVariability")
                        Log.d(TAG, "- Breath cycle duration: $breathCycleDuration ms")

                        // Calculate irregularity index using ALL available features
                        val irregularityIndex =
                                calculateIrregularityIndex(
                                        breathingMetrics.breathingRate,
                                        amplitudeVariability,
                                        durationVariability,
                                        velocityVariability
                                )

                        // Create the feature vector with ALL features
                        val featureVector =
                                createFullFeatureVector(
                                        breathingMetrics,
                                        amplitudeVariability,
                                        durationVariability,
                                        avgVelocity,
                                        velocityVariability,
                                        patientMetadata
                                )

                        // Detect specific breathing conditions
                        val detectedConditions =
                                detectBreathingConditions(
                                        breathingMetrics.breathingRate,
                                        amplitudeVariability,
                                        durationVariability
                                )

                        // Determine classification based on irregularity and conditions
                        val classification =
                                if (detectedConditions.isNotEmpty() || irregularityIndex > 50f) {
                                        "Abnormal"
                                } else {
                                        "Normal"
                                }

                        // Calculate confidence based on how abnormal or normal the breathing is
                        val confidence =
                                when {
                                        classification == "Abnormal" &&
                                                detectedConditions.size > 1 -> 0.9f
                                        classification == "Abnormal" &&
                                                detectedConditions.size == 1 -> 0.8f
                                        classification == "Normal" && irregularityIndex < 20f ->
                                                0.85f
                                        else -> 0.7f
                                }

                        // IMPORTANT DEBUG LOGS - DO NOT REMOVE
                        Log.i(TAG, "***** DISEASE DETECTION RESULTS *****")
                        Log.i(TAG, "Breathing Rate: ${breathingMetrics.breathingRate} breaths/min")
                        Log.i(TAG, "Classification: $classification")
                        Log.i(TAG, "Detected Conditions: $detectedConditions")
                        Log.i(TAG, "Irregularity Index: $irregularityIndex")
                        Log.i(TAG, "Amplitude Variability: $amplitudeVariability")
                        Log.i(TAG, "Duration Variability: $durationVariability")
                        Log.i(TAG, "*************************************")

                        // Generate detailed recommendations based on ALL detected conditions
                        val recommendations =
                                generateDetailedRecommendations(
                                        classification,
                                        breathingMetrics.breathingRate,
                                        irregularityIndex,
                                        detectedConditions
                                )

                        // Print recommendations for debugging
                        Log.i(TAG, "Recommendations: $recommendations")

                        Log.d(
                                TAG,
                                "Classification: $classification, Conditions: $detectedConditions"
                        )

                        // Create diagnosis result with ALL information
                        val diagnosisResult =
                                DiagnosisResult(
                                        classification = classification,
                                        confidence = confidence,
                                        breathingRate = breathingMetrics.breathingRate,
                                        irregularityIndex = irregularityIndex,
                                        recommendations = recommendations,
                                        detectedConditions = detectedConditions,
                                        amplitudeVariability = amplitudeVariability,
                                        durationVariability = durationVariability
                                )

                        // Verify diagnosis result
                        Log.i(
                                TAG,
                                "FINAL DIAGNOSIS: ${diagnosisResult.classification}, CONDITIONS: ${diagnosisResult.detectedConditions}"
                        )

                        return diagnosisResult
                } catch (e: Exception) {
                        Log.e(TAG, "Error during classification: ${e.message}")
                        return DiagnosisResult(
                                classification = "Error",
                                confidence = 0f,
                                breathingRate = breathingMetrics.breathingRate,
                                irregularityIndex = 0f,
                                recommendations = listOf("Error during analysis: ${e.message}"),
                                detectedConditions = emptyList(),
                                amplitudeVariability = 0f,
                                durationVariability = 0f
                        )
                }
        }

        /**
         * Detects specific breathing conditions based on respiratory metrics
         * @return List of detected conditions (empty if normal)
         */
        private fun detectBreathingConditions(
                breathingRate: Float,
                amplitudeVariability: Float,
                durationVariability: Float
        ): List<String> {
                val conditions = mutableListOf<String>()

                // Detect bradypnea (abnormally slow breathing)
                if (breathingRate < BRADYPNEA_THRESHOLD) {
                        conditions.add("Bradypnea (slow breathing)")
                        Log.d(TAG, "Detected BRADYPNEA: Rate = $breathingRate breaths/min")
                }

                // Detect tachypnea (abnormally rapid breathing)
                if (breathingRate > TACHYPNEA_THRESHOLD) {
                        conditions.add("Tachypnea (rapid breathing)")
                        Log.d(TAG, "Detected TACHYPNEA: Rate = $breathingRate breaths/min")
                }

                // Detect high amplitude variability (irregular breathing depth)
                if (amplitudeVariability > HIGH_AMPLITUDE_VARIABILITY) {
                        conditions.add("High amplitude variability (irregular breathing depth)")
                        Log.d(TAG, "Detected HIGH AMPLITUDE VARIABILITY: $amplitudeVariability")
                }

                // Detect high duration variability (irregular timing)
                if (durationVariability > HIGH_DURATION_VARIABILITY) {
                        conditions.add("High timing variability (irregular rhythm)")
                        Log.d(TAG, "Detected HIGH DURATION VARIABILITY: $durationVariability")
                }

                return conditions
        }

        /** Calculates an irregularity index from 0-100 based on ALL available features */
        private fun calculateIrregularityIndex(
                breathingRate: Float,
                amplitudeVariability: Float,
                durationVariability: Float,
                velocityVariability: Float
        ): Float {
                var index = 0f

                // Factor 1: Breathing rate irregularity
                // Normal adult breathing rate is 12-20 breaths per minute
                val breathingRateScore =
                        when {
                                breathingRate < 8 -> 25f // Too slow
                                breathingRate > 25 -> 25f // Too fast
                                breathingRate < 12 -> 15f // Slightly slow
                                breathingRate > 20 -> 15f // Slightly fast
                                else -> 0f // Normal range
                        }
                index += breathingRateScore

                // Factor 2: Amplitude variability
                // Higher variability can indicate irregular breathing
                val amplitudeScore = (amplitudeVariability * 100f).coerceIn(0f, 25f)
                index += amplitudeScore

                // Factor 3: Duration variability
                // Higher variability can indicate irregular rhythm
                val durationScore = (durationVariability * 100f).coerceIn(0f, 25f)
                index += durationScore

                // Factor 4: Velocity variability
                // Higher variability can indicate irregular muscle control
                val velocityScore = (velocityVariability * 100f).coerceIn(0f, 25f)
                index += velocityScore

                // Cap the index at 100
                return index.coerceIn(0f, 100f)
        }

        /** Creates a complete feature vector for classification */
        private fun createFullFeatureVector(
                metrics: BreathingMetrics,
                amplitudeVariability: Float,
                durationVariability: Float,
                avgVelocity: Float,
                velocityVariability: Float,
                patientInfo: PatientMetadata
        ): List<Float> {
                val features = mutableListOf<Float>()

                // Basic respiratory metrics
                features.add(metrics.breathingRate)
                features.add(metrics.averageAmplitude)
                features.add(metrics.maxAmplitude)
                features.add(metrics.minAmplitude)

                // Advanced respiratory metrics
                features.add(avgVelocity)
                features.add(amplitudeVariability)
                features.add(durationVariability)
                features.add(velocityVariability)

                // Patient demographics (for age/gender related analysis)
                features.add(patientInfo.age.toFloat())
                features.add(if (patientInfo.gender.lowercase() == "male") 1f else 0f)

                // Log the full feature vector
                Log.d(TAG, "Full feature vector for classification: $features")

                return features
        }

        // Calculate amplitude variability using standard deviation / mean (CV)
        private fun calculateAmplitudeVariability(data: List<RespiratoryDataPoint>): Float {
                if (data.size < 2) return 0f

                val amplitudes = data.map { it.amplitude }
                val mean = amplitudes.average().toFloat()

                // Calculate standard deviation
                val variance = amplitudes.map { (it - mean).pow(2) }.average().toFloat()
                val stdDev = sqrt(variance)

                // Return the coefficient of variation (standardized variability)
                return if (mean != 0f) stdDev / mean else 0f
        }

        // Calculate duration variability from respiratory data
        private fun calculateDurationVariability(data: List<RespiratoryDataPoint>): Float {
                // Reduced minimum requirement to better handle smaller data sets
                if (data.size < 4) return 0f

                // Extract inhale/exhale cycles
                val breathPhases = data.map { it.breathingPhase.lowercase() }
                val cycleDurations = mutableListOf<Int>()

                var cycleStart = -1
                for (i in breathPhases.indices) {
                        if (i > 0 &&
                                        breathPhases[i - 1] != "inhaling" &&
                                        breathPhases[i] == "inhaling"
                        ) {
                                // Found start of a new inhale
                                if (cycleStart >= 0) {
                                        // Complete previous cycle
                                        cycleDurations.add(i - cycleStart)
                                }
                                cycleStart = i
                        }
                }

                // Fall back to time-based approach if not enough cycles found
                if (cycleDurations.size < 2) {
                        val timestamps = data.map { it.timestamp }
                        val phases = data.map { it.breathingPhase.lowercase() }
                        val transitions = mutableListOf<Long>()

                        // Find transitions between different phases
                        for (i in 1 until data.size) {
                                if (phases[i] != phases[i - 1]) {
                                        transitions.add(timestamps[i])
                                }
                        }

                        // Need at least 3 transitions to calculate intervals
                        if (transitions.size < 3) return 0f

                        // Calculate intervals between transitions
                        val intervals = mutableListOf<Long>()
                        for (i in 1 until transitions.size) {
                                intervals.add(transitions[i] - transitions[i - 1])
                        }

                        // Calculate coefficient of variation
                        val mean = intervals.average()
                        val sumSquaredDiff = intervals.sumOf { (it - mean).pow(2) }
                        val stdDev = kotlin.math.sqrt(sumSquaredDiff / intervals.size)

                        return (stdDev / mean).toFloat()
                }

                // Calculate variability of cycle durations
                val mean = cycleDurations.average().toFloat()
                val variance = cycleDurations.map { (it - mean).pow(2) }.average().toFloat()
                val stdDev = sqrt(variance)

                // Return the coefficient of variation
                return stdDev / mean
        }

        // Calculate average velocity from respiratory data
        private fun calculateAverageVelocity(data: List<RespiratoryDataPoint>): Float {
                if (data.isEmpty()) return 0f

                // Calculate average of absolute velocity values
                val velocities = data.map { kotlin.math.abs(it.velocity) }
                return velocities.average().toFloat()
        }

        // Calculate velocity variability
        private fun calculateVelocityVariability(data: List<RespiratoryDataPoint>): Float {
                if (data.size < 2) return 0f

                val velocities = data.map { it.velocity }
                val mean = velocities.average().toFloat()

                // Calculate standard deviation
                val variance = velocities.map { (it - mean).pow(2) }.average().toFloat()
                val stdDev = sqrt(variance)

                // Return coefficient of variation (absolute value because mean could be near zero)
                return if (kotlin.math.abs(mean) > 0.1f) stdDev / kotlin.math.abs(mean) else stdDev
        }

        // Calculate average breath cycle duration in milliseconds
        private fun calculateBreathCycleDuration(data: List<RespiratoryDataPoint>): Float {
                if (data.size < 4) return 0f

                // Find complete breathing cycles (inhale to inhale)
                val breathPhases = data.map { it.breathingPhase.lowercase() }
                val timestamps = data.map { it.timestamp }
                val cycleTimes = mutableListOf<Long>()

                for (i in 1 until data.size) {
                        if (i > 0 &&
                                        breathPhases[i] == "inhaling" &&
                                        breathPhases[i - 1] != "inhaling"
                        ) {
                                cycleTimes.add(timestamps[i])
                        }
                }

                // Calculate durations between successive cycle starts
                if (cycleTimes.size < 2) return 0f

                val durations = mutableListOf<Long>()
                for (i in 1 until cycleTimes.size) {
                        durations.add(cycleTimes[i] - cycleTimes[i - 1])
                }

                return durations.average().toFloat()
        }

        // Helper function to generate detailed recommendations based on detected conditions
        private fun generateDetailedRecommendations(
                classification: String,
                breathingRate: Float,
                irregularityIndex: Float,
                detectedConditions: List<String>
        ): List<String> {
                val recommendations = mutableListOf<String>()

                // Overall classification
                recommendations.add(
                        "Your breathing shows signs of ${classification.lowercase()} patterns."
                )
                recommendations.add(
                        "Breathing rate: ${breathingRate.toInt()} breaths/min (normal range: 12-20 breaths/min)."
                )

                // Specific condition recommendations
                for (condition in detectedConditions) {
                        when {
                                condition.contains("Bradypnea") -> {
                                        recommendations.add(
                                                "You have bradypnea (abnormally slow breathing rate)."
                                        )
                                        recommendations.add(
                                                "This can be associated with medication effects, neurological conditions, or sleep apnea."
                                        )
                                }
                                condition.contains("Tachypnea") -> {
                                        recommendations.add(
                                                "You have tachypnea (abnormally rapid breathing rate)."
                                        )
                                        recommendations.add(
                                                "This can be associated with anxiety, fever, respiratory infections, or cardiopulmonary issues."
                                        )
                                }
                                condition.contains("amplitude variability") -> {
                                        recommendations.add(
                                                "Your breathing shows high amplitude variability (irregular breathing depth)."
                                        )
                                        recommendations.add(
                                                "This can suggest respiratory muscle weakness or uneven airflow in the lungs."
                                        )
                                }
                                condition.contains("timing variability") -> {
                                        recommendations.add(
                                                "Your breathing shows irregular rhythm with high timing variability."
                                        )
                                        recommendations.add(
                                                "This can be associated with sleep-disordered breathing or respiratory control issues."
                                        )
                                }
                        }
                }

                // Add general advice
                if (classification != "Normal") {
                        recommendations.add(
                                "Consider consulting a healthcare professional for proper evaluation of these findings."
                        )
                        recommendations.add(
                                "Regular breathing exercises and monitoring may help improve your respiratory function."
                        )
                } else {
                        recommendations.add(
                                "Continue to maintain good respiratory health through regular exercise and proper breathing techniques."
                        )
                }

                return recommendations
        }

        /** Releases resources */
        fun close() {
                // Clean up resources if needed
                loaded = false
        }
}
