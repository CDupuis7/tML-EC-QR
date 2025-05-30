package com.example.tml_ec_qr_scan.models

//import androidx.compose.ui.geometry.Offset
//
//data class TrackedPoint(
//        val center: Offset,
//        val lastUpdateTime: Long,
//        val velocity: Offset,
//        val isLocked: Boolean,
//        val initialPosition: Offset? = null
//)
//
//data class CalibrationThresholds(
//        var inhaleThreshold: Float,
//        var exhaleThreshold: Float,
//        var pauseThresholdLow: Float,
//        var pauseThresholdHigh: Float
//)
//
//data class PatientMetadata(
//        val id: String,
//        val age: Int,
//        val gender: String,
//        val healthStatus: String,
//        val additionalNotes: String = ""
//)
//
//data class BreathingMetrics(
//        val breathingRate: Float,
//        val averageAmplitude: Float,
//        val maxAmplitude: Float,
//        val minAmplitude: Float,
//        val breathCount: Int
//)
//
//data class RespiratoryDataPoint(
//        val timestamp: Long,
//        val position: Offset,
//        val velocity: Float,
//        val breathingPhase: String,
//        val amplitude: Float,
//        val qrId: String = "unknown",
//        val movement: String = "unknown"
//)
//
//data class DiagnosisResult(
//        val classification: String,
//        val confidence: Float,
//        val breathingRate: Float,
//        val irregularityIndex: Float,
//        val recommendations: List<String>,
//        val detectedConditions: List<String>,
//        val amplitudeVariability: Float,
//        val durationVariability: Float
//)
//
//sealed class DiseaseUiState {
//    object Initial : DiseaseUiState()
//    object Loading : DiseaseUiState()
//    data class Success(val result: DiagnosisResult) : DiseaseUiState()
//    data class Error(val message: String) : DiseaseUiState()
//}
