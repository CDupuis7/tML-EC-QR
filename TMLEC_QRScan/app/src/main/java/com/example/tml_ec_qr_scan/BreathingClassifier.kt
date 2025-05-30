package com.example.tml_ec_qr_scan

import android.content.ContentValues.TAG
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp
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
            arrayOf("respiratory_disease.tflite", "respiratory_abnormality.tflite")

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
        private val breathingPhaseLabels = arrayOf("Inhaling", "Exhaling")
        private val abnormalityLabels = arrayOf("Normal", "Abnormal")

        // Static references for model information
        var isAbnormalityModelAvailable = false
        var loadedModelName = "unknown"
    }

    private val BREATHING_PHASE_MODEL_FILENAME = "breathing_phase.tflite"
    private val RESPIRATORY_ABNORMALITY_MODEL_FILENAME = "respiratory_abnormality.tflite"
    private val RESPIRATORY_DISEASE_MODEL_FILENAME = "respiratory_disease.tflite"

    // Model Status flags
    private var _isBreathingPhaseModelAvailable = false
    private var isAbnormalityModelAvailable = false

    private val PYTHON_MODEL_WEIGHTS_FILENAME = "python_model_weights.json"
    private var _isPythonModelWeightsAvailable = false // New flag for Python model

    // Python model parameters (loaded from JSON)
    private var pythonModelWeights: Map<String, Float>? = null
    private var pythonModelThresholds: Map<String, Float>? = null

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
            init(context)
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
                    isAbnormalityModelAvailable = true
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
            isAbnormalityModelAvailable = false
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
        Log.i("BreathingClassifier", "Model loaded: $isAbnormalityModelAvailable")
        Log.i("BreathingClassifier", "Using model: $_loadedModelName")
        Log.i(
                "BreathingClassifier",
                "================================================================"
        )

        Log.d(TAG, "Model loaded successfully: ${abnormalityInterpreter != null}")
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

    /** Initialize the breathing classifier */
    fun init(context: Context) {
        try {
            // Log device info
            logDeviceInfo()

            // Attempt to load breathing phase model first
            try {
                Log.d(TAG, "Attempting to load breathing phase model")
                loadBreathingPhaseModel(context)
                _isBreathingPhaseModelAvailable = true
                Log.d(TAG, "Successfully loaded breathing phase model")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load breathing phase model: ${e.message}")
                _isBreathingPhaseModelAvailable = false
            }

            // Then attempt to load the abnormality model
            try {
                Log.d(TAG, "Attempting to load abnormality model as primary classifier")
                loadAbnormalityModel(context.toString())
                isAbnormalityModelAvailable = true
                Log.d(TAG, "Successfully loaded abnormality model")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load abnormality model: ${e.message}")
                isAbnormalityModelAvailable = false
            }

            Log.i(
                    TAG,
                    "Classifier initialized with models: " +
                            "Breathing Phase: $_isBreathingPhaseModelAvailable, " +
                            "Abnormality: $isAbnormalityModelAvailable"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing classifier: ${e.message}")
            Log.e(TAG, e.stackTraceToString())
        }
    }

    /**
     * Load Python model weights from the JSON file exported by
     * respiratory_pattern_classification.py
     */
    @Throws(IOException::class)
    private fun loadPythonModelWeights(context: Context) {
        try {
            // Check if the file exists
            val modelExists = fileExistsInAssets(context, PYTHON_MODEL_WEIGHTS_FILENAME)
            if (!modelExists) {
                Log.e(TAG, "Python model weights file not found in assets")
                throw IOException("Python model weights file not found in assets")
            }

            // Read the JSON file
            val modelJson =
                    context.assets.open(PYTHON_MODEL_WEIGHTS_FILENAME).bufferedReader().use {
                        it.readText()
                    }

            // Parse the JSON
            val gson = Gson()
            val modelData = gson.fromJson(modelJson, Map::class.java)

            // Extract weights and thresholds
            @Suppress("UNCHECKED_CAST")
            pythonModelWeights =
                    modelData["weights"] as? Map<String, Float>
                            ?: throw IOException("Invalid weights format in Python model file")

            @Suppress("UNCHECKED_CAST")
            pythonModelThresholds =
                    modelData["thresholds"] as? Map<String, Float>
                            ?: throw IOException("Invalid thresholds format in Python model file")

            Log.d(TAG, "Loaded Python model weights: ${pythonModelWeights?.keys}")
            Log.d(TAG, "Loaded Python model thresholds: ${pythonModelThresholds}")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Python model weights: ${e.message}")
            throw e
        }
    }

    /** Predict using the Python model weights (logistic regression) */
    private fun predictWithPythonModel(
            breathingRate: Float,
            irregularityIndex: Float,
            amplitudeVariation: Float,
            avgVelocity: Float
    ): Pair<Float, Float> {
        // Extract weights (or use defaults if not found)
        val weights = pythonModelWeights ?: return Pair(0.5f, 0.5f)

        // Get weights for each feature (or default to 0.0f if not found)
        val rateWeight = weights["breathing_rate"] ?: 0.0f
        val irregularityWeight = weights["irregularity_index"] ?: 0.0f
        val amplitudeWeight = weights["amplitude_variation"] ?: 0.0f
        val velocityWeight = weights["avg_velocity"] ?: 0.0f
        val biasWeight = weights["bias"] ?: 0.0f

        // Normalize features (should match Python preprocessing)
        val normalizedRate = breathingRate / 30f
        val normalizedIrregularity = irregularityIndex.coerceIn(0f, 1f)
        val normalizedAmplitude = (amplitudeVariation / 100f).coerceIn(0f, 1f)
        val normalizedVelocity = (avgVelocity / 15f).coerceIn(0f, 1f)

        // Calculate weighted sum
        val weightedSum =
                (normalizedRate * rateWeight) +
                        (normalizedIrregularity * irregularityWeight) +
                        (normalizedAmplitude * amplitudeWeight) +
                        (normalizedVelocity * velocityWeight) +
                        biasWeight

        // Apply sigmoid function to get probability
        val abnormalProb = 1.0f / (1.0f + exp(-weightedSum))
        val normalProb = 1.0f - abnormalProb

        Log.d(TAG, "Python model prediction: Normal=$normalProb, Abnormal=$abnormalProb")

        return Pair(normalProb, abnormalProb)
    }

    /** Classify breathing as normal or abnormal using the trained model */
    fun classifyBreathing(
            breathingRate: Float,
            irregularityIndex: Float,
            amplitudeVariation: Float,
            avgVelocity: Float
    ): ClassificationResultV2 {
        try {
            Log.d(
                    "BreathingClassifier",
                    "Classifying breathing - Rate: $breathingRate, Irregularity: $irregularityIndex, " +
                            "Amplitude Variation: $amplitudeVariation, Avg Velocity: $avgVelocity"
            )

            // Store all metrics for detailed results and identified conditions
            val details = mutableMapOf<String, Float>()
            val detectedConditions = mutableListOf<String>()

            // Create a separate list for conditions that actually affect classification
            val classificationRelevantConditions = mutableListOf<String>()

            details["breathingRate"] = breathingRate
            details["irregularityIndex"] = irregularityIndex
            details["amplitudeVariation"] = amplitudeVariation
            details["avgVelocity"] = avgVelocity

            // Normalize values for the model
            val normalizedValues = mutableMapOf<String, Float>()
            normalizedValues["breathingRate"] =
                    breathingRate / 30f // Normalize to 0-1 range (max 30)
            normalizedValues["irregularityIndex"] = irregularityIndex.coerceIn(0f, 1f)
            normalizedValues["amplitudeVariation"] = (amplitudeVariation / 100f).coerceIn(0f, 1f)
            normalizedValues["avgVelocity"] = (avgVelocity / 15f).coerceIn(0f, 1f)

            // Define thresholds for rule-based classification
            val thresholds =
                    mapOf(
                            "BRADYPNEA_THRESHOLD" to 10f,
                            "TACHYPNEA_THRESHOLD" to 24f,
                            "IRREGULARITY_THRESHOLD" to 0.6f,
                            "AMPLITUDE_VARIATION_THRESHOLD" to 60f,
                            "VELOCITY_THRESHOLD" to 12f
                    )

            val BRADYPNEA_THRESHOLD = thresholds["BRADYPNEA_THRESHOLD"] ?: 10f
            val TACHYPNEA_THRESHOLD = thresholds["TACHYPNEA_THRESHOLD"] ?: 24f
            val IRREGULARITY_THRESHOLD = thresholds["IRREGULARITY_THRESHOLD"] ?: 0.6f
            val AMPLITUDE_VARIATION_THRESHOLD = thresholds["AMPLITUDE_VARIATION_THRESHOLD"] ?: 60f
            val VELOCITY_THRESHOLD = thresholds["VELOCITY_THRESHOLD"] ?: 12f

            // Identify conditions based on thresholds (for detailed diagnostics)
            // Check breathing rate condition
            val isBreathingRateNormal = breathingRate in BRADYPNEA_THRESHOLD..TACHYPNEA_THRESHOLD
            if (!isBreathingRateNormal) {
                if (breathingRate < BRADYPNEA_THRESHOLD) {
                    detectedConditions.add("BRADYPNEA")
                    classificationRelevantConditions.add("BRADYPNEA")
                    Log.d(
                            "BreathingClassifier",
                            "BRADYPNEA detected: breathing rate ${breathingRate} < $BRADYPNEA_THRESHOLD"
                    )
                } else {
                    detectedConditions.add("TACHYPNEA")
                    classificationRelevantConditions.add("TACHYPNEA")
                    Log.d(
                            "BreathingClassifier",
                            "TACHYPNEA detected: breathing rate ${breathingRate} > $TACHYPNEA_THRESHOLD"
                    )
                }
            }

            // Check irregularity index - still detect and report it but don't include in
            // classification
            if (irregularityIndex > IRREGULARITY_THRESHOLD) {
                detectedConditions.add("HIGH_IRREGULARITY")
                details["abnormal_irregularity"] = 1.0f
                Log.d(
                        "BreathingClassifier",
                        "High timing variability detected (IGNORED FOR CLASSIFICATION): ${irregularityIndex} > $IRREGULARITY_THRESHOLD"
                )
            }

            // Check amplitude variation
            if (amplitudeVariation > AMPLITUDE_VARIATION_THRESHOLD) {
                detectedConditions.add("HIGH_AMPLITUDE_VARIATION")
                classificationRelevantConditions.add("HIGH_AMPLITUDE_VARIATION")
                details["abnormal_amplitude"] = 1.0f
                Log.d(
                        "BreathingClassifier",
                        "High amplitude variability detected: ${amplitudeVariation} > $AMPLITUDE_VARIATION_THRESHOLD"
                )
            }

            // Check average velocity
            if (avgVelocity > VELOCITY_THRESHOLD) {
                detectedConditions.add("HIGH_VELOCITY")
                classificationRelevantConditions.add("HIGH_VELOCITY")
                details["abnormal_velocity"] = 1.0f
                Log.d(
                        "BreathingClassifier",
                        "High velocity detected: ${avgVelocity} > $VELOCITY_THRESHOLD"
                )
            }

            // PRIMARY APPROACH: Use TFLite model if available
            if (isAbnormalityModelAvailable && abnormalityInterpreter != null) {
                return classifyWithTFLiteModel(
                        breathingRate,
                        irregularityIndex,
                        amplitudeVariation,
                        avgVelocity,
                        normalizedValues,
                        details,
                        detectedConditions,
                        isBreathingRateNormal
                )
            } else {
                // FALLBACK: Use rule-based classification if no models available
                Log.d("BreathingClassifier", "No models available, using rule-based classification")
                val classification =
                        useRuleBasedClassification(
                                isBreathingRateNormal,
                                detectedConditions.size,
                                detectedConditions
                        )
                val confidence = if (classification == "Abnormal") 0.85f else 0.9f

                // Add model probabilities to details
                val probNormal = if (classification == "Normal") confidence else 1.0f - confidence
                val probAbnormal =
                        if (classification == "Abnormal") confidence else 1.0f - confidence
                details["model_prob_normal"] = probNormal
                details["model_prob_abnormal"] = probAbnormal

                // Create detailed diagnostic info
                val diagnosticInfo =
                        createDiagnosticInfo(
                                classification,
                                breathingRate,
                                irregularityIndex,
                                amplitudeVariation,
                                avgVelocity,
                                detectedConditions
                        )

                // Store diagnostic info
                details["diagnostic_info"] = diagnosticInfo.hashCode().toFloat()

                Log.d(
                        "BreathingClassifier",
                        "FINAL CLASSIFICATION: $classification with confidence $confidence (breathing rate: $breathingRate)"
                )
                Log.d("BreathingClassifier", "Detected conditions: $detectedConditions")

                // Create and return the detailed classification result
                return ClassificationResultV2(
                        classification = classification,
                        confidence = confidence,
                        details = details,
                        normalizedValues = normalizedValues.toMap(),
                        detectedConditions = detectedConditions.toList(),
                        diagnosticInfo = diagnosticInfo
                )
            }
        } catch (e: Exception) {
            Log.e("BreathingClassifier", "General error in classification: ${e.message}")
            Log.e("BreathingClassifier", "Stack trace: ${e.stackTraceToString()}")

            // Return a safe default in case of any error
            return ClassificationResultV2(
                    classification = "Error",
                    confidence = 0.5f,
                    details = mapOf("error" to 1.0f),
                    normalizedValues = emptyMap(),
                    detectedConditions = listOf("ERROR"),
                    diagnosticInfo = "Error during classification: ${e.message}"
            )
        }
    }

    /** Helper method for TFLite model classification */
    private fun classifyWithTFLiteModel(
            breathingRate: Float,
            irregularityIndex: Float,
            amplitudeVariation: Float,
            avgVelocity: Float,
            normalizedValues: Map<String, Float>,
            details: MutableMap<String, Float>,
            detectedConditions: MutableList<String>,
            isBreathingRateNormal: Boolean
    ): ClassificationResultV2 {
        var modelProbNormal = 0.5f
        var modelProbAbnormal = 0.5f
        var classification: String
        var confidence: Float

        try {
            Log.d("BreathingClassifier", "Running TFLite model for classification")

            // Reset input buffer and add normalized features
            abnormalityInputBuffer.rewind()
            abnormalityInputBuffer.putFloat(normalizedValues["breathingRate"]!!)
            abnormalityInputBuffer.putFloat(normalizedValues["irregularityIndex"]!!)
            abnormalityInputBuffer.putFloat(normalizedValues["amplitudeVariation"]!!)
            abnormalityInputBuffer.putFloat(normalizedValues["avgVelocity"]!!)

            // Run inference with TFLite model
            abnormalityInterpreter?.run(abnormalityInputBuffer, abnormalityOutputBuffer)

            // Get the predicted probabilities from the model
            val probabilities = abnormalityOutputBuffer[0]

            // Log probabilities for each class
            for (i in probabilities.indices) {
                val label = abnormalityLabels.getOrElse(i) { "Unknown-$i" }
                val probability = probabilities[i]
                Log.d("BreathingClassifier", "Class $label probability: $probability")
                details["probability_$label"] = probability

                if (label == "Normal") modelProbNormal = probability
                if (label == "Abnormal") modelProbAbnormal = probability
            }

            // STEP 1: Initial model classification
            val initialModelClassification =
                    if (modelProbAbnormal > modelProbNormal) "Abnormal" else "Normal"
            Log.d(
                    "BreathingClassifier",
                    "Initial TFLite model classification: $initialModelClassification"
            )
            Log.d(
                    "BreathingClassifier",
                    "Model probabilities - Normal: $modelProbNormal, Abnormal: $modelProbAbnormal"
            )

            // STEP 2: Count abnormal conditions and evaluate breathing rate
            val hasBradypnea = detectedConditions.contains("BRADYPNEA")
            val hasTachypnea = detectedConditions.contains("TACHYPNEA")
            val abnormalBreathingRate = hasBradypnea || hasTachypnea

            // Count the secondary factors (excluding irregularity index as requested)
            val secondaryFactors = mutableListOf<String>()
            if (detectedConditions.contains("HIGH_AMPLITUDE_VARIATION"))
                    secondaryFactors.add("HIGH_AMPLITUDE_VARIATION")
            if (detectedConditions.contains("HIGH_VELOCITY")) secondaryFactors.add("HIGH_VELOCITY")
            if (detectedConditions.contains("HIGH_IRREGULARITY")) secondaryFactors.add("HIGH_IRREGULARITY")

            // Log all the factors for transparency
            Log.d("BreathingClassifier", "==== DECISION FACTORS ====")
            Log.d(
                    "BreathingClassifier",
                    "Breathing rate: ${if(abnormalBreathingRate) "ABNORMAL" else "NORMAL"} ($breathingRate)"
            )
            Log.d(
                    "BreathingClassifier",
                    "Irregularity: ${if(detectedConditions.contains("HIGH_IRREGULARITY")) "ABNORMAL" else "NORMAL"} ($irregularityIndex) - NOT CONSIDERED"
            )
            Log.d(
                    "BreathingClassifier",
                    "Velocity: ${if(detectedConditions.contains("HIGH_VELOCITY")) "ABNORMAL" else "NORMAL"} ($avgVelocity)"
            )
            Log.d(
                    "BreathingClassifier",
                    "Amplitude variation: ${if(detectedConditions.contains("HIGH_AMPLITUDE_VARIATION")) "ABNORMAL" else "NORMAL"} ($amplitudeVariation)"
            )

            // STEP 3: Apply the majority voting rule based on your requirements
            val finalClassification: String
            val finalConfidence: Float

            if (abnormalBreathingRate) {
                // Breathing rate is the primary factor - if it's abnormal, final classification is
                // abnormal
                finalClassification = "Abnormal"
                // Use model confidence but ensure it's high enough to reflect our certainty
                finalConfidence = modelProbAbnormal.coerceAtLeast(0.7f)
                Log.d(
                        "BreathingClassifier",
                        "FINAL DECISION: ABNORMAL (abnormal breathing rate is primary factor)"
                )
            } else if (secondaryFactors.size >= 2) {
                // Both secondary factors are abnormal, which is enough for an abnormal
                // classification
                finalClassification = "Abnormal"
                finalConfidence = modelProbAbnormal.coerceAtLeast(0.65f)
                Log.d(
                        "BreathingClassifier",
                        "FINAL DECISION: ABNORMAL (two secondary factors abnormal: ${secondaryFactors.joinToString()})"
                )
            } else {
                // Not enough abnormal factors, so the classification is normal
                finalClassification = "Normal"
                finalConfidence = modelProbNormal.coerceAtLeast(0.65f)
                Log.d(
                        "BreathingClassifier",
                        "FINAL DECISION: NORMAL (breathing rate normal and fewer than 2 secondary factors abnormal)"
                )
            }

            // STEP 4: Record if the rule-based post-processing overrode the model's prediction
            if (finalClassification != initialModelClassification) {
                Log.d(
                        "BreathingClassifier",
                        "NOTE: Rule-based post-processing overrode model prediction from $initialModelClassification to $finalClassification"
                )
                details["model_overridden"] = 1.0f
                details["original_classification"] =
                        if (initialModelClassification == "Abnormal") 1.0f else 0.0f
            } else {
                Log.d(
                        "BreathingClassifier",
                        "Model prediction and final decision agree: $finalClassification"
                )
                details["model_overridden"] = 0.0f
            }

            // Return the final classification and confidence
            classification = finalClassification
            confidence = finalConfidence

            Log.d(
                    "BreathingClassifier",
                    "Final TFLite MODEL CLASSIFICATION: $classification with confidence $confidence"
            )

            // Store original model probabilities for reference
            details["original_model_prob_normal"] = modelProbNormal
            details["original_model_prob_abnormal"] = modelProbAbnormal

            // Update the model probabilities based on our final classification
            modelProbNormal = if (classification == "Normal") confidence else 1.0f - confidence
            modelProbAbnormal = if (classification == "Abnormal") confidence else 1.0f - confidence
        } catch (e: Exception) {
            Log.e(
                    "BreathingClassifier",
                    "Error running TFLite model: ${e.message}, falling back to rule-based"
            )
            // Continue using our rule-based decision as fallback
            classification =
                    useRuleBasedClassification(
                            isBreathingRateNormal,
                            detectedConditions.size,
                            detectedConditions
                    )
            confidence = if (classification == "Abnormal") 0.85f else 0.9f

            // Log that we had to fall back
            Log.w(
                    "BreathingClassifier",
                    "Had to fall back to rule-based classification due to model error"
            )
            details["model_error"] = 1.0f
        }

        // Add model probabilities to details
        details["model_prob_normal"] = modelProbNormal
        details["model_prob_abnormal"] = modelProbAbnormal

        // Create detailed diagnostic info
        val diagnosticInfo =
                createDiagnosticInfo(
                        classification,
                        breathingRate,
                        irregularityIndex,
                        amplitudeVariation,
                        avgVelocity,
                        detectedConditions
                )

        // Store diagnostic info
        details["diagnostic_info"] = diagnosticInfo.hashCode().toFloat()

        Log.d(
                "BreathingClassifier",
                "FINAL CLASSIFICATION: $classification with confidence $confidence (breathing rate: $breathingRate)"
        )
        Log.d("BreathingClassifier", "Detected conditions: $detectedConditions")

        // Create and return the detailed classification result
        return ClassificationResultV2(
                classification = classification,
                confidence = confidence,
                details = details,
                normalizedValues = normalizedValues.toMap(),
                detectedConditions = detectedConditions.toList(),
                diagnosticInfo = diagnosticInfo
        )
    }

    /** Helper method to create diagnostic info text */
    private fun createDiagnosticInfo(
            classification: String,
            breathingRate: Float,
            irregularityIndex: Float,
            amplitudeVariation: Float,
            avgVelocity: Float,
            detectedConditions: List<String>
    ): String {
        val diagnosticInfo = StringBuilder()

        // Use thresholds from Python model if available
        val thresholds =
                pythonModelThresholds
                        ?: mapOf(
                                "BRADYPNEA_THRESHOLD" to 10f,
                                "TACHYPNEA_THRESHOLD" to 24f,
                                "IRREGULARITY_THRESHOLD" to 0.6f,
                                "AMPLITUDE_VARIATION_THRESHOLD" to 60f,
                                "VELOCITY_THRESHOLD" to 12f
                        )

        val BRADYPNEA_THRESHOLD = thresholds["BRADYPNEA_THRESHOLD"] ?: 10f
        val TACHYPNEA_THRESHOLD = thresholds["TACHYPNEA_THRESHOLD"] ?: 24f
        val IRREGULARITY_THRESHOLD = thresholds["IRREGULARITY_THRESHOLD"] ?: 0.6f
        val AMPLITUDE_VARIATION_THRESHOLD = thresholds["AMPLITUDE_VARIATION_THRESHOLD"] ?: 60f
        val VELOCITY_THRESHOLD = thresholds["VELOCITY_THRESHOLD"] ?: 12f

        if (classification == "Abnormal") {
            diagnosticInfo.append("Abnormal breathing detected:\n")

            // Add specific conditions
            if (detectedConditions.contains("BRADYPNEA")) {
                diagnosticInfo.append(
                        "- BRADYPNEA: Breathing rate too slow (${breathingRate.format(1)} breaths/min)\n"
                )
            } else if (detectedConditions.contains("TACHYPNEA")) {
                diagnosticInfo.append(
                        "- TACHYPNEA: Breathing rate too fast (${breathingRate.format(1)} breaths/min)\n"
                )
            }

            if (detectedConditions.contains("HIGH_IRREGULARITY")) {
                diagnosticInfo.append(
                        "- High breathing irregularity (${irregularityIndex.format(2)})\n"
                )
            }

            if (detectedConditions.contains("HIGH_AMPLITUDE_VARIATION")) {
                diagnosticInfo.append(
                        "- High amplitude variation (${amplitudeVariation.format(1)})\n"
                )
            }

            if (detectedConditions.contains("HIGH_VELOCITY")) {
                diagnosticInfo.append("- High breathing velocity (${avgVelocity.format(1)})\n")
            }
        } else {
            diagnosticInfo.append("Normal breathing pattern\n")
            diagnosticInfo.append("- Breathing rate: ${breathingRate.format(1)} breaths/min\n")
        }

        // Add more general info
        diagnosticInfo.append("\nBreathing metrics:\n")
        diagnosticInfo.append(
                "- Rate: ${breathingRate.format(1)} breaths/min (normal range: $BRADYPNEA_THRESHOLD-$TACHYPNEA_THRESHOLD)\n"
        )
        diagnosticInfo.append(
                "- Irregularity: ${irregularityIndex.format(2)} (threshold: $IRREGULARITY_THRESHOLD)\n"
        )
        diagnosticInfo.append(
                "- Amplitude variation: ${amplitudeVariation.format(1)} (threshold: $AMPLITUDE_VARIATION_THRESHOLD)\n"
        )
        diagnosticInfo.append(
                "- Average velocity: ${avgVelocity.format(1)} (threshold: $VELOCITY_THRESHOLD)\n"
        )

        return diagnosticInfo.toString()
    }

    // Helper method for rule-based classification
    private fun useRuleBasedClassification(
            isBreathingRateNormal: Boolean,
            abnormalFactorsCount: Int,
            conditions: List<String> = emptyList()
    ): String {
        // Exclude HIGH_AMPLITUDE_VARIATION from abnormalFactorsCount for classification
        val relevantCount =
                abnormalFactorsCount -
                        (if (conditions.contains("HIGH_AMPLITUDE_VARIATION")) 1 else 0)

        Log.d(
                "BreathingClassifier",
                "Total abnormal factors: $abnormalFactorsCount, Relevant for classification: $relevantCount"
        )

        return when {
            // If breathing rate is abnormal, always classify as abnormal
            !isBreathingRateNormal -> {
                Log.d(
                        "BreathingClassifier",
                        "RULE-BASED: ABNORMAL due to breathing rate outside normal range"
                )
                "Abnormal"
            }
            // If breathing rate is normal but majority of secondary factors are abnormal
            // Requires at least 2 abnormal secondary factors (stricter than before)
            relevantCount >= 2 -> {
                Log.d(
                        "BreathingClassifier",
                        "RULE-BASED: ABNORMAL due to $relevantCount relevant abnormal secondary factors"
                )
                "Abnormal"
            }
            // If breathing rate is normal and most secondary factors are normal
            else -> {
                Log.d(
                        "BreathingClassifier",
                        "RULE-BASED: NORMAL - breathing rate normal and fewer than 2 relevant abnormal factors"
                )
                "Normal"
            }
        }
    }

    // Helper function to format float values for display
    private fun Float.format(digits: Int): String = String.format("%.${digits}f", this)

    // Enhanced classification result class with more detailed diagnostics
    data class ClassificationResultV2(
            val classification: String,
            val confidence: Float,
            val details: Map<String, Float> = emptyMap(),
            val normalizedValues: Map<String, Float> = emptyMap(),
            val detectedConditions: List<String> = emptyList(),
            val diagnosticInfo: String = ""
    ) {
        /** Returns information about which classification model is being used */
        fun getModelInfo(): String {
            return if (BreathingClassifier.isAbnormalityModelAvailable) {
                "ML Model: ${BreathingClassifier.loadedModelName}"
            } else {
                "Rule-Based Classification"
            }
        }

        /** Returns a formatted string of detected conditions */
        fun getDetectedConditionsFormatted(): String {
            // Only return detected conditions if classification is Abnormal
            if (classification != "Abnormal" || detectedConditions.isEmpty()) return "None"
            return detectedConditions.joinToString(", ")
        }

        /** Returns whether a specific condition was detected */
        fun hasCondition(condition: String): Boolean = detectedConditions.contains(condition)

        /** Determines if this is a breathing rate abnormality */
        fun isBreathingRateAbnormal(): Boolean =
                detectedConditions.contains("BRADYPNEA") || detectedConditions.contains("TACHYPNEA")
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

            // Get the velocity for phase determination
            val smoothedVelocity = calculateSmoothedVelocity(recentMovements)

            // Log all probabilities for debugging
            for (i in probabilities.indices) {
                Log.d(
                        "BreathingClassifier",
                        "Class '${classLabels.getOrElse(i) { "unknown-$i" }}' probability: ${probabilities[i]}"
                )
            }

            // Determine the breathing phase based primarily on velocity
            // This is a more reliable approach than relying solely on the ML model
            val phase = determineBreathingPhaseFromVelocity(smoothedVelocity)

            Log.d(
                    "BreathingClassifier",
                    "ML classification - Raw Model Output: $predictedClass, Velocity-based: $phase, " +
                            "Velocity: $smoothedVelocity, Confidence: $maxProb"
            )

            lastClassification = phase
            confidenceScore = maxProb

            return BreathingResult(phase, maxProb)
        } catch (e: Exception) {
            Log.e("BreathingClassifier", "Error during model inference: ${e.message}")
            // Fallback to heuristic approach
            return classifyUsingHeuristics(recentMovements.last())
        }
    }

    /** Calculate smoothed velocity from recent movements */
    private fun calculateSmoothedVelocity(movements: List<RespiratoryDataPoint>): Float {
        if (movements.size < 3) return movements.lastOrNull()?.velocity ?: 0f

        // Use weighted average with more weight on recent values
        val recentValues = movements.takeLast(3).map { it.velocity }
        val weights = floatArrayOf(0.5f, 0.3f, 0.2f) // More weight on recent values

        var sum = 0f
        for (i in recentValues.indices) {
            sum += recentValues[i] * weights[i]
        }

        return sum
    }

    /** Determine breathing phase based on velocity */
    private fun determineBreathingPhaseFromVelocity(velocity: Float): String {
        return when {
            velocity < -2.0f -> "inhaling"
            velocity > 2.0f -> "exhaling"
            else -> "pause"
        }
    }

    /** Fallback heuristic-based classification when ML model isn't available */
    private fun classifyUsingHeuristics(dataPoint: RespiratoryDataPoint): BreathingResult {
        // Calculate smoothed velocity from recent movements
        val smoothedVelocity = calculateSmoothedVelocity(recentMovements)

        // Determine breathing phase using velocity thresholds with wider pause range
        val phase = determineBreathingPhaseFromVelocity(smoothedVelocity)

        // Calculate confidence based on how far the velocity is from the threshold
        val confidence =
                when (phase) {
                    "inhaling" -> ((abs(smoothedVelocity) - 2.0f) / 10f).coerceIn(0.5f, 0.95f)
                    "exhaling" -> ((abs(smoothedVelocity) - 2.0f) / 10f).coerceIn(0.5f, 0.95f)
                    else -> (1f - abs(smoothedVelocity) / 2.0f).coerceIn(0.3f, 0.7f)
                }

        Log.d(
                "BreathingClassifier",
                "Heuristic classification - Raw Velocity: ${dataPoint.velocity}, " +
                        "Smoothed: $smoothedVelocity, Phase: $phase, Confidence: $confidence"
        )

        lastClassification = phase
        confidenceScore = confidence

        return BreathingResult(phase, confidence)
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

    /** Release resources when no longer needed */
    fun close() {
        interpreter?.close()
        abnormalityInterpreter?.close()
        recentMovements.clear()
    }

    /** Results class for breathing classification */
    data class BreathingResult(val phase: String, val confidence: Float)

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
        Log.d("BreathingClassifier", "  - Breathing Rate: $breathingRate (normal range: 12-20)")
        Log.d("BreathingClassifier", "  - Irregularity: $irregularityIndex (normal range: 0-0.5)")
        Log.d("BreathingClassifier", "  - Amplitude Variation: $amplitudeVariation")
        Log.d("BreathingClassifier", "  - Average Velocity: $avgVelocity")

        // NORMALIZE our inputs to typical ranges expected by the model

        // Breathing rate: normalize to [0-1] range where normal values (12-20) map to 0.4-0.6
        val normalizedBreathingRate =
                when {
                    breathingRate < 8f -> 0.1f // Very slow breathing
                    breathingRate < 12f -> 0.3f // Slow breathing
                    breathingRate <= 20f -> 0.5f // Normal range - all values map to 0.5
                    breathingRate < 24f -> 0.7f // Fast breathing
                    else -> 0.9f // Very fast breathing
                }

        // Other normalizations remain the same
        val normalizedIrregularity = irregularityIndex.coerceIn(0f, 0.9f)
        val normalizedAmplitude = (amplitudeVariation / 50f).coerceIn(0.1f, 0.9f)
        val normalizedVelocity = (avgVelocity / 20f).coerceIn(0.1f, 0.9f)

        Log.d("BreathingClassifier", "NORMALIZED VALUES:")
        Log.d("BreathingClassifier", "  - Normalized Breathing Rate: $normalizedBreathingRate")
        Log.d("BreathingClassifier", "  - Normalized Irregularity: $normalizedIrregularity")
        Log.d("BreathingClassifier", "  - Normalized Amplitude: $normalizedAmplitude")
        Log.d("BreathingClassifier", "  - Normalized Velocity: $normalizedVelocity")

        // Feature mapping remains the same
        features[0] = normalizedAmplitude
        features[1] = normalizedAmplitude * 0.5f
        features[2] = normalizedIrregularity * 0.5f
        features[3] = normalizedIrregularity * 0.3f
        features[4] = (normalizedAmplitude + normalizedIrregularity) * 0.4f
        features[5] = (normalizedAmplitude + normalizedIrregularity) * 0.2f
        features[6] = normalizedVelocity * 0.6f
        features[7] = normalizedVelocity * 0.3f
        features[8] = normalizedBreathingRate

        // Rest of the feature vector creation remains the same
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

        return features
    }

    /** Returns information about which classification model is being used */
    fun getModelInfo(): String {
        return if (isAbnormalityModelAvailable) {
            "ML Model: $_loadedModelName"
        } else {
            "Rule-Based Classification"
        }
    }

    private fun logDeviceInfo() {
        Log.d(TAG, "Device: ${Build.MODEL}, Android: ${Build.VERSION.SDK_INT}")
    }

    private fun loadBreathingPhaseModel(context: Context) {
        // This can be simplified since you don't actually use this model
        Log.d(TAG, "Breathing phase model not needed, skipping")
    }

    private fun fileExistsInAssets(context: Context, filename: String): Boolean {
        return try {
            val assetsList = context.assets.list("") ?: emptyArray()
            assetsList.contains(filename)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if file exists: ${e.message}")
            false
        }
    }
}

// Original classification result class needed for backward compatibility
data class ClassificationResult(
        val classification: String,
        val confidence: Float,
        val details: Map<String, Float> = emptyMap(),
        val normalizedValues: Map<String, Float> = emptyMap()
) {
    /** Returns information about which classification model is being used */
    fun getModelInfo(): String {
        return if (BreathingClassifier.Companion.isAbnormalityModelAvailable) {
            "ML Model: ${BreathingClassifier.Companion.loadedModelName}"
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
