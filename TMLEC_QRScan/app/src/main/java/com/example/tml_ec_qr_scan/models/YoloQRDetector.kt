package com.example.tml_ec_qr_scan.models

import android.graphics.RectF

class YoloQRDetector {
    data class DetectionResult(
            val boundingBox: RectF,
            val confidence: Float,
            val label: String = "QR"
    )
}
