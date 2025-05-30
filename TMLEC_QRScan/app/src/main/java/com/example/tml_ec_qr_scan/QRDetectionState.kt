package com.example.tml_ec_qr_scan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object QRDetectionState {
    private val _qrDetected = MutableStateFlow(false)
    val qrDetected: StateFlow<Boolean> = _qrDetected.asStateFlow()

    fun updateQrDetectionState(detected: Boolean) {
        _qrDetected.value = detected
    }
}
