package com.example.tml_ec_qr_scan

import android.util.Log

/**
 * Helper class to detect specific breathing conditions based on respiratory metrics Uses the same
 * logic as the Python script (respiratory_pattern_classification.py)
 */
class BreathingConditionDetector {
    companion object {
        private const val TAG = "BREATHING_DETECTOR"

        /**
         * Classify the overall breathing pattern as normal or abnormal
         * @param breathingRate Breathing rate in breaths per minute
         * @return "Normal" if breathing rate is between 12-20, otherwise "Abnormal"
         */
        fun classifyBreathingPattern(breathingRate: Float): String {
            // Using exact same logic as Python script:
            // rate_status = "NORMAL" if 12 <= breathing_rate <= 20 else "ABNORMAL"
            return if (breathingRate >= 12f && breathingRate <= 20f) "Normal" else "Abnormal"
        }

        /**
         * Detect specific breathing conditions based on respiratory metrics
         * @param breathingRate Breathing rate in breaths per minute
         * @param irregularityIndex Index of breathing irregularity (0-1)
         * @param amplitudeVariation Variation in breathing amplitude
         * @param durationVariability Variation in breathing duration
         * @return List of detected conditions (e.g., "Bradypnea", "Tachypnea")
         */
        fun detectBreathingConditions(
                breathingRate: Float,
                irregularityIndex: Float,
                amplitudeVariation: Float,
                durationVariability: Float
        ): List<String> {
            val conditions = mutableListOf<String>()

            // Debug the input values
            Log.i(TAG, "====== DETECTING BREATHING CONDITIONS ======")
            Log.i(TAG, "Breathing Rate: $breathingRate breaths/min")
            Log.i(TAG, "Irregularity Index: $irregularityIndex")
            Log.i(TAG, "Amplitude Variation: $amplitudeVariation")
            Log.i(TAG, "Duration Variability: $durationVariability")

            // Using EXACT same logic as Python script:
            // if breathing_rate < 12: print("→ Bradypnea detected (slow breathing)")
            // elif breathing_rate > 20: print("→ Tachypnea detected (rapid breathing)")
            if (breathingRate < 12f) {
                conditions.add("Bradypnea (slow breathing)")
                Log.i(TAG, "DETECTED: Bradypnea with rate $breathingRate")
            } else if (breathingRate > 20f) {
                conditions.add("Tachypnea (rapid breathing)")
                Log.i(TAG, "DETECTED: Tachypnea with rate $breathingRate")
            }

            // Other conditions from Python script
            if (amplitudeVariation > 0.3f) {
                conditions.add("High amplitude variability (irregular breathing depth)")
                Log.i(TAG, "DETECTED: High amplitude variability ($amplitudeVariation)")
            }

            if (durationVariability > 0.3f) {
                conditions.add("High timing variability (irregular breathing rhythm)")
                Log.i(TAG, "DETECTED: High timing variability ($durationVariability)")
            }

            if (irregularityIndex > 0.5f) {
                conditions.add("Irregular breathing pattern")
                Log.i(TAG, "DETECTED: Irregular breathing pattern ($irregularityIndex)")
            }

            // Final list of conditions
            Log.i(TAG, "FINAL DETECTED CONDITIONS: $conditions")
            Log.i(TAG, "==========================================")

            return conditions
        }

        /**
         * Generate detailed recommendations based on detected conditions
         * @param classification Overall classification ("Normal" or "Abnormal")
         * @param breathingRate Breathing rate in breaths per minute
         * @param detectedConditions List of detected conditions
         * @return List of recommendations to display to the user
         */
        fun generateRecommendations(
                classification: String,
                breathingRate: Float,
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
                        recommendations.add("You have bradypnea (abnormally slow breathing rate).")
                        recommendations.add(
                                "This can be associated with medication effects, neurological conditions, or sleep apnea."
                        )
                    }
                    condition.contains("Tachypnea") -> {
                        recommendations.add("You have tachypnea (abnormally rapid breathing rate).")
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

            // If no specific conditions, provide normal recommendation
            if (detectedConditions.isEmpty()) {
                recommendations.add("Your breathing pattern appears normal.")
                recommendations.add(
                        "Continue to maintain good respiratory health through regular exercise and proper breathing techniques."
                )
            } else {
                recommendations.add(
                        "Consider consulting a healthcare professional for proper evaluation of these findings."
                )
            }

            return recommendations
        }
    }
}
