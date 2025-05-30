package com.example.tml_ec_qr_scan

// Simple Kalman filter implementation for velocity smoothing
class KalmanFilter(
        private val processNoise: Float,
        private val measurementNoise: Float,
        private var errorCovariance: Float
) {
    private var estimate = 0f

    fun update(measurement: Float): Float {
        // Prediction update
        errorCovariance += processNoise

        // Measurement update
        val kalmanGain = errorCovariance / (errorCovariance + measurementNoise)
        estimate += kalmanGain * (measurement - estimate)
        errorCovariance = (1 - kalmanGain) * errorCovariance

        return estimate
    }
}
