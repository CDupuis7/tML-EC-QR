package com.example.tml_ec_qr_scan

// Add explicit imports for our models
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlin.math.pow
import kotlin.math.sqrt
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

/** Sealed class representing different UI states for the respiratory disease detection screen */
sealed class DiseaseUiState {
    /** Initial state before recording starts */
    object Ready : DiseaseUiState()

    /** Recording in progress with countdown timer */
    data class Recording(val remainingSeconds: Int) : DiseaseUiState()

    /** Processing and analyzing the recorded audio */
    object Analyzing : DiseaseUiState()

    /** Analysis complete with diagnosis result */
    data class Result(val diagnosisResult: DiagnosisResult) : DiseaseUiState()
}

/** Data class representing the result of respiratory disease analysis */
data class DiagnosisResult(
        val classification: String, // "Normal" or "Abnormal"
        val confidence: Float,
        val breathingRate: Float,
        val irregularityIndex: Float,
        val recommendations: List<String>,
        val detectedConditions: List<String> = emptyList(), // Specific conditions detected
        val amplitudeVariability: Float = 0f, // Amplitude variability metric
        val durationVariability: Float = 0f, // Duration variability metric
        val averageHealthData: HealthData = HealthData() // Session average health data
)

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

    // Health monitoring with Health Connect
    private val healthConnectManager by lazy { HealthConnectManager(getApplication()) }
    private val _currentHealthData = MutableStateFlow(HealthData())
    val currentHealthData: StateFlow<HealthData> = _currentHealthData.asStateFlow()

    private var healthDataCollectionJob: Job? = null
    private var sessionStartTime: Instant? = null

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

    // Breathing rate
    private val _breathingRate = MutableStateFlow<Float>(0.0f)
    val breathingRate: StateFlow<Float> = _breathingRate.asStateFlow()

    // Recording timer
    private val _recordingTimeRemaining = MutableStateFlow<Int>(30)
    val recordingTimeRemaining: StateFlow<Int> = _recordingTimeRemaining.asStateFlow()

    // Is timer active
    private val _isTimerActive = MutableStateFlow<Boolean>(false)
    val isTimerActive: StateFlow<Boolean> = _isTimerActive.asStateFlow()

    // Timer job
    private var timerJob: Job? = null

    // Recording duration in seconds
    private val _recordingDuration = MutableStateFlow<Int>(30)
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()

    // Breathing classifier for real-time analysis
    private var breathingClassifier: BreathingClassifier? = null

    // Tracking mode state
    private val _currentTrackingMode = MutableStateFlow(TrackingMode.QR_TRACKING)
    val currentTrackingMode: StateFlow<TrackingMode> = _currentTrackingMode.asStateFlow()

    // Track the last used tracking mode for "Record Again" functionality
    private val _lastUsedTrackingMode = MutableStateFlow(TrackingMode.QR_TRACKING)
    val lastUsedTrackingMode: StateFlow<TrackingMode> = _lastUsedTrackingMode.asStateFlow()

    // YOLO chest detection state
    private val _chestDetection = MutableStateFlow<YoloChestDetector.ChestDetection?>(null)
    val chestDetection: StateFlow<YoloChestDetector.ChestDetection?> = _chestDetection.asStateFlow()

    // Camera facing state for YOLO tracking
    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    // Store the last classification result for UI access
    private var lastClassificationResult: BreathingClassifier.ClassificationResultV2? = null

    /** Get the last classification result with detected conditions */
    fun getLastClassificationResult(): BreathingClassifier.ClassificationResultV2? {
        return lastClassificationResult
    }

    /** Update the last classification result */
    private fun updateLastClassificationResult(result: BreathingClassifier.ClassificationResultV2) {
        lastClassificationResult = result
        Log.d(
                "MainViewModel",
                "Updated classification result: ${result.classification}, conditions: ${result.detectedConditions}"
        )
    }

    init {
        // Initialize components for disease detection
        diseaseClassifier = DiseaseClassifier(getApplication())
        // Initialize breathing classifier
        breathingClassifier = BreathingClassifier(getApplication())
    }

    /** Proceed to camera setup state without starting recording */
    fun proceedToCameraSetup() {
        _uiState.value = UiState.CameraSetup
        // Start health data collection when patient proceeds to camera setup
        startHealthDataCollection()
    }

    /** Navigate back to the previous screen based on current state */
    fun navigateBack() {
        viewModelScope.launch {
            when (_uiState.value) {
                is UiState.Initial -> {
                    // Already at the first screen, no back navigation
                    Log.d("MainViewModel", "Already at initial screen, cannot go back")
                }
                is UiState.CameraSetup -> {
                    // Go back to patient information screen
                    Log.d("MainViewModel", "Navigating back to Initial screen")
                    _uiState.value = UiState.Initial
                }
                is UiState.Calibrating -> {
                    // Stop calibration and go back to camera setup
                    Log.d("MainViewModel", "Stopping calibration and returning to CameraSetup")
                    _isCalibrating.value = false
                    _uiState.value = UiState.CameraSetup
                    // Stop camera when manually returning to setup screen from calibration
                    stopCamera()
                    Log.d("MainViewModel", "🎥 Camera stopped after manual calibration exit")
                }
                is UiState.Recording -> {
                    // Stop recording and go back to camera setup
                    Log.d("MainViewModel", "Stopping recording and returning to CameraSetup")
                    if (_isRecording.value) {
                        // Cancel the timer job if recording
                        timerJob?.cancel()
                        timerJob = null
                        _isTimerActive.value = false
                        _isRecording.value = false
                    }
                    _readyToRecord.value = false
                    _uiState.value = UiState.CameraSetup
                    // Stop camera when returning to setup screen from recording
                    stopCamera()
                    Log.d("MainViewModel", "🎥 Camera stopped after recording exit")
                }
                is UiState.Results -> {
                    // Go back to camera setup for new recording
                    Log.d("MainViewModel", "Navigating back to CameraSetup from Results")
                    _uiState.value = UiState.CameraSetup
                    // Stop camera when returning to setup screen from results
                    stopCamera()
                    Log.d("MainViewModel", "🎥 Camera stopped after results exit")
                }
                is UiState.DiseaseDetection -> {
                    // Go back to camera setup
                    Log.d("MainViewModel", "Navigating back to CameraSetup from DiseaseDetection")
                    _uiState.value = UiState.CameraSetup
                    // Stop camera when returning to setup screen from disease detection
                    stopCamera()
                    Log.d("MainViewModel", "🎥 Camera stopped after disease detection exit")
                }
            }
        }
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
                // Stop camera when returning to setup screen after calibration
                stopCamera()
                Log.d("MainViewModel", "🎥 Camera stopped after calibration completion")
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
            _currentTrackingMode.value = TrackingMode.QR_TRACKING
            _lastUsedTrackingMode.value = TrackingMode.QR_TRACKING
            _readyToRecord.value = true
            _isRecording.value = false
            Log.d("MainViewModel", "Changing state to Recording (QR positioning mode)")
            _uiState.value = UiState.Recording
        }
    }

    /** Prepare for YOLO chest tracking mode */
    fun prepareForYoloTracking() {
        viewModelScope.launch {
            _currentTrackingMode.value = TrackingMode.YOLO_TRACKING
            _lastUsedTrackingMode.value = TrackingMode.YOLO_TRACKING
            _readyToRecord.value = true
            _isRecording.value = false
            Log.d("MainViewModel", "Changing state to Recording (YOLO chest tracking mode)")
            _uiState.value = UiState.Recording
        }
    }

    /**
     * Restart recording with the same tracking mode as the previous session This is used by the
     * "Record Again" button to maintain mode consistency
     */
    fun restartRecordingWithSameMode() {
        viewModelScope.launch {
            val lastMode = _lastUsedTrackingMode.value
            Log.d("MainViewModel", "🔄 Restarting recording with last used mode: $lastMode")

            // Clear previous session data
            clearSessionData()

            // Set the tracking mode to the last used mode
            _currentTrackingMode.value = lastMode
            _readyToRecord.value = true
            _isRecording.value = false

            when (lastMode) {
                TrackingMode.QR_TRACKING -> {
                    Log.d("MainViewModel", "🎯 Restarting QR tracking mode")
                    _uiState.value = UiState.Recording
                }
                TrackingMode.YOLO_TRACKING -> {
                    Log.d("MainViewModel", "🤖 Restarting YOLO tracking mode")
                    // Clear any previous chest detection
                    _chestDetection.value = null
                    _uiState.value = UiState.Recording
                }
            }
        }
    }

    /** Clear session data while preserving tracking mode preferences */
    private fun clearSessionData() {
        _respiratoryData.value = emptyList()
        _currentBreathingPhase.value = "Unknown"
        _breathingConfidence.value = 0.0f
        _currentVelocity.value = 0.0f
        _breathingClassification.value = "Unknown"
        _classificationConfidence.value = 0.0f
        _breathingRate.value = 0.0f
        breathingDataBuffer.clear()

        // Reset timer state
        _recordingTimeRemaining.value = _recordingDuration.value
        _isTimerActive.value = false

        Log.d("MainViewModel", "✅ Session data cleared for restart")
    }

    /** Start recording of respiratory data */
    fun startRecording() {
        viewModelScope.launch {
            // Cancel any existing timer
            timerJob?.cancel()

            // Reset recording start time
            recordingStartTime = System.currentTimeMillis()

            // Initialize recording state
            _isRecording.value = true
            _readyToRecord.value = true

            // Reset classification values
            _breathingClassification.value = "Waiting for recording to complete..."
            _classificationConfidence.value = 0.0f

            // Reset analysis tracking
            lastAnalysisTime = 0L
            analysisStarted = false

            // Clear existing data for a fresh start
            _respiratoryData.value = emptyList()
            breathingDataBuffer.clear()

            // Start the countdown timer with the custom duration
            val duration = _recordingDuration.value
            _recordingTimeRemaining.value = duration
            _isTimerActive.value = true

            Log.d("MainViewModel", "Starting recording with timer: $duration seconds")

            // Launch the timer job
            timerJob =
                    viewModelScope.launch {
                        while (_recordingTimeRemaining.value > 0 && _isRecording.value) {
                            delay(1000) // Wait one second
                            _recordingTimeRemaining.value -= 1

                            // Log every 5 seconds
                            if (_recordingTimeRemaining.value % 5 == 0 ||
                                            _recordingTimeRemaining.value <= 3
                            ) {
                                Log.d(
                                        "MainViewModel",
                                        "Recording time remaining: ${_recordingTimeRemaining.value} seconds, " +
                                                "Data points so far: ${breathingDataBuffer.size}"
                                )
                            }

                            // Once timer reaches zero, stop recording automatically
                            if (_recordingTimeRemaining.value <= 0) {
                                Log.d(
                                        "MainViewModel",
                                        "Recording timer completed, stopping recording"
                                )
                                stopRecording()
                                break
                            }
                        }
                    }
        }
    }

    /** Stop recording and show results */
    fun stopRecording() {
        viewModelScope.launch {
            // Cancel the timer job
            timerJob?.cancel()
            timerJob = null

            // Reset timer state
            _isTimerActive.value = false

            // Stop recording
            _isRecording.value = false
            _readyToRecord.value = false

            // Analyze breathing pattern after stopping recording
            if (breathingDataBuffer.isNotEmpty()) {
                Log.d("MainViewModel", "Recording stopped, analyzing breathing pattern...")
                try {
                    analyzeBreathingPattern()
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error during breathing analysis: ${e.message}")
                    _breathingClassification.value = "Analysis Error"
                    _classificationConfidence.value = 0.0f
                }
            } else {
                Log.w("MainViewModel", "No breathing data collected during recording")
                _breathingClassification.value = "No Data Collected"
                _classificationConfidence.value = 0.0f
            }

            // Always transition to Results screen regardless of analysis outcome
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

            // Clear health session data for new patient
            clearHealthSessionData()

            _uiState.value = UiState.Initial
        }
    }

    /** Update patient metadata */
    fun updatePatientMetadata(metadata: PatientMetadata) {
        viewModelScope.launch { _patientMetadata.value = metadata }
    }

    /** Update chest detection data for YOLO tracking */
    fun updateChestDetection(detection: YoloChestDetector.ChestDetection?) {
        _chestDetection.value = detection
    }

    /** Update breathing data with phase, confidence, and velocity */
    fun updateBreathingData(phase: Int, confidence: Float, velocity: Float) {
        viewModelScope.launch {
            _currentBreathingPhase.value =
                    when (phase) {
                        -1 -> "inhaling" // Changed to lowercase for consistency
                        1 -> "exhaling" // Changed to lowercase for consistency
                        0 -> "pause" // Changed to lowercase for consistency
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

    /** Update breathing phase directly with string (for YOLO tracking) */
    fun updateCurrentBreathingPhase(phase: String) {
        viewModelScope.launch {
            _currentBreathingPhase.value = phase
            Log.d("ViewModel", "Directly updating breathing phase to: $phase")
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

            // Enhanced logging for QR tracking
            Log.d(
                    "QRTracking",
                    "QR Data: phase=${dataPoint.breathingPhase}, amplitude=${dataPoint.amplitude}, velocity=${dataPoint.velocity}"
            )

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

                // Also collect data for disease detection if flag is set
                if (collectingQRDataForDisease) {
                    Log.d(
                            "DiseaseTracking",
                            "Added data point for disease detection: phase=${dataPoint.breathingPhase}"
                    )
                }

                // Log data collection progress
                if (breathingDataBuffer.size % 10 == 0) { // Log every 10 data points
                    Log.d("MainViewModel", "Collected ${breathingDataBuffer.size} data points")

                    // Count phases for debugging
                    val phaseCount =
                            breathingDataBuffer
                                    .groupingBy { it.breathingPhase.lowercase() }
                                    .eachCount()
                    Log.d("MainViewModel", "Phase distribution: $phaseCount")

                    Log.d(
                            "MainViewModel",
                            "Time since recording started: ${System.currentTimeMillis() - recordingStartTime}ms"
                    )
                }

                // No breathing pattern analysis during recording - will be done when recording
                // stops
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
                delay(1000) // Wait 1 second before continuing

                // Extract features for classification, regardless of data quality
                val breathingRate = calculateBreathingRate()
                val irregularityIndex = calculateIrregularityIndex()
                val amplitudeVariation = calculateAmplitudeVariation()
                val avgVelocity = calculateAverageVelocity()

                // Store breathing rate for display
                _breathingRate.value = breathingRate

                // Log the features for debugging
                Log.d("MainViewModel", "----------------------------------------------------")
                Log.d("MainViewModel", "CALLING MODEL WITH THESE FEATURES:")
                Log.d("MainViewModel", "Breathing Rate: $breathingRate breaths/min")
                Log.d("MainViewModel", "Irregularity Index: $irregularityIndex")
                Log.d("MainViewModel", "Amplitude Variation: $amplitudeVariation")
                Log.d("MainViewModel", "Average Velocity: $avgVelocity")
                Log.d("MainViewModel", "----------------------------------------------------")

                // Special handling for no breathing or extremely low breathing rate
                if (breathingRate == 0.0f) {
                    Log.d("MainViewModel", "DETECTED NO BREATHING (rate: $breathingRate)")
                    _breathingClassification.value = "No Breathing Detected"
                    _classificationConfidence.value = 0.95f
                    return@launch
                } else if (breathingRate < 1.0f) {
                    Log.d("MainViewModel", "DETECTED VERY LOW BREATHING (rate: $breathingRate)")
                    _breathingClassification.value = "Abnormal"
                    _classificationConfidence.value = 0.95f
                    return@launch
                }

                // Check if the breathing classifier is available
                if (breathingClassifier == null) {
                    Log.e("MainViewModel", "ERROR: Breathing classifier is null!")

                    // Using same logic as Python script (rate_status = "NORMAL" if 12 <=
                    // breathing_rate <= 20 else "ABNORMAL")
                    if (breathingRate >= 12f && breathingRate <= 20f) {
                        _breathingClassification.value = "Normal"
                        _classificationConfidence.value = 0.85f
                    } else {
                        _breathingClassification.value = "Abnormal"
                        _classificationConfidence.value = 0.85f
                    }
                    return@launch
                }

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

                    // Update the last classification result
                    updateLastClassificationResult(classificationResult)
                } else {
                    Log.e("MainViewModel", "ERROR: Classification result is null!")

                    // Using same logic as Python script but handling no breathing case
                    if (breathingRate == 0.0f) {
                        _breathingClassification.value = "No Breathing Detected"
                        _classificationConfidence.value = 0.95f
                    } else if (breathingRate < 1.0f) {
                        _breathingClassification.value = "Abnormal"
                        _classificationConfidence.value = 0.95f
                    } else if (breathingRate >= 12f && breathingRate <= 20f) {
                        _breathingClassification.value = "Normal"
                        _classificationConfidence.value = 0.85f
                    } else {
                        _breathingClassification.value = "Abnormal"
                        _classificationConfidence.value = 0.85f
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error analyzing breathing pattern: ${e.message}")
                Log.e("MainViewModel", "Stack trace: ${e.stackTraceToString()}")
                _breathingClassification.value = "Error"
                _classificationConfidence.value = 0.0f
            } finally {
                // Ensure we have some classification result no matter what
                if (_breathingClassification.value == "Analyzing..." ||
                                _breathingClassification.value == "Unknown"
                ) {
                    _breathingClassification.value = "Inconclusive"
                    _classificationConfidence.value = 0.5f
                }
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

            // Create enhanced BreathingMetrics with all data
            val metrics =
                    BreathingMetrics(
                            breathingRate = breathingRate.value,
                            averageAmplitude = calculateAverageAmplitude(),
                            maxAmplitude = breathingDataBuffer.maxOfOrNull { it.amplitude } ?: 0f,
                            minAmplitude = breathingDataBuffer.minOfOrNull { it.amplitude } ?: 0f,
                            breathCount = countBreathCycles()
                    )

            // Use direct classification logic instead of the problematic classifier
            // Calculate breathing metrics
            val currentBreathingRate = calculateBreathingRate()
            val irregularityIndex = calculateIrregularityIndex()
            val amplitudeVariation = calculateAmplitudeVariation()
            val durationVariability = calculateBreathingRhythmVariability()

            // Execute classification using Python logic directly
            val classification =
                    if (currentBreathingRate >= 12f && currentBreathingRate <= 20f) "Normal"
                    else "Abnormal"

            // Detect specific breathing conditions
            val detectedConditions =
                    classifyBreathingCondition(
                            currentBreathingRate,
                            irregularityIndex,
                            amplitudeVariation,
                            durationVariability
                    )

            // Create diagnosis result with detailed conditions
            val diagnosis =
                    DiagnosisResult(
                            classification = classification,
                            confidence = 0.9f,
                            breathingRate = currentBreathingRate,
                            irregularityIndex = irregularityIndex,
                            recommendations =
                                    generateDetailedRecommendations(
                                            classification,
                                            currentBreathingRate,
                                            irregularityIndex,
                                            detectedConditions
                                    ),
                            detectedConditions = detectedConditions,
                            amplitudeVariability = amplitudeVariation,
                            durationVariability = durationVariability,
                            averageHealthData = getSessionAverageHealthData()
                    )

            // Update UI state with the diagnosis
            _diseaseUiState.value = DiseaseUiState.Result(diagnosis)
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

        // Analyze collected data for disease detection
        analyzeDiseaseData()
    }

    /** Analyze collected data for disease detection */
    fun analyzeDiseaseData() {
        // Update UI state
        _diseaseUiState.value = DiseaseUiState.Analyzing

        // Analyze the collected data for disease detection
        viewModelScope.launch {
            try {
                Log.d(
                        "MainViewModel",
                        "Starting disease analysis with ${breathingDataBuffer.size} data points"
                )

                // Calculate breathing metrics
                val breathingRate = calculateBreathingRate()
                val irregularityIndex = calculateIrregularityIndex()
                val amplitudeVariation = calculateAmplitudeVariation()
                val durationVariability = calculateBreathingRhythmVariability()

                // Execute classification using Python logic directly instead of calling the
                // classifier
                // NORMAL if 12 <= breathing_rate <= 20 else ABNORMAL
                val classification =
                        if (breathingRate >= 12f && breathingRate <= 20f) "Normal" else "Abnormal"

                // Detect specific breathing conditions like in the Python script
                val detectedConditions =
                        classifyBreathingCondition(
                                breathingRate,
                                irregularityIndex,
                                amplitudeVariation,
                                durationVariability
                        )
                Log.d("Classification", "Detected conditions: $detectedConditions")

                // Create diagnosis result with detailed conditions
                val result =
                        DiagnosisResult(
                                classification = classification,
                                confidence = 0.9f,
                                breathingRate = breathingRate,
                                irregularityIndex = irregularityIndex,
                                recommendations =
                                        generateDetailedRecommendations(
                                                classification,
                                                breathingRate,
                                                irregularityIndex,
                                                detectedConditions
                                        ),
                                detectedConditions = detectedConditions,
                                amplitudeVariability = amplitudeVariation,
                                durationVariability = durationVariability,
                                averageHealthData = getSessionAverageHealthData()
                        )

                // Update UI with result
                _diseaseUiState.value = DiseaseUiState.Result(result)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error during disease analysis: ${e.message}")
                Log.e("MainViewModel", "Stack trace: ${e.stackTraceToString()}")
                _diseaseUiState.value =
                        DiseaseUiState.Result(
                                DiagnosisResult(
                                        classification = "Error",
                                        confidence = 0f,
                                        breathingRate = 0f,
                                        irregularityIndex = 0f,
                                        recommendations =
                                                listOf("Error during analysis: ${e.message}"),
                                        detectedConditions = emptyList(),
                                        amplitudeVariability = 0f,
                                        durationVariability = 0f,
                                        averageHealthData = HealthData()
                                )
                        )
            }
        }
    }

    // Classify specific breathing conditions (directly from Python script logic)
    private fun classifyBreathingCondition(
            breathingRate: Float,
            irregularityIndex: Float,
            amplitudeVariation: Float,
            durationVariability: Float
    ): List<String> {
        val conditions = mutableListOf<String>()

        // Log Input Parameters
        Log.i("MAIN_CONDITION_DETECTOR", "==== CLASSIFYING BREATHING CONDITIONS ====")
        Log.i("MAIN_CONDITION_DETECTOR", "Breathing Rate: $breathingRate breaths/min")
        Log.i("MAIN_CONDITION_DETECTOR", "Irregularity Index: $irregularityIndex")
        Log.i("MAIN_CONDITION_DETECTOR", "Amplitude Variation: $amplitudeVariation")
        Log.i("MAIN_CONDITION_DETECTOR", "Duration Variability: $durationVariability")

        // Check for exactly zero breathing rate (No breathing)
        if (breathingRate == 0.0f) {
            conditions.add("No breathing detected")
            Log.i("MAIN_CONDITION_DETECTOR", "DETECTED: No breathing (rate: $breathingRate)")
            return conditions // Return immediately as this is the primary condition
        }

        // Check for very low breathing rate (breath holding)
        if (breathingRate > 0f && breathingRate < 1.0f) {
            conditions.add("Breath holding detected")
            Log.i("MAIN_CONDITION_DETECTOR", "DETECTED: Breath holding (rate: $breathingRate)")
            return conditions // Return immediately as this is the primary condition
        }

        // Using EXACT same logic as Python script:
        // if breathing_rate < 12: print("→ Bradypnea detected (slow breathing)")
        // elif breathing_rate > 20: print("→ Tachypnea detected (rapid breathing)")
        if (breathingRate < 12f) {
            conditions.add("Bradypnea (slow breathing)")
            Log.i("MAIN_CONDITION_DETECTOR", "DETECTED: Bradypnea with rate $breathingRate")
        } else if (breathingRate > 20f) {
            conditions.add("Tachypnea (rapid breathing)")
            Log.i("MAIN_CONDITION_DETECTOR", "DETECTED: Tachypnea with rate $breathingRate")
        }

        // Other conditions similar to Python script
        if (amplitudeVariation > 0.3f) {
            conditions.add("High amplitude variability (irregular breathing depth)")
            Log.i(
                    "MAIN_CONDITION_DETECTOR",
                    "DETECTED: High amplitude variability ($amplitudeVariation)"
            )
        }

        if (durationVariability > 0.3f) {
            conditions.add("High timing variability (irregular breathing rhythm)")
            Log.i(
                    "MAIN_CONDITION_DETECTOR",
                    "DETECTED: High timing variability ($durationVariability)"
            )
        }

        if (irregularityIndex > 0.5f) {
            conditions.add("Irregular breathing pattern")
            Log.i(
                    "MAIN_CONDITION_DETECTOR",
                    "DETECTED: Irregular breathing pattern ($irregularityIndex)"
            )
        }

        // Log results
        Log.i("MAIN_CONDITION_DETECTOR", "FINAL CONDITIONS: $conditions")
        Log.i("MAIN_CONDITION_DETECTOR", "========================================")

        return conditions
    }

    // Generate detailed recommendations based on detected conditions
    private fun generateDetailedRecommendations(
            classification: String,
            breathingRate: Float,
            irregularityIndex: Float,
            detectedConditions: List<String>
    ): List<String> {
        val recommendations = mutableListOf<String>()

        // Special case for No Breathing Detected
        if (classification == "No Breathing Detected") {
            recommendations.add("No breathing was detected during the recording period.")
            recommendations.add("This could be due to:")
            recommendations.add("- Holding your breath during the recording")
            recommendations.add("- Sensor not properly detecting your breathing movements")
            recommendations.add("- Very shallow breathing that was below detection threshold")
            recommendations.add("Consider recording again while breathing normally.")
            return recommendations
        }

        // Overall classification
        recommendations.add("Your breathing shows signs of ${classification.lowercase()} patterns.")
        recommendations.add(
                "Breathing rate: ${breathingRate.toInt()} breaths/min (normal range: 12-20 breaths/min)."
        )

        // Specific condition recommendations
        for (condition in detectedConditions) {
            when {
                condition.contains("No breathing detected") -> {
                    recommendations.add("No breathing was detected during the recording period.")
                    recommendations.add(
                            "This could be due to holding your breath during recording or sensor error."
                    )
                    recommendations.add("Consider recording again while breathing normally.")
                }
                condition.contains("Breath holding") -> {
                    recommendations.add("You appear to be holding your breath during recording.")
                    recommendations.add(
                            "Please try recording again while breathing normally for accurate assessment."
                    )
                }
                condition.contains("Bradypnea") -> {
                    recommendations.add("You have bradypnea (abnormally slow breathing rate).")
                    recommendations.add(
                            "This can be associated with medication effects, neurological conditions, or sleep apnea."
                    )
                }
                condition.contains("Tachypnea") -> {
                    recommendations.add("You have tachypnea (abnormally rapid breathing rate).")
                    recommendations.add(
                            "This can be associated with anxiety, fever, respiratory infections, or cardiopulmonary issues."
                    )
                }
                condition.contains("amplitude variability") -> {
                    recommendations.add(
                            "Your breathing shows high amplitude variability (irregular breathing depth)."
                    )
                    recommendations.add(
                            "This can suggest respiratory muscle weakness or uneven airflow in the lungs."
                    )
                }
                condition.contains("timing variability") -> {
                    recommendations.add(
                            "Your breathing shows irregular rhythm with high timing variability."
                    )
                    recommendations.add(
                            "This can be associated with sleep-disordered breathing or respiratory control issues."
                    )
                }
            }
        }

        // If no specific conditions (but not "No Breathing" classification), provide normal
        // recommendation
        if (detectedConditions.isEmpty() && classification != "No Breathing Detected") {
            recommendations.add("Your breathing pattern appears normal.")
            recommendations.add(
                    "Continue to maintain good respiratory health through regular exercise and proper breathing techniques."
            )
        } else if (classification != "No Breathing Detected") {
            recommendations.add(
                    "Consider consulting a healthcare professional for proper evaluation of these findings."
            )
        }

        return recommendations
    }

    /** Reset disease detection state */
    fun resetDiseaseDetection() {
        _diseaseUiState.value = DiseaseUiState.Ready
        breathingDataBuffer.clear()
        collectingQRDataForDisease = false
    }

    // Calculate breathing rate from collected QR tracking data
    private fun calculateBreathingRate(): Float {
        // Even with minimal data, provide a reasonable estimate
        if (breathingDataBuffer.size < 10) {
            Log.d(
                    "BreathingRate",
                    "Limited data (${breathingDataBuffer.size} points), using default estimate"
            )
            return 16f // Middle of normal range (12-20)
        }

        // Count full breathing cycles (complete inhale->pause->exhale->pause sequence)
        var cycleCount = 0
        var inCycle = false
        var hasInhaled = false
        var hasExhaled = false

        Log.d("BreathingRate", "========= BREATHING RATE CALCULATION =========")
        Log.d("BreathingRate", "Total data points: ${breathingDataBuffer.size}")

        // Group data points into time windows to smooth out rapid fluctuations
        val timeWindowSize = 300 // 300ms windows
        val dataPoints = breathingDataBuffer.sortedBy { it.timestamp }
        val timeWindows = mutableListOf<Pair<Long, String>>()

        var currentWindowStart = dataPoints.firstOrNull()?.timestamp ?: 0L
        var currentWindowEnd = currentWindowStart + timeWindowSize
        var currentWindowPhases = mutableListOf<String>()

        // Group data into time windows and determine dominant phase per window
        for (point in dataPoints) {
            if (point.timestamp <= currentWindowEnd) {
                currentWindowPhases.add(point.breathingPhase.lowercase())
            } else {
                // Determine dominant phase for this window
                val dominantPhase =
                        currentWindowPhases.groupBy { it }.maxByOrNull { it.value.size }?.key
                                ?: "pause"

                timeWindows.add(Pair(currentWindowStart, dominantPhase))

                // Start new window
                currentWindowStart = point.timestamp
                currentWindowEnd = currentWindowStart + timeWindowSize
                currentWindowPhases = mutableListOf(point.breathingPhase.lowercase())
            }
        }

        // Add final window if not empty
        if (currentWindowPhases.isNotEmpty()) {
            val dominantPhase =
                    currentWindowPhases.groupBy { it }.maxByOrNull { it.value.size }?.key ?: "pause"
            timeWindows.add(Pair(currentWindowStart, dominantPhase))
        }

        // Check if there's any actual breathing (inhaling/exhaling phases)
        // Count how many of each phase we have
        val phaseDistribution = timeWindows.groupBy { it.second }.mapValues { it.value.size }
        val hasInhalingPhases = phaseDistribution["inhaling"] ?: 0 > 0
        val hasExhalingPhases = phaseDistribution["exhaling"] ?: 0 > 0

        // Count how many pause phases we have as a percentage
        val totalWindows = timeWindows.size
        val pausePhases = phaseDistribution["pause"] ?: 0
        val pausePercentage = if (totalWindows > 0) pausePhases.toFloat() / totalWindows else 0f

        Log.d("BreathingRate", "Phase distribution: $phaseDistribution")
        Log.d(
                "BreathingRate",
                "Pause percentage: $pausePercentage (${pausePhases}/${totalWindows})"
        )

        // If majority of phases are pause (>80%) and minimal inhaling/exhaling, report actual low
        // rate
        if (pausePercentage > 0.8f && (!hasInhalingPhases || !hasExhalingPhases)) {
            Log.d("BreathingRate", "DETECTED NO BREATHING: ${pausePercentage*100}% pauses")
            return 0f // Report 0 breaths per minute for no breathing
        }

        // Count cycles using the time windows (smoothed phases)
        var prevPhase = ""
        var inhaleSeen = false
        var exhaleSeen = false

        for ((timestamp, phase) in timeWindows) {
            // If we see inhale followed by exhale, count it as a cycle
            if (phase == "inhaling") {
                inhaleSeen = true
            } else if (phase == "exhaling" && inhaleSeen) {
                exhaleSeen = true
            }

            // When we see inhale again after having seen both inhale and exhale, count a cycle
            if (phase == "inhaling" && prevPhase != "inhaling" && inhaleSeen && exhaleSeen) {
                cycleCount++
                Log.d("BreathingRate", "FULL CYCLE DETECTED #$cycleCount at $timestamp")
                inhaleSeen = true // Reset exhale but keep inhale true
                exhaleSeen = false
            }

            prevPhase = phase
        }

        // Calculate duration in minutes (from first to last data point)
        val startTime = dataPoints.first().timestamp
        val endTime = dataPoints.last().timestamp
        val durationMs = endTime - startTime
        val durationMinutes = durationMs / 60000f // Convert ms to minutes

        Log.d("BreathingRate", "Start time: $startTime, End time: $endTime")
        Log.d("BreathingRate", "Duration: ${durationMs}ms (${durationMinutes} minutes)")
        Log.d("BreathingRate", "Cycles detected: $cycleCount")

        // If we didn't detect any cycles but have reasonable duration, estimate based on phases
        if (cycleCount == 0 && durationMinutes > 0) {
            // Count phase changes as a rough estimate
            var phaseChanges = 0
            for (i in 1 until timeWindows.size) {
                if (timeWindows[i].second != timeWindows[i - 1].second) {
                    phaseChanges++
                }
            }

            // Assume approximately 2 phase changes per cycle
            cycleCount = kotlin.math.max(1, phaseChanges / 2)
            Log.d(
                    "BreathingRate",
                    "No cycles detected, estimated $cycleCount cycles from $phaseChanges phase changes"
            )
        }

        // Ensure we have at least 1 cycle for calculation purposes
        cycleCount = kotlin.math.max(1, cycleCount)

        // Apply physiological constraints - normal human breathing is 8-30 breaths per minute
        val breathingRate = if (durationMinutes > 0) cycleCount / durationMinutes else 16f

        // If the rate seems implausible, use a default value
        val finalRate =
                if (breathingRate < 5f || breathingRate > 40f) {
                    Log.d(
                            "BreathingRate",
                            "Calculated rate ($breathingRate) outside physiological range, using default"
                    )
                    16f // Default to middle of normal range
                } else {
                    breathingRate
                }

        Log.d("BreathingRate", "Raw breathing rate: $breathingRate breaths/min")
        Log.d("BreathingRate", "FINAL RATE: $finalRate breaths/min")

        return finalRate
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
            return 0.3f // Return a default moderate value
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

    // FIXED: Calculate amplitude variation with realistic scaling for normal breathing
    private fun calculateAmplitudeVariation(): Float {
        if (breathingDataBuffer.size < 10) {
            return 0f
        }

        // Use the SAME amplitude values we fixed in MainActivity for consistency
        val amplitudes = breathingDataBuffer.map { it.amplitude }

        // Remove outliers first
        val sortedAmplitudes = amplitudes.sorted()
        val q1Index = (sortedAmplitudes.size * 0.25).toInt()
        val q3Index = (sortedAmplitudes.size * 0.75).toInt()
        val filteredAmplitudes = sortedAmplitudes.subList(q1Index, q3Index)

        if (filteredAmplitudes.size < 3) {
            return 5f // Return low variation for small datasets
        }

        val meanAmplitude = filteredAmplitudes.average().toFloat()
        val variance =
                filteredAmplitudes
                        .map { (it - meanAmplitude) * (it - meanAmplitude) }
                        .average()
                        .toFloat()
        val standardDeviation = kotlin.math.sqrt(variance)

        // FIXED: Return normalized coefficient of variation as percentage (0-100 scale)
        // Normal breathing should have CV of 10-30%, abnormal is >50%
        val coefficientOfVariation =
                if (meanAmplitude > 0f) {
                    (standardDeviation / meanAmplitude) * 100f
                } else {
                    10f // Default low variation
                }

        // Cap at realistic maximum and ensure minimum variation
        val result = coefficientOfVariation.coerceIn(5f, 80f)

        Log.d("AmplitudeVariation", "=== FIXED AMPLITUDE VARIATION CALCULATION ===")
        Log.d("AmplitudeVariation", "Original data points: ${breathingDataBuffer.size}")
        Log.d("AmplitudeVariation", "Filtered amplitudes: ${filteredAmplitudes.size}")
        Log.d("AmplitudeVariation", "Mean amplitude: ${String.format("%.2f", meanAmplitude)}")
        Log.d(
                "AmplitudeVariation",
                "Standard deviation: ${String.format("%.2f", standardDeviation)}"
        )
        Log.d("AmplitudeVariation", "Raw CV: ${String.format("%.2f", coefficientOfVariation)}%")
        Log.d("AmplitudeVariation", "Final amplitude variation: ${String.format("%.2f", result)}%")
        Log.d("AmplitudeVariation", "Normal range: 10-30%, Abnormal: >50%")

        return result
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

        // Handle cases with estimated data
        if (breathingDataBuffer.size < 20) {
            Log.d(
                    "Classification",
                    "Limited data available (${breathingDataBuffer.size} points) - using simpler classification"
            )

            // With limited data, use primarily rate-based classification
            val result = if (breathingRate in 8f..24f) "Normal" else "Abnormal"
            Log.d(
                    "Classification",
                    "Simple classification result: $result (based primarily on rate)"
            )
            return result
        }

        // Use more reasonable thresholds for normal breathing
        // Normal adult breathing rate is 8-24 breaths per minute
        val abnormalRate = breathingRate < 8 || breathingRate > 24

        // Higher threshold for irregularity to avoid false positives
        val highIrregularity = irregularityIndex > 0.6 // Increased from 0.5 to be more lenient

        // Check for shallow breathing
        val shallowBreathing =
                amplitudeVariation < 3.0 && avgVelocity < 8.0 // More lenient thresholds

        // Count how many abnormal factors we have
        var abnormalFactors = 0
        if (abnormalRate) abnormalFactors++
        if (highIrregularity) abnormalFactors++
        if (shallowBreathing) abnormalFactors++

        // Only classify as abnormal if multiple factors are present
        // Modified to require more evidence to classify as abnormal
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

        // Determine specific condition
        val condition =
                when {
                    breathingRate < 12 -> "bradypnea (slow breathing)"
                    breathingRate > 20 -> "tachypnea (rapid breathing)"
                    else -> "normal breathing rate"
                }

        when (classification) {
            "Normal" -> {
                recommendations.add("Your breathing pattern is classified as normal.")
                recommendations.add(
                        "Your breathing rate is ${breathingRate.toInt()} breaths/minute, which is in the normal range."
                )
                recommendations.add(
                        "Continue to maintain good respiratory health through regular exercise and proper breathing techniques."
                )
            }
            "Abnormal" -> {
                recommendations.add("Your breathing pattern shows some irregularities.")

                if (breathingRate < 12) {
                    recommendations.add(
                            "You have bradypnea (slow breathing) with a rate of ${breathingRate.toInt()} breaths/minute."
                    )
                    recommendations.add(
                            "This can be caused by medication effects, metabolic disorders, or certain neurological conditions."
                    )
                } else if (breathingRate > 20) {
                    recommendations.add(
                            "You have tachypnea (rapid breathing) with a rate of ${breathingRate.toInt()} breaths/minute."
                    )
                    recommendations.add(
                            "This can be associated with anxiety, fever, respiratory infections, or cardiopulmonary issues."
                    )
                }

                if (irregularityIndex > 0.5) {
                    recommendations.add(
                            "Your breathing shows significant irregularity (irregularity index: ${irregularityIndex.toInt()}%)."
                    )
                    recommendations.add(
                            "This may indicate respiratory dysfunction or possible sleep-related breathing disorders."
                    )
                }

                recommendations.add(
                        "Consider consulting a healthcare professional for a proper evaluation."
                )
            }
            else -> {
                recommendations.add("Your breathing pattern is ${condition}.")
                recommendations.add("Rate: ${breathingRate.toInt()} breaths/minute")
                recommendations.add("Irregularity: ${(irregularityIndex * 100).toInt()}%")
                if (classification != "Normal" && classification != "Abnormal") {
                    recommendations.add(
                            "Please consult with a healthcare provider for further assessment."
                    )
                }
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
        val wasStarted = _isCameraStarted.value
        _isCameraStarted.value = true
        Log.d(
                "MainViewModel",
                "🎥 startCamera() called - was started: $wasStarted, now started: ${_isCameraStarted.value}"
        )
        if (!wasStarted) {
            Log.d("MainViewModel", "🎥 Camera state changed from false to true")
        } else {
            Log.d("MainViewModel", "🎥 Camera was already started")
        }
    }

    // Function to stop the camera
    fun stopCamera() {
        val wasStarted = _isCameraStarted.value
        _isCameraStarted.value = false
        Log.d(
                "MainViewModel",
                "🎥 stopCamera() called - was started: $wasStarted, now started: ${_isCameraStarted.value}"
        )
        if (wasStarted) {
            Log.d("MainViewModel", "🎥 Camera state changed from true to false")
        } else {
            Log.d("MainViewModel", "🎥 Camera was already stopped")
        }
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

    // Add function to calculate breathing rhythm variability
    private fun calculateBreathingRhythmVariability(): Float {
        if (breathingDataBuffer.size < 10) return 0f

        // Find phase transitions (points where breathing phase changes)
        val phases = breathingDataBuffer.map { it.breathingPhase.lowercase() }
        val transitions = mutableListOf<Long>()

        for (i in 1 until breathingDataBuffer.size) {
            if (phases[i] != phases[i - 1]) {
                transitions.add(breathingDataBuffer[i].timestamp)
            }
        }

        // Calculate intervals between transitions
        if (transitions.size < 3) return 0f

        val intervals = mutableListOf<Long>()
        for (i in 1 until transitions.size) {
            intervals.add(transitions[i] - transitions[i - 1])
        }

        // Calculate coefficient of variation (standard deviation / mean)
        val mean = intervals.average()
        val sumSquaredDiff = intervals.sumOf { (it - mean).pow(2) }
        val stdDev = sqrt(sumSquaredDiff / intervals.size)

        return (stdDev / mean).toFloat()
    }

    // Calculate average amplitude of breathing
    private fun calculateAverageAmplitude(): Float {
        if (breathingDataBuffer.size < 10) {
            return 0f
        }

        // Use amplitude values directly
        val amplitudes = breathingDataBuffer.map { it.amplitude }

        // Calculate average
        return amplitudes.average().toFloat()
    }

    // Count total breath cycles
    private fun countBreathCycles(): Int {
        if (breathingDataBuffer.size < 10) { // Reduced from 20 to 10
            Log.d(
                    "MainViewModel",
                    "Not enough data points for cycle counting: ${breathingDataBuffer.size} < 10"
            )
            return 0
        }

        // Count transitions from exhaling to inhaling as full cycles
        var cycleCount = 0
        var prevPhase = ""
        val phases = mutableListOf<String>()

        for (point in breathingDataBuffer) {
            val phase = point.breathingPhase.lowercase()
            phases.add(phase)
            if (prevPhase == "exhaling" && phase == "inhaling") {
                cycleCount++
            }
            prevPhase = phase
        }

        // Debug - log unique phases detected
        val uniquePhases = phases.toSet()
        Log.d("MainViewModel", "Detected breathing phases: ${uniquePhases.joinToString()}")

        // If we have enough data points but no cycles detected,
        // check if we at least have both inhale and exhale phases
        if (cycleCount == 0 && breathingDataBuffer.size >= 20) {
            if (uniquePhases.contains("inhaling") && uniquePhases.contains("exhaling")) {
                Log.d(
                        "MainViewModel",
                        "No complete cycles but both phases detected - returning 1 cycle"
                )
                return 1 // Return at least 1 cycle to allow processing
            }
        }

        Log.d(
                "MainViewModel",
                "Detected $cycleCount breath cycles in ${breathingDataBuffer.size} data points"
        )
        return cycleCount
    }

    /** Toggle between front and back camera for YOLO tracking */
    fun toggleCamera() {
        viewModelScope.launch {
            _isFrontCamera.value = !_isFrontCamera.value
            Log.d(
                    "MainViewModel",
                    "Camera switched to: ${if (_isFrontCamera.value) "Front" else "Back"}"
            )
        }
    }

    // ========== Health Data Collection Methods ==========

    /**
     * Start health data collection from Health Connect Called when patient proceeds to camera setup
     */
    private fun startHealthDataCollection() {
        viewModelScope.launch {
            try {
                // Check if Health Connect is available and we have permissions
                if (!healthConnectManager.isAvailable()) {
                    Log.w("HealthData", "Health Connect not available on this device")
                    return@launch
                }

                if (!healthConnectManager.hasPermissions()) {
                    Log.w("HealthData", "Health Connect permissions not granted")
                    // You could request permissions here if needed
                    return@launch
                }

                // Cancel any existing collection
                healthDataCollectionJob?.cancel()

                // Record session start time
                sessionStartTime = Instant.now()

                // Start collecting health data
                healthDataCollectionJob =
                        viewModelScope.launch {
                            healthConnectManager.getHealthDataFlow().collect { healthData ->
                                _currentHealthData.value = healthData
                                Log.d(
                                        "HealthData",
                                        "Updated health data: HR=${healthData.getHeartRateDisplay()}, SpO₂=${healthData.getSpO2Display()}"
                                )
                            }
                        }

                Log.d("HealthData", "Health data collection started")
            } catch (e: Exception) {
                Log.e("HealthData", "Error starting health data collection", e)
            }
        }
    }

    /** Stop health data collection */
    private fun stopHealthDataCollection() {
        healthDataCollectionJob?.cancel()
        healthDataCollectionJob = null
        Log.d("HealthData", "Health data collection stopped")
    }

    /** Get session average health data for results display */
    fun getSessionAverageHealthData(): HealthData {
        return try {
            val sessionStart = sessionStartTime ?: return HealthData()
            val sessionEnd = Instant.now()

            // This would get actual session data in a real implementation
            // For now, return the current average from the manager
            healthConnectManager.getSessionAverageHealthData()
        } catch (e: Exception) {
            Log.e("HealthData", "Error getting session average health data", e)
            HealthData()
        }
    }

    /** Clear health session data when starting a new patient */
    private fun clearHealthSessionData() {
        healthConnectManager.clearSessionData()
        sessionStartTime = null
        _currentHealthData.value = HealthData()
    }

    override fun onCleared() {
        super.onCleared()
        stopHealthDataCollection()
        diseaseClassifier = null
        breathingClassifier = null
    }
}
