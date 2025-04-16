package com.example.tml_ec_qr_scan

import android.content.Context
import android.util.Log
import kotlin.math.pow

class DiseaseClassifier(private val context: Context) {
        private var loaded = false
        private val classLabels = listOf("Normal", "Crackles", "Wheezes", "Both")

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
                        // Extract required features from respiratory data
                        val amplitudeVariability = calculateAmplitudeVariability(respiratoryData)
                        val durationVariability = calculateDurationVariability(respiratoryData)

                        // Calculate irregularity index (0-100)
                        // Higher values indicate more irregular breathing
                        val irregularityIndex =
                                calculateIrregularityIndex(
                                        false, // hasWheezes
                                        false, // hasCrackles
                                        0f, // zeroCrossingRate
                                        0f, // rmsEnergy
                                        amplitudeVariability,
                                        breathingMetrics.breathingRate
                                )

                        // Use the BreathingConditionDetector to determine classification
                        // using the same logic as the Python script
                        val classification =
                                BreathingConditionDetector.classifyBreathingPattern(
                                        breathingMetrics.breathingRate
                                )

                        // Calculate confidence (would normally come from the model)
                        val confidence = if (classification == "Normal") 0.9f else 0.8f

                        // Use the BreathingConditionDetector to detect specific conditions
                        // like bradypnea and tachypnea - exactly like the Python script
                        val detectedConditions =
                                BreathingConditionDetector.detectBreathingConditions(
                                        breathingMetrics.breathingRate,
                                        irregularityIndex / 100f, // Convert to 0-1 scale
                                        amplitudeVariability,
                                        durationVariability
                                )

                        // IMPORTANT DEBUG LOGS - DO NOT REMOVE
                        Log.i(TAG, "***** DISEASE DETECTION RESULTS *****")
                        Log.i(TAG, "Breathing Rate: ${breathingMetrics.breathingRate} breaths/min")
                        Log.i(TAG, "Classification: $classification")
                        Log.i(TAG, "Detected Conditions: $detectedConditions")
                        Log.i(TAG, "Irregularity Index: $irregularityIndex")
                        Log.i(TAG, "Amplitude Variability: $amplitudeVariability")
                        Log.i(TAG, "Duration Variability: $durationVariability")
                        Log.i(TAG, "*************************************")

                        // Generate recommendations based on detected conditions
                        val recommendations =
                                BreathingConditionDetector.generateRecommendations(
                                        classification,
                                        breathingMetrics.breathingRate,
                                        detectedConditions
                                )

                        // Print recommendations for debugging
                        Log.i(TAG, "Recommendations: $recommendations")

                        Log.d(
                                TAG,
                                "Classification: $classification, Conditions: $detectedConditions"
                        )

                        // EXPLICITLY SET DETECTED CONDITIONS
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

        /** Calculates an irregularity index from 0-100 based on available features */
        private fun calculateIrregularityIndex(
                hasWheezes: Boolean,
                hasCrackles: Boolean,
                zeroCrossingRate: Float,
                rmsEnergy: Float,
                variability: Float,
                breathingRate: Float
        ): Float {
                var index = 0f

                // Factor 1: Abnormal sounds
                if (hasWheezes) index += 30f
                if (hasCrackles) index += 25f

                // Factor 2: Breathing rate
                // Normal adult breathing rate is 12-20 breaths per minute
                val breathingRateScore =
                        when {
                                breathingRate < 8 -> 15f // Too slow
                                breathingRate > 25 -> 20f // Too fast
                                breathingRate > 20 -> 10f // Slightly elevated
                                else -> 0f // Normal range
                        }
                index += breathingRateScore

                // Factor 3: Signal variability
                // Higher variability can indicate irregular breathing
                index += variability * 50f

                // Cap the index at 100
                return index.coerceIn(0f, 100f)
        }

