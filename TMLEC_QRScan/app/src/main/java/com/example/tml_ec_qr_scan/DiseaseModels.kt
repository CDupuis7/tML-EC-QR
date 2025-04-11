package com.example.tml_ec_qr_scan

/** Sealed class representing different UI states for the respiratory disease detection screen */
sealed class DiseaseUiState {
        /** Initial state before recording starts */
        object Ready : DiseaseUiState()

        /** Recording in progress with countdown timer */
        data class Recording(val remainingSeconds: Int) : DiseaseUiState()

        /** Processing and analyzing the recorded audio */
        object Analyzing : DiseaseUiState()

        /** Analysis complete with diagnosis result */
        data class Result(val diagnosisResult: DiagnosisResult) : DiseaseUiState()
}

/** Data class representing the result of respiratory disease analysis */
data class DiagnosisResult(
        val classification: String, // Previously "condition"
        val confidence: Float,
        val breathingRate: Float,
        val irregularityIndex: Float,
        val recommendations: List<String>
)
