package com.example.tml_ec_qr_scan.models

import androidx.compose.ui.geometry.Offset

data class TrackedPoint(
        val center: Offset,
        val lastUpdateTime: Long,
        val velocityVector: Offset = Offset.Zero, // 2D velocity vector
        val velocityY: Float = 0f, // 1D vertical velocity
        val isLocked: Boolean = false,
        val initialPosition: Offset? = null
)