        /** Generates recommendations based on classification and metrics */
        private fun generateRecommendations(
                classification: String,
                irregularityIndex: Float,
                breathingRate: Float
        ): List<String> {
                val recommendations = mutableListOf<String>()

                // General breathing rate advice
                when {
                        breathingRate < 8 ->
                                recommendations.add(
                                        "Your breathing rate appears below normal. Try to take deeper, more regular breaths."
                                )
                        breathingRate > 25 ->
                                recommendations.add(
                                        "Your breathing rate is elevated. Try relaxation techniques to slow your breathing."
                                )
                        breathingRate > 20 ->
                                recommendations.add(
                                        "Your breathing rate is slightly elevated. Consider practicing slow, deep breathing."
                                )
                        else ->
                                recommendations.add(
                                        "Your breathing rate is within the normal range."
                                )
                }

                // Classification-specific recommendations
                when (classification) {
                        "Normal" -> {
                                if (irregularityIndex < 20) {
                                        recommendations.add(
                                                "Your respiratory sounds appear normal."
                                        )
                                } else {
                                        recommendations.add(
                                                "Your respiratory sounds are mostly normal, but there may be some minor irregularities."
                                        )
                                }
                        }
                        "Wheezes" -> {
                                recommendations.add(
                                        "Wheezing sounds were detected, which may indicate narrowed airways."
                                )
                                recommendations.add(
                                        "Consider avoiding potential triggers like allergens or irritants."
                                )
                                if (irregularityIndex > 50) {
                                        recommendations.add(
                                                "If wheezing persists or worsens, consult a healthcare professional."
                                        )
                                }
                        }
                        "Crackles" -> {
                                recommendations.add(
                                        "Crackle sounds were detected, which may indicate fluid in the lungs or air in the alveoli."
                                )
                                recommendations.add(
                                        "Stay hydrated and consider deep breathing exercises to clear airways."
                                )
                                if (irregularityIndex > 50) {
                                        recommendations.add(
                                                "If symptoms worsen or are accompanied by fever or chest pain, consult a healthcare professional."
                                        )
                                }
                        }
                        "Both" -> {
                                recommendations.add(
                                        "Both wheezing and crackle sounds were detected, which may indicate multiple respiratory issues."
                                )
                                recommendations.add(
                                        "Consider resting, staying hydrated, and monitoring your symptoms."
                                )
                                recommendations.add(
                                        "We recommend consulting a healthcare professional for proper evaluation."
                                )
                        }
                }

                return recommendations
        }

        // Calculate amplitude variability from respiratory data
        private fun calculateAmplitudeVariability(data: List<RespiratoryDataPoint>): Float {
                if (data.isEmpty()) return 0f

                val amplitudes = data.map { it.amplitude }
                val min = amplitudes.minOrNull() ?: 0f
                val max = amplitudes.maxOrNull() ?: 0f
                val mean = amplitudes.average().toFloat()

                // Return a normalized variability measure
                return if (mean > 0) {
                        (max - min) / mean
                } else {
                        0f
                }
        }

        // Calculate duration variability from respiratory data
        private fun calculateDurationVariability(data: List<RespiratoryDataPoint>): Float {
                if (data.size < 10) return 0f

                // Find phase transitions
                val phases = data.map { it.breathingPhase.lowercase() }
                val transitions = mutableListOf<Long>()

                for (i in 1 until data.size) {
                        if (phases[i] != phases[i - 1]) {
                                transitions.add(data[i].timestamp)
                        }
                }

                // Calculate intervals between transitions
                if (transitions.size < 3) return 0f

                val intervals = mutableListOf<Long>()
                for (i in 1 until transitions.size) {
                        intervals.add(transitions[i] - transitions[i - 1])
                }

                // Calculate coefficient of variation (standard deviation / mean)
                val mean = intervals.average()
                val sumSquaredDiff = intervals.sumOf { (it - mean).pow(2) }
                val stdDev = kotlin.math.sqrt(sumSquaredDiff / intervals.size)

                return (stdDev / mean).toFloat()
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

        companion object {
                private const val TAG = "DISEASE_CLASSIFIER"
        }
}
