package com.example.tml_ec_qr_scan

import android.content.Context
import android.util.Log
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.abs
import org.tensorflow.lite.Interpreter

/** Breathing classifier using TensorFlow Lite model */
class BreathingClassifier(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var isModelAvailable = false
    private val modelName = "breathing_classifier.tflite"
    private val inputLength = 30 // 30 data points window for classification
    private val inputFeatures = 4 // position.y, velocity, amplitude, direction

    // Buffer for storing recent movements for classification
    private val recentMovements = ArrayList<RespiratoryDataPoint>()
    private var lastClassification = "unknown"
    private var confidenceScore = 0.0f

    // Classification results
    data class BreathingResult(val phase: String, val confidence: Float)

    private var lastVelocity = 0f
    private var velocityHistory = mutableListOf<Float>()
    private val historySize = 5

    init {
        try {
            loadModel()
            isModelAvailable = true
        } catch (e: Exception) {
            Log.w(
                    "BreathingClassifier",
                    "Model not found, falling back to heuristic classification"
            )
            isModelAvailable = false
        }
    }

    private fun loadModel() {
        try {
            context.assets.openFd("breathing_classifier.tflite").use { fileDescriptor ->
                val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
                val modelBuffer =
                        inputStream.channel.map(
                                FileChannel.MapMode.READ_ONLY,
                                fileDescriptor.startOffset,
                                fileDescriptor.declaredLength
                        )
                interpreter = Interpreter(modelBuffer)
            }
        } catch (e: Exception) {
            Log.e("BreathingClassifier", "Error loading model: ${e.message}")
            throw e
        }
    }

    /** Add a new data point and classify breathing phase if enough data is collected */
    fun processNewDataPoint(dataPoint: RespiratoryDataPoint): BreathingResult {
        velocityHistory.add(dataPoint.velocity)
        if (velocityHistory.size > historySize) {
            velocityHistory.removeAt(0)
        }

        // Ensure we have at least some velocity data
        val smoothedVelocity =
                if (velocityHistory.size >= 2) {
                    val recentValues = velocityHistory.takeLast(2)
                    val weights = floatArrayOf(0.7f, 0.3f)
                    var sum = 0f
                    recentValues.forEachIndexed { index, velocity ->
                        sum += velocity * weights[index]
                    }
                    sum
                } else {
                    dataPoint.velocity
                }

        // VERY SENSITIVE thresholds to capture even small movements
        val (phase, confidence) =
                when {
                    smoothedVelocity <
                            -5f -> { // Threshold for inhaling (matches movement classification)
                        val conf = ((abs(smoothedVelocity) - 5f) / 10f).coerceIn(0.5f, 1.0f)
                        Pair("inhaling", conf)
                    }
                    smoothedVelocity >
                            5f -> { // Threshold for exhaling (matches movement classification)
                        val conf = ((abs(smoothedVelocity) - 5f) / 10f).coerceIn(0.5f, 1.0f)
                        Pair("exhaling", conf)
                    }
                    else -> {
                        val conf = (1f - abs(smoothedVelocity) / 5f).coerceIn(0.3f, 0.7f)
                        Pair("pause", conf)
                    }
                }

        Log.d(
                "BreathingClassifier",
                """
            Breathing Analysis:
            Raw Velocity: ${dataPoint.velocity}
            Smoothed Velocity: $smoothedVelocity
            Phase: $phase
            Confidence: $confidence
            Velocity History: ${velocityHistory.joinToString()}
        """.trimIndent()
        )

        return BreathingResult(phase, confidence)
    }

    /** Release resources when no longer needed */
    fun close() {
        interpreter?.close()
        velocityHistory.clear()
    }
}
