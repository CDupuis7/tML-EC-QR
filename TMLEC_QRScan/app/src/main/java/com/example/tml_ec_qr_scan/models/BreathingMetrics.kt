package com.example.tml_ec_qr_scan.models

data class BreathingMetrics(
        val breathingRate: Float = 0f,
        val averageAmplitude: Float = 0f,
        val maxAmplitude: Float = 0f,
        val minAmplitude: Float = 0f,
        val breathCount: Int = 0
)
