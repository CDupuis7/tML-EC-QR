package com.example.tml_ec_qr_scan.ml

/**
 * Data class for holding audio features extracted from respiratory recordings
 */
data class AudioFeatures(
    val zeroCrossingRate: Float,
    val rmsEnergy: Float,
    val wheezeCount: Float,
    val crackleCount: Float,
    val respiratoryRate: Float,
    val duration: Float
)

