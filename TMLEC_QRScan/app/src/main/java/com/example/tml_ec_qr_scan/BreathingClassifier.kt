package com.example.tml_ec_qr_scan

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import org.tensorflow.lite.Interpreter

/** Breathing classifier using TensorFlow Lite model */
class BreathingClassifier(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var abnormalityInterpreter: Interpreter? = null
    private var isModelAvailable = false
    private var _isAbnormalityModelAvailable = false
    private var _loadedModelName = "unknown"
    private val modelName = "breathing_classifier.tflite"

    // Define multiple potential model filenames to try
    private val abnormalityModelNames =
            arrayOf(
                    "breathing_abnormality.tflite",
                    "respiratory_disease.tflite",
                    "respiratory_abnormality.tflite"
            )

    // Track which model was successfully loaded
    private var loadedModelName: String
        get() = _loadedModelName
        set(value) {
            _loadedModelName = value
        }

    private val inputLength = 5 // 5 data points window for feature extraction
    private val numFeatures = 14 // Features used by the model

    // Class labels (will be loaded from the model)
    private val classLabels = arrayOf("inhaling", "exhaling")
    private val abnormalityLabels = arrayOf("Normal", "Abnormal")

    // Buffer for storing recent movements for classification
    private val recentMovements = ArrayList<RespiratoryDataPoint>()
    private var lastClassification = "unknown"
    private var confidenceScore = 0.0f

    // Feature extraction buffers
    private val inputBuffer =
            ByteBuffer.allocateDirect(4 * numFeatures).apply { order(ByteOrder.nativeOrder()) }
    private val outputBuffer = Array(1) { FloatArray(2) }

    // Abnormality classification buffers
    private var abnormalityInputBuffer =
            ByteBuffer.allocateDirect(4 * 4).apply { order(ByteOrder.nativeOrder()) } // 4 features
    private val abnormalityOutputBuffer = Array(1) { FloatArray(2) }

    // Time-based hysteresis to prevent too rapid phase changes
    private val lastPhaseChangeTime = mutableMapOf<String, Long>()

    // Track model loading attempt count and failures
    private var modelLoadAttempts = 0
    private var lastModelError: String? = null

    // Companion object for shared values and constants
    companion object {
        // Class labels
        private val breathingPhaseLabels = arrayOf("Inhaling", "Exhaling", "Pause")
        private val abnormalityLabels = arrayOf("Normal", "Abnormal")

        // Static references for model information
        private var isAbnormalityModelAvailable = false
        private var loadedModelName = "unknown"
    }

    init {
        // Check and log Android device info
        val deviceInfo =
                "Model: ${Build.MODEL}, Android: ${Build.VERSION.SDK_INT}, Brand: ${Build.BRAND}"
        Log.i("BreathingClassifier", "Device info: $deviceInfo")

        Log.i(
                "BreathingClassifier",
                "================================================================"
        )
        Log.i("BreathingClassifier", "INITIALIZING BREATHING CLASSIFIER - STARTING MODEL LOADING")
        Log.i(
                "BreathingClassifier",
                "================================================================"
        )

        try {
            loadModel()
            isModelAvailable = true
            Log.i("BreathingClassifier", "✓ ML model for breathing phase loaded successfully")
        } catch (e: Exception) {
            Log.w(
                    "BreathingClassifier",
                    "Breathing phase model not found, falling back to heuristic classification: ${e.message}"
            )
            isModelAvailable = false
        }

        try {
            // Try to load each potential model filename until one works
            var modelLoaded = false
            modelLoadAttempts = 0

            Log.i("BreathingClassifier", "Attempting to load abnormality classification model...")
            Log.i(
                    "BreathingClassifier",
                    "Will try these models: ${abnormalityModelNames.joinToString()}"
            )

            for (modelName in abnormalityModelNames) {
                try {
                    modelLoadAttempts++
                    Log.i(
                            "BreathingClassifier",
                            "[Attempt $modelLoadAttempts] Trying to load: $modelName"
                    )

                    loadAbnormalityModel(modelName)
                    _isAbnormalityModelAvailable = true
                    Companion.isAbnormalityModelAvailable = true
                    _loadedModelName = modelName
                    Companion.loadedModelName = modelName
                    modelLoaded = true

                    Log.i(
                            "BreathingClassifier",
                            "✓ SUCCESS! Abnormality classification model '$modelName' loaded successfully on attempt $modelLoadAttempts"
                    )
                    break
                } catch (e: Exception) {
                    lastModelError = e.message
                    Log.w(
                            "BreathingClassifier",
                            "✗ Failed to load model '$modelName': ${e.message}"
                    )
                }
            }

            if (!modelLoaded) {
                throw Exception(
                        "None of the model files could be loaded after $modelLoadAttempts attempts"
                )
            }
        } catch (e: Exception) {
            Log.w(
                    "BreathingClassifier",
                    "Abnormality model not found, using rule-based classification: ${e.message}"
            )
            _isAbnormalityModelAvailable = false
            Companion.isAbnormalityModelAvailable = false

            // Show error messages only when model loading actually fails
            Log.e(
                    "BreathingClassifier",
                    "✗ ERROR LOADING ANY MODEL - USING RULE-BASED CLASSIFICATION!"
            )
            Log.e("BreathingClassifier", "Total attempts: $modelLoadAttempts")
            Log.e("BreathingClassifier", "Last error: $lastModelError")
            Log.e(
                    "BreathingClassifier",
                    "================================================================"
            )
            Log.e("BreathingClassifier", "Available files in assets directory:")
            try {
                val assetsList = context.assets.list("") ?: emptyArray()
                for (asset in assetsList) {
                    Log.e("BreathingClassifier", "  - $asset")
                }
            } catch (e2: Exception) {
                Log.e("BreathingClassifier", "Could not list assets: ${e2.message}")
            }
            Log.e(
                    "BreathingClassifier",
                    "================================================================"
            )

            _loadedModelName = "MISSING! Copy models to assets folder"
            Companion.loadedModelName = "MISSING! Copy models to assets folder"
        }

        Log.i(
                "BreathingClassifier",
                "================================================================"
        )
        Log.i("BreathingClassifier", "CLASSIFIER INITIALIZATION COMPLETE")
        Log.i("BreathingClassifier", "Model loaded: $_isAbnormalityModelAvailable")
        Log.i("BreathingClassifier", "Using model: $_loadedModelName")
        Log.i(
                "BreathingClassifier",
                "================================================================"
        )
    }

    private fun loadModel() {
        try {
            // Try to load model info first
            context.assets.open("model_info.json").use { inputStream ->
                val modelInfoJson = inputStream.bufferedReader().use { it.readText() }
                val modelInfo = org.json.JSONObject(modelInfoJson)
                val classNamesArray = modelInfo.getJSONArray("class_names")
                for (i in 0 until classNamesArray.length()) {
                    classLabels[i] = classNamesArray.getString(i)
                }
                Log.i("BreathingClassifier", "Loaded class names: ${classLabels.joinToString()}")
            }

            // Now load the model
            context.assets.openFd(modelName).use { fileDescriptor ->
                val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
                val modelBuffer =
                        inputStream.channel.map(
                                FileChannel.MapMode.READ_ONLY,
                                fileDescriptor.startOffset,
                                fileDescriptor.declaredLength
                        )

                // Create a new interpreter with specified options
                val options =
                        Interpreter.Options().apply {
                            setNumThreads(2) // Use 2 threads for better performance
                        }
                interpreter = Interpreter(modelBuffer, options)

                Log.i("BreathingClassifier", "Loaded model from assets: $modelName")
            }
        } catch (e: Exception) {
            Log.e("BreathingClassifier", "Error loading model: ${e.message}")
            throw e
        }
    }

    private fun loadAbnormalityModel(modelName: String) {
        try {
            // First, check if the model file exists by listing the assets
            val assetsList = context.assets.list("") ?: emptyArray()
            val modelExists = assetsList.contains(modelName)

            Log.d(
                    "BreathingClassifier",
                    "Assets directory contents: ${assetsList.joinToString(", ")}"
            )
            Log.d("BreathingClassifier", "Looking for model file: $modelName")
            Log.d("BreathingClassifier", "Model file exists in assets: $modelExists")

            if (!modelExists) {
                throw Exception("Model file '$modelName' not found in assets")
            }

            // Load the abnormality classification model
            context.assets.openFd(modelName).use { fileDescriptor ->
                val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
                val modelBuffer =
                        inputStream.channel.map(
                                FileChannel.MapMode.READ_ONLY,
                                fileDescriptor.startOffset,
                                fileDescriptor.declaredLength
                        )

                // Create a new interpreter with specified options
                val options =
                        Interpreter.Options().apply {
                            setNumThreads(2) // Use 2 threads for better performance
                            setUseNNAPI(false) // Disable NNAPI to ensure consistent behavior
                        }

                // Close any existing interpreter
                abnormalityInterpreter?.close()

                // Create new interpreter
                abnormalityInterpreter = Interpreter(modelBuffer, options)

                // Analyze the model
                val inputTensor = abnormalityInterpreter?.getInputTensor(0)
                val inputShape = inputTensor?.shape() ?: intArrayOf()
                val inputSize = inputTensor?.numBytes() ?: 0
                val inputType = inputTensor?.dataType()?.name ?: "unknown"

                val outputTensor = abnormalityInterpreter?.getOutputTensor(0)
                val outputShape = outputTensor?.shape() ?: intArrayOf()

                // Update the byte buffer size if we need a different size than 16 bytes
                if (inputSize != 16) { // != 4 floats * 4 bytes
                    Log.d(
                            "BreathingClassifier",
                            "Model requires $inputSize bytes, updating buffer size"
                    )
                    // Re-create the input buffer with the correct size
                    val numFloats = inputSize / 4 // Assuming float inputs (4 bytes each)
                    abnormalityInputBuffer =
                            ByteBuffer.allocateDirect(inputSize).apply {
                                order(ByteOrder.nativeOrder())
                            }
                }

                Log.i(
                        "BreathingClassifier",
                        "SUCCESS! Loaded abnormality model: $modelName (size: ${fileDescriptor.declaredLength} bytes)"
                )
                Log.i("BreathingClassifier", "Model input shape: ${inputShape.joinToString()}")
                Log.i("BreathingClassifier", "Model input size: $inputSize bytes ($inputType)")
                Log.i("BreathingClassifier", "Model output shape: ${outputShape.joinToString()}")
            }
        } catch (e: Exception) {
            Log.e(
                    "BreathingClassifier",
                    "ERROR loading abnormality model '$modelName': ${e.message}"
            )

            // Try to check if we can access the assets directory at all
            try {
                val assetsList = context.assets.list("") ?: emptyArray()
                Log.e("BreathingClassifier", "Available assets: ${assetsList.joinToString(", ")}")
                Log.e("BreathingClassifier", "Looking for: $modelName")
            } catch (e2: Exception) {
                Log.e("BreathingClassifier", "Failed to list assets: ${e2.message}")
            }

            throw e
        }
    }

    /** Classify breathing as normal or abnormal using the trained model */
    fun classifyBreathing(
            breathingRate: Float,
            irregularityIndex: Float,
            amplitudeVariation: Float,
            avgVelocity: Float
    ): ClassificationResult {
        try {
            // First, log all input parameters for debugging
            Log.d("BreathingClassifier", "===== CLASSIFICATION INPUTS =====")
            Log.d("BreathingClassifier", "Breathing Rate: $breathingRate breaths/min")
            Log.d("BreathingClassifier", "Irregularity Index: $irregularityIndex")
            Log.d("BreathingClassifier", "Amplitude Variation: $amplitudeVariation")
            Log.d("BreathingClassifier", "Average Velocity: $avgVelocity")
            Log.d("BreathingClassifier", "Using Model?: $isAbnormalityModelAvailable")

            // Initialize detailed metrics to track
            val details = mutableMapOf<String, Float>()
            val normalizedValues = mutableMapOf<String, Float>()

            if (isAbnormalityModelAvailable) {
                try {
                    // Get the input tensor details
                    val inputTensor = abnormalityInterpreter?.getInputTensor(0)
                    val inputSize = inputTensor?.numBytes() ?: 0
                    val inputShape = inputTensor?.shape() ?: intArrayOf()

                    Log.d("BreathingClassifier", "Model input size: $inputSize bytes")
                    Log.d("BreathingClassifier", "Model input shape: ${inputShape.joinToString()}")
                    Log.d(
                            "BreathingClassifier",
                            "Input buffer capacity: ${abnormalityInputBuffer.capacity()} bytes"
                    )

                    // Use the trained ML model
                    Log.d(
                            "BreathingClassifier",
                            "USING ML MODEL for classification: $loadedModelName"
                    )

                    // Create the full feature vector from our available metrics
                    val featureVector =
                            createFeatureVector(
                                    breathingRate,
                                    irregularityIndex,
                                    amplitudeVariation,
                                    avgVelocity
                            )

                    // Store normalized values that were used
                    normalizedValues["breathingRate"] = featureVector[8]
                    normalizedValues["irregularity"] = featureVector[2]
                    normalizedValues["amplitude"] = featureVector[0]
                    normalizedValues["velocity"] = featureVector[6]

                    // Reset input buffer
                    abnormalityInputBuffer.rewind()

                    // Put each feature into the buffer
                    for (feature in featureVector) {
                        abnormalityInputBuffer.putFloat(feature)
                    }

                    // Prepare for inference
                    abnormalityInputBuffer.rewind()

                    // Run inference
                    abnormalityInterpreter?.run(abnormalityInputBuffer, abnormalityOutputBuffer)

                    // Get the predicted class and confidence
                    val probabilities = abnormalityOutputBuffer[0]

                    // Log all probabilities
                    for (i in probabilities.indices) {
                        val label = abnormalityLabels.getOrElse(i) { "Unknown-$i" }
                        val probability = probabilities[i]
                        Log.d("BreathingClassifier", "Class $label probability: $probability")
                        details["probability_$label"] = probability
                    }

                    var maxProbIndex = 0
                    var maxProb = probabilities[0]

                    // Find the class with the highest probability
                    for (i in 1 until probabilities.size) {
                        if (probabilities[i] > maxProb) {
                            maxProb = probabilities[i]
                            maxProbIndex = i
                        }
                    }

                    // Map index to class label
                    val predictedClass = abnormalityLabels[maxProbIndex]

                    // If confidence is too extreme (>0.95), reduce it slightly to avoid 100%
                    // confidence
                    val adjustedConfidence = if (maxProb > 0.95f) 0.95f else maxProb

                    Log.d(
                            "BreathingClassifier",
                            "ML MODEL RESULT: $predictedClass with confidence $adjustedConfidence"
                    )

                    // Create and return the detailed classification result
                    return ClassificationResult(
                            classification = predictedClass,
                            confidence = adjustedConfidence,
                            details = details,
                            normalizedValues = normalizedValues
                    )
                } catch (e: Exception) {
                    // If there's an error with the model, log it and fall back to rule-based
                    Log.e("BreathingClassifier", "Error using ML model: ${e.message}")
                    Log.e("BreathingClassifier", "Stack trace: ${e.stackTraceToString()}")
                    Log.d(
                            "BreathingClassifier",
                            "Falling back to rule-based approach due to model error"
                    )
                    details["error"] = 1.0f
                    details["errorMessage"] = e.message?.hashCode()?.toFloat() ?: 0f
                    // Continue to rule-based approach
                }
            }

            // If we get here, we need to use the rule-based approach
            // (either because model is not available or there was an error)

            // First, normalize our input values for consistent evaluation
            val normalizedBreathingRate =
                    when {
                        breathingRate < 8f -> 0.1f // Very slow breathing
                        breathingRate < 12f -> 0.3f // Slow breathing
                        breathingRate < 16f -> 0.5f // Normal lower range
                        breathingRate < 20f -> 0.7f // Normal upper range
                        breathingRate < 24f -> 0.8f // Fast breathing
                        else -> 0.9f // Very fast breathing
                    }
            val normalizedIrregularity = irregularityIndex.coerceIn(0f, 0.9f)

            normalizedValues["breathingRate"] = normalizedBreathingRate
            normalizedValues["irregularity"] = normalizedIrregularity
            normalizedValues["amplitude"] = (amplitudeVariation / 50f).coerceIn(0.1f, 0.9f)
            normalizedValues["velocity"] = (avgVelocity / 20f).coerceIn(0.1f, 0.9f)

            // Store detailed metrics
            details["breathingRateScore"] = if (breathingRate in 12f..20f) 0f else 1f
            details["irregularityScore"] = irregularityIndex
            details["amplitudeScore"] = if (amplitudeVariation > 10f) 0f else 1f

            // Use rule-based approach
            Log.d("BreathingClassifier", "Using rule-based classification")

            // Check for abnormal breathing
            // Breathing rate: normal is 12-20 breaths/min
            val abnormalRate = breathingRate < 12 || breathingRate > 20

            // Irregularity: normal is < 0.3
            val highIrregularity = irregularityIndex > 0.3

            // Shallow breathing check
            val shallowBreathing = amplitudeVariation < 10 && avgVelocity < 5

            // Count abnormal factors
            var abnormalFactors = 0
            if (abnormalRate) abnormalFactors++
            if (highIrregularity) abnormalFactors++
            if (shallowBreathing) abnormalFactors++

            details["abnormalFactors"] = abnormalFactors.toFloat()

            // Classification result
            val classification = if (abnormalFactors >= 2) "Abnormal" else "Normal"

            // Calculate confidence - more abnormal factors = higher confidence
            val confidence =
                    when (abnormalFactors) {
                        0 -> 0.85f // Normal with high confidence
                        1 -> 0.7f // Normal with moderate confidence
                        2 -> 0.8f // Abnormal with moderate confidence
                        else -> 0.9f // Abnormal with high confidence
                    }

            Log.d(
                    "BreathingClassifier",
                    "RULE-BASED RESULT: $classification with confidence $confidence"
            )
            Log.d(
                    "BreathingClassifier",
                    "Abnormal factors: Rate=$abnormalRate, Irregularity=$highIrregularity, Shallow=$shallowBreathing"
            )

            // Return the rule-based result with detailed metrics
            return ClassificationResult(
                    classification = classification,
                    confidence = confidence,
                    details = details,
                    normalizedValues = normalizedValues
            )
        } catch (e: Exception) {
            Log.e("BreathingClassifier", "General error in classification: ${e.message}")
            Log.e("BreathingClassifier", "Stack trace: ${e.stackTraceToString()}")

            // Return a safe default in case of any error
            return ClassificationResult(
                    classification = "Error",
                    confidence = 0.5f,
                    details = mapOf("error" to 1.0f),
                    normalizedValues = emptyMap()
            )
        }
    }

    /** Add a new data point and classify breathing phase if enough data is collected */
    fun processNewDataPoint(dataPoint: RespiratoryDataPoint): BreathingResult {
        // Add to recent movements
        recentMovements.add(dataPoint)

        // Trim to keep only the most recent points
        if (recentMovements.size > inputLength) {
            recentMovements.removeAt(0)
        }

        // If we don't have enough data points or model isn't available, use the heuristic approach
        if (recentMovements.size < inputLength || !isModelAvailable) {
            return classifyUsingHeuristics(dataPoint)
        }

        // Otherwise, use the ML model for classification
        return classifyUsingModel()
    }

    private fun classifyUsingModel(): BreathingResult {
        try {
            Log.d("BreathingClassifier", "USING ML MODEL FOR PREDICTION")
            // Extract features from the recent movements
            val features = extractFeatures()

            // Reset input buffer and add features
            inputBuffer.rewind()
            features.forEach { inputBuffer.putFloat(it) }

            // Run inference
            interpreter?.run(inputBuffer, outputBuffer)

            // Get the predicted class and confidence
            val probabilities = outputBuffer[0]
            var maxProbIndex = 0
            var maxProb = probabilities[0]

            // Find the class with the highest probability
            for (i in 1 until probabilities.size) {
                if (probabilities[i] > maxProb) {
                    maxProb = probabilities[i]
                    maxProbIndex = i
                }
            }

            // Map index to class label
            val predictedClass = classLabels[maxProbIndex]

            // Add hysteresis to prevent rapid switching - enforce natural breathing cycles
            val lastPhase =
                    if (recentMovements.size > 1)
                            recentMovements[recentMovements.size - 2].breathingPhase.lowercase()
                    else ""
            val smoothedVelocity = recentMovements.last().velocity
            val correctedClass = enforceBreathingCycle(lastPhase, predictedClass, smoothedVelocity)

            // Log the result
            Log.d(
                    "BreathingClassifier",
                    "ML classification - Raw Phase: $predictedClass, Corrected: $correctedClass, Confidence: $maxProb, Velocity: $smoothedVelocity"
            )

            lastClassification = correctedClass
            confidenceScore = maxProb

            return BreathingResult(correctedClass, maxProb)
        } catch (e: Exception) {
            Log.e("BreathingClassifier", "Error during model inference: ${e.message}")
            // Fallback to heuristic approach
            return classifyUsingHeuristics(recentMovements.last())
        }
    }

    private fun extractFeatures(): FloatArray {
        // This must match the feature extraction in the Python script
        val features = FloatArray(numFeatures)
        var featureIndex = 0

        // Make sure we have enough data
        if (recentMovements.size < inputLength) {
            return features // Return zeros if not enough data
        }

        // Extract velocities, positions, and amplitudes from the window
        val velocities = recentMovements.map { it.velocity }.toFloatArray()
        val yPositions = recentMovements.map { it.position.y }.toFloatArray()
        val amplitudes = recentMovements.map { it.amplitude }.toFloatArray()

        // Feature extraction matching your Python script feature_extraction.py
        // 1. Mean velocity
        features[featureIndex++] = velocities.average().toFloat()
        // 2. Std dev velocity
        features[featureIndex++] = calculateStd(velocities)
        // 3. Min velocity
        features[featureIndex++] = velocities.minOrNull() ?: 0f
        // 4. Max velocity
        features[featureIndex++] = velocities.maxOrNull() ?: 0f
        // 5. Median velocity
        features[featureIndex++] = velocities.median()

        // 6. Mean position
        features[featureIndex++] = yPositions.average().toFloat()
        // 7. Std dev position
        features[featureIndex++] = calculateStd(yPositions)
        // 8. Position range
        features[featureIndex++] = (yPositions.maxOrNull() ?: 0f) - (yPositions.minOrNull() ?: 0f)

        // 9. Mean amplitude
        features[featureIndex++] = amplitudes.average().toFloat()
        // 10. Max amplitude
        features[featureIndex++] = amplitudes.maxOrNull() ?: 0f

        // 11. Direction changes
        var directionChanges = 0
        for (i in 1 until velocities.size) {
            if ((velocities[i] > 0 && velocities[i - 1] < 0) ||
                            (velocities[i] < 0 && velocities[i - 1] > 0)
            ) {
                directionChanges++
            }
        }
        features[featureIndex++] = directionChanges.toFloat()

        // 12. Rate of velocity change
        var velocityChange = 0f
        for (i in 1 until velocities.size) {
            velocityChange += abs(velocities[i] - velocities[i - 1])
        }
        features[featureIndex++] = velocityChange / (velocities.size - 1)

        // 13. Normalized position in breath cycle
        val minY = yPositions.minOrNull() ?: 0f
        val maxY = yPositions.maxOrNull() ?: 0f
        val range = maxY - minY
        if (range > 0) {
            val lastY = yPositions.last()
            features[featureIndex++] = (lastY - minY) / range
        } else {
            features[featureIndex++] = 0.5f
        }

        // 14. Std dev of last few velocities
        val recentVelocities = velocities.takeLast(3).toFloatArray()
        features[featureIndex++] = calculateStd(recentVelocities)

        return features
    }

    private fun calculateStd(values: FloatArray): Float {
        if (values.isEmpty()) return 0f

        val mean = values.average().toFloat()
        val variance = values.map { (it - mean).pow(2) }.average().toFloat()
        return sqrt(variance)
    }

    private fun FloatArray.median(): Float {
        if (isEmpty()) return 0f
        val sorted = this.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
    }

    private fun FloatArray.average(): Double {
        if (isEmpty()) return 0.0
        return sum().toDouble() / size
    }

    /** Fallback heuristic-based classification when ML model isn't available */
    private fun classifyUsingHeuristics(dataPoint: RespiratoryDataPoint): BreathingResult {
        // Calculate smoothed velocity from recent movements
        val velocityHistory = recentMovements.map { it.velocity }
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

        // Determine breathing phase using velocity thresholds
        // NARROWER pause range for more natural breathing cycles
        val (phase, confidence) =
                when {
                    smoothedVelocity < -1.5f -> { // More sensitive threshold for inhaling (was -3f)
                        val conf = ((abs(smoothedVelocity) - 1.5f) / 10f).coerceIn(0.5f, 1.0f)
                        Pair("inhaling", conf)
                    }
                    smoothedVelocity > 1.5f -> { // More sensitive threshold for exhaling (was 3f)
                        val conf = ((abs(smoothedVelocity) - 1.5f) / 10f).coerceIn(0.5f, 1.0f)
                        Pair("exhaling", conf)
                    }
                    else -> {
                        // Narrower pause range
                        val conf =
                                (1f - abs(smoothedVelocity) / 1.5f).coerceIn(
                                        0.3f,
                                        0.7f
                                ) // Updated from 3f
                        Pair("pause", conf)
                    }
                }

        // Add hysteresis to prevent rapid switching - enforce natural breathing cycles
        val lastPhase =
                if (recentMovements.size > 1)
                        recentMovements[recentMovements.size - 2].breathingPhase.lowercase()
                else ""
        val correctedPhase = enforceBreathingCycle(lastPhase, phase, smoothedVelocity)

        Log.d(
                "BreathingClassifier",
                "Heuristic classification - Raw Velocity: ${dataPoint.velocity}, " +
                        "Smoothed: $smoothedVelocity, Phase: $phase, Corrected: $correctedPhase, Confidence: $confidence"
        )

        lastClassification = correctedPhase
        confidenceScore = confidence

        return BreathingResult(correctedPhase, confidence)
    }

    // Helper to enforce more natural breathing cycles
    private fun enforceBreathingCycle(
            lastPhase: String,
            currentPhase: String,
            velocity: Float
    ): String {
        // If velocity is very clearly in one direction, always respect that - lowered from 8f
        if (abs(velocity) > 5f) {
            return currentPhase
        }

        // Time-based hysteresis to prevent too rapid phase changes
        val currentTime = System.currentTimeMillis()
        val lastChange = lastPhaseChangeTime[lastPhase] ?: 0L
        val timeSinceLastChange = currentTime - lastChange

        // Minimum time in a phase before allowing changes (300ms)
        val MIN_PHASE_DURATION = 300L

        // Don't change too quickly unless velocity is significant
        if (lastPhase != currentPhase &&
                        timeSinceLastChange < MIN_PHASE_DURATION &&
                        abs(velocity) < 3f
        ) {
            return lastPhase
        }

        // Track when phase changes
        if (lastPhase != currentPhase) {
            lastPhaseChangeTime[currentPhase] = currentTime
        }

        // Natural breathing cycle: inhale -> (pause) -> exhale -> (pause) -> inhale
        // Prevent unnatural transitions like inhale -> exhale without pause
        // or exhale -> inhale without pause
        return when {
            // Prevent direct inhale -> exhale transitions
            lastPhase == "inhaling" && currentPhase == "exhaling" -> "pause"

            // Prevent direct exhale -> inhale transitions
            lastPhase == "exhaling" && currentPhase == "inhaling" -> "pause"

            // For pause -> X transitions, use velocity direction to guide the decision
            lastPhase == "pause" && currentPhase == "pause" -> {
                // Don't stay in pause too long - prefer to move to inhale/exhale
                if (velocity < -1f) "inhaling" else if (velocity > 1f) "exhaling" else "pause"
            }

            // Otherwise, respect the current phase
            else -> currentPhase
        }
    }

    /** Release resources when no longer needed */
    fun close() {
        interpreter?.close()
        abnormalityInterpreter?.close()
        recentMovements.clear()
    }

    /** Results class for breathing classification */
    data class BreathingResult(val phase: String, val confidence: Float)

    /** Result of breathing classification */
    data class ClassificationResult(
            val classification: String,
            val confidence: Float,
            val details: Map<String, Float> = emptyMap(),
            val normalizedValues: Map<String, Float> = emptyMap()
    ) {
        /** Returns information about which classification model is being used */
        fun getModelInfo(): String {
            return if (Companion.isAbnormalityModelAvailable) {
                "ML Model: ${Companion.loadedModelName}"
            } else {
                "Rule-Based Classification"
            }
        }

        /** Returns a detailed description of the classification result */
        fun getDetailedResult(): String {
            val sb = StringBuilder()
            sb.appendLine("Classification: $classification (${(confidence * 100).toInt()}%)")

            if (normalizedValues.isNotEmpty()) {
                sb.appendLine("\nNormalized Inputs:")
                normalizedValues.forEach { (key, value) -> sb.appendLine("- $key: $value") }
            }

            if (details.isNotEmpty()) {
                sb.appendLine("\nDetailed Results:")
                details.forEach { (key, value) -> sb.appendLine("- $key: $value") }
            }

            return sb.toString()
        }
    }

    /** Map available respiratory metrics to the full feature set expected by the model */
    private fun createFeatureVector(
            breathingRate: Float,
            irregularityIndex: Float,
            amplitudeVariation: Float,
            avgVelocity: Float
    ): FloatArray {
        // Create a vector with 35 features (matching the model's expected input)
        val features = FloatArray(35)

        Log.d(
                "BreathingClassifier",
                "Creating feature vector for model input - mapping 4 metrics to 35 features"
        )
        Log.d("BreathingClassifier", "INPUT VALUES:")
        Log.d("BreathingClassifier", "  - Breathing Rate: $breathingRate (normal range: 8-24)")
        Log.d("BreathingClassifier", "  - Irregularity: $irregularityIndex (normal range: 0-0.5)")
        Log.d("BreathingClassifier", "  - Amplitude Variation: $amplitudeVariation")
        Log.d("BreathingClassifier", "  - Average Velocity: $avgVelocity")

        // NORMALIZE our inputs to typical ranges expected by the model

        // Breathing rate: normalize to [0-1] range where 0.5 is perfectly normal (16 breaths/min)
        val normalizedBreathingRate =
                when {
                    breathingRate < 8f -> 0.1f // Very slow breathing
                    breathingRate < 12f -> 0.3f // Slow breathing
                    breathingRate < 16f -> 0.5f // Normal lower range
                    breathingRate < 20f -> 0.7f // Normal upper range
                    breathingRate < 24f -> 0.8f // Fast breathing
                    else -> 0.9f // Very fast breathing
                }

        // Irregularity: already in [0-1] range but may need adjustment
        val normalizedIrregularity = irregularityIndex.coerceIn(0f, 0.9f)

        // Amplitude: normalize to [0-1] range based on expected values
        val normalizedAmplitude = (amplitudeVariation / 50f).coerceIn(0.1f, 0.9f)

        // Velocity: normalize to [0-1] range
        val normalizedVelocity = (avgVelocity / 20f).coerceIn(0.1f, 0.9f)

        Log.d("BreathingClassifier", "NORMALIZED VALUES:")
        Log.d("BreathingClassifier", "  - Normalized Breathing Rate: $normalizedBreathingRate")
        Log.d("BreathingClassifier", "  - Normalized Irregularity: $normalizedIrregularity")
        Log.d("BreathingClassifier", "  - Normalized Amplitude: $normalizedAmplitude")
        Log.d("BreathingClassifier", "  - Normalized Velocity: $normalizedVelocity")

        // 0: rms_energy_mean - map to normalized amplitude
        features[0] = normalizedAmplitude

        // 1: rms_energy_std - slightly lower than mean
        features[1] = normalizedAmplitude * 0.5f

        // 2: zero_crossing_rate_mean - relate to irregularity
        features[2] = normalizedIrregularity * 0.5f

        // 3: zero_crossing_rate_std
        features[3] = normalizedIrregularity * 0.3f

        // 4: spectral_centroid_mean - derived from amplitude and irregularity
        features[4] = (normalizedAmplitude + normalizedIrregularity) * 0.4f

        // 5: spectral_centroid_std
        features[5] = (normalizedAmplitude + normalizedIrregularity) * 0.2f

        // 6: spectral_bandwidth_mean - relate to velocity
        features[6] = normalizedVelocity * 0.6f

        // 7: spectral_bandwidth_std
        features[7] = normalizedVelocity * 0.3f

        // 8: breathing_rate - direct normalized value
        features[8] = normalizedBreathingRate

        // MFCC features - these are harder to synthesize, but create reasonable values
        // We'll use combinations of our 4 normalized metrics with different weights

        // MFCCs typical range: -10 to +10, but we'll use 0-1 for simplicity
        val mfccBaseValues =
                listOf(
                        normalizedBreathingRate,
                        normalizedIrregularity,
                        normalizedAmplitude,
                        normalizedVelocity,
                        (normalizedBreathingRate + normalizedAmplitude) / 2,
                        (normalizedIrregularity + normalizedVelocity) / 2,
                        normalizedBreathingRate * normalizedAmplitude,
                        normalizedIrregularity * normalizedVelocity,
                        normalizedBreathingRate * 0.8f,
                        normalizedIrregularity * 0.7f,
                        normalizedAmplitude * 0.6f,
                        normalizedVelocity * 0.5f,
                        normalizedBreathingRate * normalizedIrregularity * 0.5f
                )

        // Fill MFCC mean and std values (positions 9-34)
        for (i in 0 until 13) {
            val meanPos = 9 + (i * 2)
            val stdPos = 10 + (i * 2)

            features[meanPos] =
                    mfccBaseValues[i % mfccBaseValues.size] *
                            (0.9f - (i * 0.05f)).coerceAtLeast(0.2f)
            features[stdPos] = features[meanPos] * 0.3f // std is typically smaller than mean
        }

        // Log some of the feature values
        Log.d("BreathingClassifier", "FEATURE VECTOR SAMPLES:")
        Log.d("BreathingClassifier", "  - features[0] (rms_energy_mean): ${features[0]}")
        Log.d("BreathingClassifier", "  - features[8] (breathing_rate): ${features[8]}")
        Log.d("BreathingClassifier", "  - features[9] (mfcc_0_mean): ${features[9]}")
        Log.d("BreathingClassifier", "  - features[34] (mfcc_12_std): ${features[34]}")

        return features
    }

    /** Returns information about which classification model is being used */
    fun getModelInfo(): String {
        return if (_isAbnormalityModelAvailable) {
            "ML Model: $_loadedModelName"
        } else {
            "Rule-Based Classification"
        }
    }
}
