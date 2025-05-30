package com.example.tml_ec_qr_scan.models

import androidx.compose.ui.geometry.Offset

data class BoofCvQrDetection(val rawValue: String?, val center: Offset, val corners: List<Offset>)
