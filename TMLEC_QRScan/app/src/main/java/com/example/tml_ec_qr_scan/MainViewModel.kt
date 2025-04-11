package com.example.tml_ec_qr_scan

// Add explicit imports for our models
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UiState {
    object Initial : UiState()
    object CameraSetup : UiState()
    object Recording : UiState()
    object Results : UiState()
    object Calibrating : UiState()
    object DiseaseDetection : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // UI state
    private val _uiState = MutableStateFlow<UiState>(UiState.Initial)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Recording state
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Ready to record state (for QR positioning)
    private val _readyToRecord = MutableStateFlow(false)
    val readyToRecord: StateFlow<Boolean> = _readyToRecord.asStateFlow()

    // Calibration state
    private val _isCalibrating = MutableStateFlow(false)
    val isCalibrating: StateFlow<Boolean> = _isCalibrating.asStateFlow()

    // Respiratory data
    private val _respiratoryData = MutableStateFlow<List<RespiratoryDataPoint>>(emptyList())
    val respiratoryData: StateFlow<List<RespiratoryDataPoint>> = _respiratoryData.asStateFlow()

    // Breathing phase
    private val _currentBreathingPhase = MutableStateFlow<String>("Unknown")
    val currentBreathingPhase: StateFlow<String> = _currentBreathingPhase.asStateFlow()

    // Confidence
    private val _breathingConfidence = MutableStateFlow<Float>(0.0f)
    val breathingConfidence: StateFlow<Float> = _breathingConfidence.asStateFlow()

    // Velocity
    private val _currentVelocity = MutableStateFlow<Float>(0.0f)
    val currentVelocity: StateFlow<Float> = _currentVelocity.asStateFlow()

    // Patient metadata
    private val _patientMetadata = MutableStateFlow<PatientMetadata?>(null)
    val patientMetadata: StateFlow<PatientMetadata?> = _patientMetadata.asStateFlow()

    // Callback to complete calibration in MainActivity
    private var calibrationCompleter: (() -> Unit)? = null

    // UI state for disease detection
    private val _diseaseUiState = MutableStateFlow<DiseaseUiState>(DiseaseUiState.Ready)
    val diseaseUiState: StateFlow<DiseaseUiState> = _diseaseUiState

    // Breathing data buffer for disease detection
    private val breathingDataBuffer = mutableListOf<RespiratoryDataPoint>()

    // QR data collection flag for disease classification
    private var collectingQRDataForDisease = false
    private var qrDataCollectionStartTime = 0L

    // Track when recording starts
    private var recordingStartTime = 0L

    // Breath analyzer variables
    private var lastAnalysisTime = 0L
    private val analysisInterval = 2000L // Analyze every 2 seconds (was 4000L)
    private val initialAnalysisDelay = 5000L // Wait 5 seconds before first analysis (was 8000L)
    private val minDataPointsForAnalysis = 20 // Need at least 20 data points (was 30)

    // Flag to track if we've started analyzing
    private var analysisStarted = false

    // Components for respiratory disease detection
    private var diseaseClassifier: DiseaseClassifier? = null
    private var recordingJob: Job? = null

    // Training mode state
    private val _isTrainingMode = MutableStateFlow(false)
    val isTrainingMode: StateFlow<Boolean> = _isTrainingMode.asStateFlow()

    // Camera state
    private val _isCameraStarted = MutableStateFlow(false)
    val isCameraStarted: StateFlow<Boolean> = _isCameraStarted.asStateFlow()

    // Breathing classification
    private val _breathingClassification = MutableStateFlow<String>("Unknown")
    val breathingClassification: StateFlow<String> = _breathingClassification.asStateFlow()

    // Classification confidence
    private val _classificationConfidence = MutableStateFlow<Float>(0.0f)
    val classificationConfidence: StateFlow<Float> = _classificationConfidence.asStateFlow()

    // Breathing classifier for real-time analysis
    private var breathingClassifier: BreathingClassifier? = null

    init {
        // Initialize components for disease detection
        diseaseClassifier = DiseaseClassifier(getApplication())
        // Initialize breathing classifier
        breathingClassifier = BreathingClassifier(getApplication())
    }

    /** Proceed to camera setup state without starting recording */
    fun proceedToCameraSetup() {
        _uiState.value = UiState.CameraSetup
    }

    /** Update calibration state */
    fun updateCalibrationState(isCalibrating: Boolean) {
        viewModelScope.launch {
            _isCalibrating.value = isCalibrating

            // If starting calibration, update UI state
            if (isCalibrating) {
                Log.d("MainViewModel", "Changing state to Calibrating")
                _uiState.value = UiState.Calibrating
            } else {
                // Return to camera setup when calibration is done
                Log.d("MainViewModel", "Changing state to CameraSetup (after calibration)")
                _uiState.value = UiState.CameraSetup
            }
        }
    }

    /** Start calibration process */
    fun startCalibration() {
        viewModelScope.launch {
            _isCalibrating.value = true
            Log.d("MainViewModel", "Changing state to Calibrating (startCalibration)")
            _uiState.value = UiState.Calibrating
        }
    }

    /** Prepare for recording (QR positioning mode) */
    fun prepareForRecording() {
        viewModelScope.launch {
            _readyToRecord.value = true
            _isRecording.value = false
            Log.d("MainViewModel", "Changing state to Recording (QR positioning mode)")
            _uiState.value = UiState.Recording
        }
    }

    /** Start recording of respiratory data */
    fun startRecording() {
        viewModelScope.launch {
            _isRecording.value = true
            _readyToRecord.value = true
            _breathingClassification.value = "Analyzing..."
            _classificationConfidence.value = 0.0f
            Log.d("MainViewModel", "Starting actual recording")

            // Reset timing variables for analysis
            recordingStartTime = System.currentTimeMillis()
            lastAnalysisTime = 0L
            analysisStarted = false

            // Clear existing data only if we're starting from scratch
            if (_respiratoryData.value.isEmpty()) {
                _respiratoryData.value = emptyList()
                breathingDataBuffer.clear()
            }
        }
    }

    /** Stop recording and show results */
    fun stopRecording() {
        viewModelScope.launch {
            _isRecording.value = false
            _readyToRecord.value = false
            _uiState.value = UiState.Results
        }
    }

    /** Reset to initial state for a new patient */
    fun startNewPatient() {
        viewModelScope.launch {
            _patientMetadata.value = null
            _respiratoryData.value = emptyList()
            _currentBreathingPhase.value = "Unknown"
            _breathingConfidence.value = 0.0f
            _currentVelocity.value = 0.0f
            _isRecording.value = false
            _readyToRecord.value = false
            _isCalibrating.value = false
            _breathingClassification.value = "Unknown"
            _classificationConfidence.value = 0.0f
            breathingDataBuffer.clear()
            _uiState.value = UiState.Initial
        }
    }

    /** Update patient metadata */
    fun updatePatientMetadata(metadata: PatientMetadata) {
        viewModelScope.launch { _patientMetadata.value = metadata }
    }

    /** Update breathing data with phase, confidence, and velocity */
    fun updateBreathingData(phase: Int, confidence: Float, velocity: Float) {
        viewModelScope.launch {
            _currentBreathingPhase.value =
                    when (phase) {
                        -1 -> "Inhaling"
                        1 -> "Exhaling"
                        0 -> "Pause"
                        else -> "Unknown"
                    }
            _breathingConfidence.value = confidence
            _currentVelocity.value = velocity

            // Debug log to see what's being set
            Log.d(
                    "ViewModel",
                    "Updating breathing phase to: ${_currentBreathingPhase.value} (phase code: $phase), velocity: $velocity"
            )
        }
    }

    /** Add a new respiratory data point and analyze if enough time has passed */
    fun addRespiratoryDataPoint(dataPoint: RespiratoryDataPoint) {
        viewModelScope.launch {
            // Update phase and confidence - fixing the reference error
            _currentBreathingPhase.value = dataPoint.breathingPhase
            // There's no confidence field in RespiratoryDataPoint, so use a default value
            _breathingConfidence.value = 0.8f // Default confidence
            _currentVelocity.value = dataPoint.velocity

            // Add the data point
            _respiratoryData.value = _respiratoryData.value + dataPoint

            // Only accumulate breathing data during recording
            if (_isRecording.value) {
                // Add to our analysis buffer
                breathingDataBuffer.add(dataPoint)

                // Keep buffer from growing too large
                if (breathingDataBuffer.size > 1000) {
                    breathingDataBuffer.removeAt(0)
                }

                // Check if we should analyze the breathing pattern
                val currentTime = System.currentTimeMillis()

                // Log data collection progress
                if (breathingDataBuffer.size % 10 == 0) { // Log every 10 data points
                    Log.d("MainViewModel", "Collected ${breathingDataBuffer.size} data points")
                    Log.d(
                            "MainViewModel",
                            "Time since recording started: ${currentTime - recordingStartTime}ms"
                    )
                    Log.d("MainViewModel", "Analysis will start after: ${initialAnalysisDelay}ms")
                }

                // Only start analyzing if:
                // 1. We're actively recording
                // 2. We have enough data points
                // 3. We've waited the initial delay for first analysis OR we've already started
                // analyzing
                // 4. Enough time has passed since last analysis
                if (_isRecording.value &&
                                breathingDataBuffer.size >= minDataPointsForAnalysis &&
                                ((currentTime - lastAnalysisTime > analysisInterval &&
                                        analysisStarted) ||
                                        (!analysisStarted &&
                                                currentTime - recordingStartTime >
                                                        initialAnalysisDelay))
                ) {

                    if (!analysisStarted) {
                        Log.d(
                                "MainViewModel",
                                "----------------------------------------------------"
                        )
                        Log.d("MainViewModel", "STARTING BREATHING ANALYSIS AFTER INITIAL DELAY")
                        Log.d("MainViewModel", "Data points collected: ${breathingDataBuffer.size}")
                        Log.d(
                                "MainViewModel",
                                "Time elapsed: ${currentTime - recordingStartTime}ms"
                        )
                        Log.d(
                                "MainViewModel",
                                "----------------------------------------------------"
                        )
                        analysisStarted = true
                    } else {
                        Log.d(
                                "MainViewModel",
                                "Running periodic analysis - ${breathingDataBuffer.size} data points"
                        )
                    }

                    analyzeBreathingPattern()
                    lastAnalysisTime = currentTime
                }
            }
        }
    }

    /** Analyze breathing pattern using the trained model and recent data */
    private fun analyzeBreathingPattern() {
        viewModelScope.launch {
            try {
                Log.d("MainViewModel", "Starting breathing pattern analysis...")

                // Add a slight delay to slow down the classification process
                // This gives the UI time to show "Analyzing..." and feels more natural
                _breathingClassification.value = "Analyzing..."
                delay(1500) // Wait 1.5 seconds before continuing

                // Check for minimum data points (20 points for meaningful analysis)
                if (breathingDataBuffer.size < 20) {
                    _breathingClassification.value = "Analyzing..."
                    Log.d(
                            "MainViewModel",
                            "Not enough data points yet for analysis (${breathingDataBuffer.size}/20)"
                    )
                    return@launch
                }

                // Use last 30 seconds of data for analysis, or all available data if less
                val recentData =
                        if (breathingDataBuffer.size > 150) {
                            breathingDataBuffer.takeLast(150)
                        } else {
                            breathingDataBuffer
                        }

                // RELAXED DATA QUALITY CHECKS

                // DATA QUALITY CHECK 1: Count complete breathing cycles
                var cycleCount = 0
                var prevPhase = ""
                for (point in recentData) {
                    val phase = point.breathingPhase.lowercase()
                    // Count transitions from exhaling to inhaling as full cycles
                    if (prevPhase == "exhaling" && phase == "inhaling") {
                        cycleCount++
                    }
                    prevPhase = phase
                }

                // Need at least 2 complete breathing cycles for reliable analysis (was 3)
                if (cycleCount < 2) {
                    _breathingClassification.value = "Analyzing..."
                    Log.d(
                            "MainViewModel",
                            "Not enough complete breathing cycles yet ($cycleCount/2)"
                    )
                    return@launch
                }

                // DATA QUALITY CHECK 2: Check time span of data
                val firstTimestamp = recentData.first().timestamp
                val lastTimestamp = recentData.last().timestamp
                val dataSpanSeconds = (lastTimestamp - firstTimestamp) / 1000

                // Need at least 5 seconds of data for reliable analysis (was 10)
                if (dataSpanSeconds < 5) {
                    _breathingClassification.value = "Analyzing..."
                    Log.d(
                            "MainViewModel",
                            "Data collection time too short ($dataSpanSeconds/5 seconds)"
                    )
                    return@launch
                }

                // DATA QUALITY CHECK 3: Check for consistent QR tracking
                val phases = recentData.map { it.breathingPhase.lowercase() }
                val uniquePhases = phases.toSet()

                // Need both inhaling and exhaling phases for proper analysis
                if (!uniquePhases.contains("inhaling") || !uniquePhases.contains("exhaling")) {
                    _breathingClassification.value = "Analyzing..."
                    Log.d(
                            "MainViewModel",
                            "Missing breathing phases: Inhaling=${uniquePhases.contains("inhaling")}, Exhaling=${uniquePhases.contains("exhaling")}"
                    )
                    return@launch
                }

                // Extract features for classification
                val breathingRate = calculateBreathingRate()
                val irregularityIndex = calculateIrregularityIndex()
                val amplitudeVariation = calculateAmplitudeVariation()
                val avgVelocity = calculateAverageVelocity()

                // Log the features for debugging
                Log.d("MainViewModel", "----------------------------------------------------")
                Log.d("MainViewModel", "CALLING MODEL WITH THESE FEATURES:")
                Log.d("MainViewModel", "Breathing Rate: $breathingRate breaths/min")
                Log.d("MainViewModel", "Irregularity Index: $irregularityIndex")
                Log.d("MainViewModel", "Amplitude Variation: $amplitudeVariation")
                Log.d("MainViewModel", "Average Velocity: $avgVelocity")
                Log.d("MainViewModel", "----------------------------------------------------")

                // Get prediction from the trained model
                Log.d("MainViewModel", "Calling breathingClassifier.classifyBreathing()...")
                val classificationResult =
                        breathingClassifier?.classifyBreathing(
                                breathingRate,
                                irregularityIndex,
                                amplitudeVariation,
                                avgVelocity
                        )

                if (classificationResult != null) {
                    _breathingClassification.value = classificationResult.classification
                    _classificationConfidence.value = classificationResult.confidence

                    Log.d("MainViewModel", "----------------------------------------------------")
                    Log.d("MainViewModel", "CLASSIFICATION RESULT RECEIVED")
                    Log.d(
                            "MainViewModel",
                            "Breathing classified as: ${classificationResult.classification} with confidence ${classificationResult.confidence}"
                    )
                    Log.d("MainViewModel", "Model Used: ${getModelInfo()}")
                    Log.d("MainViewModel", "----------------------------------------------------")
                } else {
                    Log.e("MainViewModel", "ERROR: Classification result is null!")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error analyzing breathing pattern: ${e.message}")
                Log.e("MainViewModel", "Stack trace: ${e.stackTraceToString()}")
                _breathingClassification.value = "Error"
                _classificationConfidence.value = 0.0f
            }
        }
    }

    /** Force a breathing update (for testing) */
    fun forceBreathingUpdate() {
        viewModelScope.launch {
            // Toggle between inhaling and exhaling
            val currentPhase = _currentBreathingPhase.value
            _currentBreathingPhase.value =
                    when (currentPhase) {
                        "Inhaling" -> "Exhaling"
                        "Exhaling" -> "Inhaling"
                        else -> "Inhaling"
                    }

            _breathingConfidence.value = 0.95f
            _currentVelocity.value = if (_currentBreathingPhase.value == "Inhaling") -25f else 25f
        }
    }

    /** Save respiratory data */
    fun saveData() {
        viewModelScope.launch {
            // Data saving logic is handled in MainActivity
        }
    }

    /** Set the callback function to complete calibration in MainActivity */
    fun setCalibrationCompleter(completer: () -> Unit) {
        calibrationCompleter = completer
    }

    /** Force complete the calibration (can be triggered by UI) */
    fun forceCompleteCalibration() {
        viewModelScope.launch {
            // First update our own state
            _isCalibrating.value = false
            _uiState.value = UiState.CameraSetup

            // Then call the MainActivity completer if available
            calibrationCompleter?.invoke()
        }
    }

    /** Start disease detection process */
    fun startDiseaseDetection() {
        viewModelScope.launch {
            // Reset data
            breathingDataBuffer.clear()
            collectingQRDataForDisease = false

            // Update UI states
            _diseaseUiState.value = DiseaseUiState.Ready
            _uiState.value = UiState.DiseaseDetection
        }
    }

    /** Start QR data collection for disease detection */
    fun startDiseaseRecording() {
        viewModelScope.launch {
            // Clear any previous data
            breathingDataBuffer.clear()

            // Set flag to collect QR data for disease detection
            collectingQRDataForDisease = true
            qrDataCollectionStartTime = System.currentTimeMillis()

            // Update UI state
            _diseaseUiState.value = DiseaseUiState.Recording(remainingSeconds = 30)

            // Start countdown timer
            recordingJob = launch {
                for (i in 30 downTo 1) {
                    _diseaseUiState.value = DiseaseUiState.Recording(remainingSeconds = i)
                    delay(1000) // Wait 1 second
                }
                stopDiseaseDetection() // Auto-stop after 30 seconds
            }
        }
    }

    /** Stop disease detection recording and analyze */
    fun stopDiseaseDetection() {
        // Cancel timer if running
        recordingJob?.cancel()

        // Stop collecting QR data
        collectingQRDataForDisease = false

        // Update UI state
        _diseaseUiState.value = DiseaseUiState.Analyzing

        // Process data and analyze
        viewModelScope.launch {
            try {
                if (breathingDataBuffer.size >= 10) {
                    // Calculate breathing rate from QR data
                    val breathingRate = calculateBreathingRate()

                    // Calculate irregularity index from QR data
                    val irregularityIndex = calculateIrregularityIndex()

                    // Calculate other metrics from QR data
                    val amplitudeVariation = calculateAmplitudeVariation()
                    val avgVelocity = calculateAverageVelocity()

                    // Determine classification based on metrics
                    val classification =
                            classifyBreathingPattern(
                                    breathingRate,
                                    irregularityIndex,
                                    amplitudeVariation,
                                    avgVelocity
                            )

                    // Prepare recommendations based on classification
                    val recommendations =
                            generateRecommendations(
                                    classification,
                                    breathingRate,
                                    irregularityIndex
                            )

                    // Create diagnosis result
                    val result =
                            DiagnosisResult(
                                    classification = classification,
                                    confidence =
                                            calculateConfidence(breathingRate, irregularityIndex),
                                    breathingRate = breathingRate,
                                    irregularityIndex = irregularityIndex,
                                    recommendations = recommendations
                            )

                    // Update UI with result
                    _diseaseUiState.value = DiseaseUiState.Result(result)
                } else {
                    _diseaseUiState.value =
                            DiseaseUiState.Result(
                                    DiagnosisResult(
                                            classification = "Error",
                                            confidence = 0f,
                                            breathingRate = 0f,
                                            irregularityIndex = 0f,
                                            recommendations =
                                                    listOf(
                                                            "Not enough breathing data collected. Please try again and ensure the QR code is visible on your chest."
                                                    )
                                    )
                            )
                }
            } catch (e: Exception) {
                _diseaseUiState.value =
                        DiseaseUiState.Result(
                                DiagnosisResult(
                                        classification = "Error",
                                        confidence = 0f,
                                        breathingRate = 0f,
                                        irregularityIndex = 0f,
                                        recommendations =
                                                listOf("Error during analysis: ${e.message}")
                                )
                        )
            }
        }
    }

    /** Reset disease detection state */
    fun resetDiseaseDetection() {
        _diseaseUiState.value = DiseaseUiState.Ready
        breathingDataBuffer.clear()
        collectingQRDataForDisease = false
    }

    // Calculate breathing rate from collected QR tracking data
    private fun calculateBreathingRate(): Float {
        if (breathingDataBuffer.size < 10) {
            Log.d(
                    "BreathingRate",
                    "Not enough data points for calculation (${breathingDataBuffer.size})"
            )
            return 0f
        }

        // Count breathing cycles (inhale + exhale)
        var cycleCount = 0
        var prevPhase = ""

        // For debugging: count all phase changes
        var allPhaseChanges = 0
        var prevPhaseForAll = ""

        Log.d("BreathingRate", "========= BREATHING RATE CALCULATION =========")
        Log.d("BreathingRate", "Total data points: ${breathingDataBuffer.size}")
        Log.d(
                "BreathingRate",
                "First few phases: ${breathingDataBuffer.take(5).map { it.breathingPhase }}"
        )

        for (point in breathingDataBuffer) {
            val phase = point.breathingPhase.lowercase()

            // Log phase changes for debugging
            if (phase != prevPhaseForAll) {
                allPhaseChanges++
                prevPhaseForAll = phase
                Log.d(
                        "BreathingRate",
                        "Phase change #$allPhaseChanges: $prevPhaseForAll -> $phase at ${point.timestamp}"
                )
            }

            // Count transitions from exhaling to inhaling as full cycles
            if (prevPhase == "exhaling" && phase == "inhaling") {
                cycleCount++
                Log.d("BreathingRate", "FULL CYCLE DETECTED #$cycleCount at ${point.timestamp}")
            }

            prevPhase = phase
        }

        // Calculate duration in minutes
        val startTime = breathingDataBuffer.first().timestamp
        val endTime = breathingDataBuffer.last().timestamp
        val durationMs = endTime - startTime
        val durationSeconds = durationMs / 1000f
        val duration = durationMs / 60000f // Convert ms to minutes

        Log.d("BreathingRate", "Start time: $startTime, End time: $endTime")
        Log.d(
                "BreathingRate",
                "Duration: $durationMs ms ($durationSeconds seconds, $duration minutes)"
        )
        Log.d("BreathingRate", "Full cycles counted: $cycleCount")
        Log.d("BreathingRate", "All phase changes: $allPhaseChanges")

        // *** IMPROVED BREATHING RATE CALCULATION ***

        // Method 1: Direct calculation (original method)
        val directRate =
                if (duration > 0) {
                    cycleCount / duration
                } else {
                    0f
                }

        // Method 2: Calculate using time between cycles
        var cycleDurations = mutableListOf<Long>()
        var lastCycleTime = 0L
        prevPhase = ""

        for (point in breathingDataBuffer) {
            val phase = point.breathingPhase.lowercase()

            // Detect cycle completion
            if (prevPhase == "exhaling" && phase == "inhaling") {
                if (lastCycleTime > 0) {
                    cycleDurations.add(point.timestamp - lastCycleTime)
                }
                lastCycleTime = point.timestamp
            }

            prevPhase = phase
        }

        // Calculate average cycle time if we have at least 2 complete cycles
        val avgCycleTimeMethod =
                if (cycleDurations.isNotEmpty()) {
                    val avgCycleTimeMs = cycleDurations.average()
                    val avgCycleTimeMinutes = avgCycleTimeMs / 60000.0
                    (1.0 / avgCycleTimeMinutes).toFloat() // Convert to breaths per minute
                } else {
                    directRate // Fall back to direct method
                }

        // Method 3: Use alternate rate based on phase changes
        val alternateRate =
                if (duration > 0) {
                    (allPhaseChanges / 2) / duration
                } else {
                    0f
                }

        // Log all calculation methods
        Log.d("BreathingRate", "METHOD 1 (Direct): $directRate breaths/min")
        Log.d("BreathingRate", "METHOD 2 (Avg Cycle): $avgCycleTimeMethod breaths/min")
        Log.d("BreathingRate", "METHOD 3 (Phase Count): $alternateRate breaths/min")

        // Check if we have a reliable duration
        val hasReliableDuration = durationSeconds >= 15.0f // At least 15 seconds of data

        // Choose the best calculation method
        val rawRate =
                if (hasReliableDuration) {
                    // Enough data to trust the direct calculation
                    directRate
                } else if (cycleDurations.isNotEmpty()) {
                    // Not enough duration but we have multiple cycles to average
                    avgCycleTimeMethod
                } else {
                    // Last resort: use the alternate calculation
                    alternateRate
                }

        // Apply physiological constraints - normal human breathing is 8-30 breaths per minute
        // Values outside this range are almost certainly calculation errors
        val constrainedRate = rawRate.coerceIn(8.0f, 30.0f)

        Log.d("BreathingRate", "Raw breathing rate: $rawRate breaths/min")
        Log.d("BreathingRate", "FINAL CONSTRAINED RATE: $constrainedRate breaths/min")
        Log.d(
                "BreathingRate",
                "Method used: ${if (hasReliableDuration) "Direct calculation" else if (cycleDurations.isNotEmpty()) "Average cycle time" else "Phase count"}"
        )
        Log.d("BreathingRate", "============================================")

        // Return the constrained rate
        return constrainedRate
    }

    // Calculate irregularity index from QR data
    private fun calculateIrregularityIndex(): Float {
        if (breathingDataBuffer.size < 10) {
            return 0f
        }

        // Calculate standard deviation of time between breaths
        val breathTimes = mutableListOf<Long>()
        var lastPhaseChangeTime = breathingDataBuffer.first().timestamp
        var lastPhase = breathingDataBuffer.first().breathingPhase

        for (point in breathingDataBuffer.drop(1)) {
            if (point.breathingPhase != lastPhase) {
                // Phase changed
                breathTimes.add(point.timestamp - lastPhaseChangeTime)
                lastPhaseChangeTime = point.timestamp
                lastPhase = point.breathingPhase
            }
        }

        // Log the breath times for debugging
        Log.d("IrregularityIndex", "============= IRREGULARITY CALCULATION =============")
        Log.d("IrregularityIndex", "Number of breath phase changes: ${breathTimes.size}")
        if (breathTimes.isNotEmpty()) {
            Log.d("IrregularityIndex", "Min time between phases: ${breathTimes.minOrNull()} ms")
            Log.d("IrregularityIndex", "Max time between phases: ${breathTimes.maxOrNull()} ms")
            Log.d("IrregularityIndex", "First few intervals: ${breathTimes.take(5)}")
        }

        if (breathTimes.size < 3) {
            Log.d("IrregularityIndex", "Not enough phase changes to calculate irregularity")
            return 0f
        }

        // Remove outliers (values more than 3 standard deviations from mean)
        val initialMean = breathTimes.average()
        val initialStdDev =
                kotlin.math.sqrt(
                        breathTimes.map { (it - initialMean) * (it - initialMean) }.average()
                )
        val upperBound = initialMean + (3 * initialStdDev)

        val filteredBreathTimes = breathTimes.filter { it < upperBound }

        if (filteredBreathTimes.size < 3) {
            Log.d("IrregularityIndex", "Not enough valid intervals after filtering outliers")
            return 0.3f // Return a default moderate value
        }

        // Calculate mean
        val mean = filteredBreathTimes.average()

        // Safety check to avoid division by zero
        if (mean <= 0) {
            Log.d("IrregularityIndex", "Mean breathing interval is zero or negative: $mean")
            return 0.3f // Return a default moderate value
        }

        // Calculate variance
        val variance = filteredBreathTimes.map { (it - mean) * (it - mean) }.average()

        // Calculate standard deviation
        val stdDev = kotlin.math.sqrt(variance)

        // Calculate coefficient of variation (CV) - standard measure of variability
        val cv = stdDev / mean

        // Normalize to a 0-1 scale where 0 is perfectly regular and 1 is extremely irregular
        // Normal breathing has CV of 0.05-0.15, so scale accordingly
        val normalizedIrregularity = (cv / 0.5f).toFloat().coerceIn(0f, 1f)

        Log.d("IrregularityIndex", "Original breath changes: ${breathTimes.size}")
        Log.d("IrregularityIndex", "Filtered breath changes: ${filteredBreathTimes.size}")
        Log.d("IrregularityIndex", "Mean interval: $mean ms")
        Log.d("IrregularityIndex", "Standard deviation: $stdDev ms")
        Log.d("IrregularityIndex", "Coefficient of variation: $cv")
        Log.d("IrregularityIndex", "Final irregularity index: $normalizedIrregularity")
        Log.d("IrregularityIndex", "==================================================")

        return normalizedIrregularity
    }

    // Calculate amplitude variation (depth of breathing)
    private fun calculateAmplitudeVariation(): Float {
        if (breathingDataBuffer.size < 10) {
            return 0f
        }

        // Using velocity values as an indicator of amplitude
        val velocities = breathingDataBuffer.map { kotlin.math.abs(it.velocity) }

        // Find min and max
        val minVelocity = velocities.minOrNull() ?: 0f
        val maxVelocity = velocities.maxOrNull() ?: 0f

        // Calculate variation
        return maxVelocity - minVelocity
    }

    // Calculate average velocity
    private fun calculateAverageVelocity(): Float {
        if (breathingDataBuffer.size < 10) {
            return 0f
        }

        // Get absolute velocities
        val velocities = breathingDataBuffer.map { kotlin.math.abs(it.velocity) }

        // Calculate average
        return velocities.average().toFloat()
    }

    // Classify breathing pattern based on metrics
    private fun classifyBreathingPattern(
            breathingRate: Float,
            irregularityIndex: Float,
            amplitudeVariation: Float,
            avgVelocity: Float
    ): String {
        // Log all parameters used for classification
        Log.d("Classification", "====== CLASSIFICATION PARAMETERS ======")
        Log.d("Classification", "Breathing Rate: $breathingRate (normal: 8-24)")
        Log.d("Classification", "Irregularity Index: $irregularityIndex (normal: <0.5)")
        Log.d("Classification", "Amplitude Variation: $amplitudeVariation")
        Log.d("Classification", "Average Velocity: $avgVelocity")

        // Use more reasonable thresholds for normal breathing
        // Normal adult breathing rate is 8-24 breaths per minute
        val abnormalRate = breathingRate < 8 || breathingRate > 24

        // Higher threshold for irregularity to avoid false positives
        val highIrregularity = irregularityIndex > 0.5

        // Check for shallow breathing
        val shallowBreathing = amplitudeVariation < 5.0 && avgVelocity < 10.0

        // Count how many abnormal factors we have
        var abnormalFactors = 0
        if (abnormalRate) abnormalFactors++
        if (highIrregularity) abnormalFactors++
        if (shallowBreathing) abnormalFactors++

        // Only classify as abnormal if multiple factors are present
        val result =
                if (abnormalFactors >= 2) {
                    "Abnormal"
                } else {
                    "Normal"
                }

        Log.d("Classification", "Abnormal Rate: $abnormalRate")
        Log.d("Classification", "High Irregularity: $highIrregularity")
        Log.d("Classification", "Shallow Breathing: $shallowBreathing")
        Log.d("Classification", "Abnormal Factors: $abnormalFactors")
        Log.d("Classification", "CLASSIFICATION RESULT: $result")
        Log.d("Classification", "======================================")

        return result
    }

    // Calculate confidence based on available data
    private fun calculateConfidence(breathingRate: Float, irregularityIndex: Float): Float {
        // More data points = higher confidence
        val dataFactor = (breathingDataBuffer.size.coerceAtMost(100) / 100f)

        // Normal values = higher confidence
        val rateFactor = if (breathingRate in 12f..20f) 1f else 0.7f
        val regularityFactor = 1f - irregularityIndex

        return (dataFactor * rateFactor * regularityFactor).coerceIn(0.5f, 0.95f)
    }

    // Generate recommendations based on classification
    private fun generateRecommendations(
            classification: String,
            breathingRate: Float,
            irregularityIndex: Float
    ): List<String> {
        val recommendations = mutableListOf<String>()

        when (classification) {
            "Normal" -> {
                recommendations.add("Your breathing pattern appears normal.")
                recommendations.add(
                        "Continue to maintain good respiratory health through regular exercise and proper breathing techniques."
                )
            }
            "Abnormal" -> {
                recommendations.add("Your breathing pattern shows some irregularities.")

                if (breathingRate < 12) {
                    recommendations.add(
                            "Your breathing is slower than normal. This may indicate respiratory depression."
                    )
                } else if (breathingRate > 20) {
                    recommendations.add(
                            "Your breathing is faster than normal. This may indicate anxiety, asthma, or other respiratory issues."
                    )
                }

                if (irregularityIndex > 0.4) {
                    recommendations.add(
                            "Your breathing shows significant irregularity. This may indicate sleep apnea or other breathing disorders."
                    )
                }

                recommendations.add(
                        "Consider consulting a healthcare professional for a proper evaluation."
                )
            }
            else -> {
                recommendations.add("Unable to classify breathing pattern accurately.")
                recommendations.add("Please try again with proper QR code placement on your chest.")
            }
        }

        return recommendations
    }

    /** Toggle training data collection mode */
    fun toggleTrainingMode(enabled: Boolean) {
        viewModelScope.launch {
            _isTrainingMode.value = enabled
            Log.d("MainViewModel", "Training mode set to: ${_isTrainingMode.value}")
        }
    }

    // Function to start the camera
    fun startCamera() {
        _isCameraStarted.value = true
        Log.d("MainViewModel", "Camera started")
    }

    /** Returns information about which classification model is being used */
    fun getModelInfo(): String {
        return breathingClassifier?.getModelInfo() ?: "No model available"
    }

    /** Force a breathing analysis immediately, bypassing timing constraints */
    fun forceBreathingAnalysis() {
        viewModelScope.launch {
            if (_isRecording.value && breathingDataBuffer.size > 0) {
                Log.d("MainViewModel", "==================================================")
                Log.d("MainViewModel", "FORCING IMMEDIATE BREATHING ANALYSIS")
                Log.d("MainViewModel", "Current data points: ${breathingDataBuffer.size}")
                Log.d("MainViewModel", "==================================================")
                analyzeBreathingPattern()
                lastAnalysisTime = System.currentTimeMillis()
                analysisStarted = true
            } else {
                Log.d("MainViewModel", "Cannot force analysis - not recording or no data")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        diseaseClassifier = null
        breathingClassifier = null
    }
}
