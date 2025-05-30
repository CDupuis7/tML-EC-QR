package com.example.tml_ec_qr_scan.models

import androidx.compose.ui.geometry.Offset

data class RespiratoryDataPoint(
        val timestamp: Long,
        val position: Offset,
        val qrId: String,
        val movement: String,
        val breathingPhase: String,
        val amplitude: Float,
        val velocity: Float
)
