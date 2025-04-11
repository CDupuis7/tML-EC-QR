package com.example.tml_ec_qr_scan

import android.content.Context
import android.util.Log
import kotlin.random.Random


class DiseaseClassifier(private val context: Context) {
        private var loaded = false
        private val classLabels = listOf("Normal", "Crackles", "Wheezes", "Both")

        init {
                // In a real app, we would load a trained model here
                loaded = true
        }

        /**
         * Classifies respiratory sounds based on extracted features
         * @param audioFeatures Features extracted from respiratory audio
         * @param breathingRate Calculated breathing rate from QR tracking
         * @return DiagnosisResult containing classification and recommendations
         */
        fun classify(audioFeatures: Map<String, Float>, breathingRate: Float): DiagnosisResult {
                // If we haven't loaded our model yet, return an error
                if (!loaded) {
                        return DiagnosisResult(
                                classification = "Error",
                                confidence = 0f,
                                breathingRate = breathingRate,
                                irregularityIndex = 0f,
                                recommendations = listOf("Model not loaded. Please try again.")
                        )
                }

                try {
                        // In a real implementation, we would use TensorFlow Lite to run inference
                        // For now, use a rule-based approach based on the extracted features

                        // Get key features
                        val hasWheezes = audioFeatures["has_wheezes"] ?: 0f
                        val hasCrackles = audioFeatures["has_crackles"] ?: 0f
                        val zeroCrossingRate = audioFeatures["zero_crossing_rate"] ?: 0f
                        val rmsEnergy = audioFeatures["rms_energy"] ?: 0f
                        val variability = audioFeatures["variability"] ?: 0f

                        // Calculate irregularity index (0-100)
                        // Higher values indicate more irregular breathing
                        val irregularityIndex =
                                calculateIrregularityIndex(
                                        hasWheezes > 0,
                                        hasCrackles > 0,
                                        zeroCrossingRate,
                                        rmsEnergy,
                                        variability,
                                        breathingRate
                                )

                        // Determine classification based on detected sounds
                        val classification =
                                when {
                                        hasWheezes > 0 && hasCrackles > 0 -> "Both"
                                        hasWheezes > 0 -> "Wheezes"
                                        hasCrackles > 0 -> "Crackles"
                                        else -> "Normal"
                                }

                        // Calculate confidence (would normally come from the model)
                        // For demo purposes, generate a confidence between 70-95%
                        val randomConfidence = 0.7f + (Random.nextFloat() * 0.25f)
                        val confidence =
                                when (classification) {
                                        "Normal" -> if (irregularityIndex < 20) 0.9f else 0.7f
                                        else -> randomConfidence
                                }

                        // Generate appropriate recommendations
                        val recommendations =
                                generateRecommendations(
                                        classification,
                                        irregularityIndex,
                                        breathingRate
                                )

                        return DiagnosisResult(
                                classification = classification,
                                confidence = confidence,
                                breathingRate = breathingRate,
                                irregularityIndex = irregularityIndex,
                                recommendations = recommendations
                        )
                } catch (e: Exception) {
                        Log.e(TAG, "Error during classification: ${e.message}")
                        return DiagnosisResult(
                                classification = "Error",
                                confidence = 0f,
                                breathingRate = breathingRate,
                                irregularityIndex = 0f,
                                recommendations = listOf("Error during analysis: ${e.message}")
                        )
                }
        }

        /**
         * Calculates an irregularity index from 0-100 based on audio features and breathing rate
         */
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

        /** Releases resources */
        fun close() {
                // Clean up resources if needed
                loaded = false
        }

        companion object {
                private const val TAG = "DiseaseClassifier"
        }
}
