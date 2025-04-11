package com.example.tml_ec_qr_scan

// import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// CompositionLocal to provide ViewModel access in nested composables
val LocalViewModel = staticCompositionLocalOf<MainViewModel> { error("No ViewModel provided") }

@Composable
fun MainScreen(
        viewModel: MainViewModel,
        onStartRecording: () -> Unit,
        onStopRecording: () -> Unit,
        onForceBreathingUpdate: () -> Unit,
        onSaveData: () -> Unit,
        onNewPatient: () -> Unit,
        onStartCalibration: () -> Unit,
        onToggleTrainingMode: () -> Unit,
        onSaveGraph: () -> Unit = {},
        previewView: @Composable () -> Unit = {}
) {
        val uiState by viewModel.uiState.collectAsState()
        val isRecording by viewModel.isRecording.collectAsState()
        val readyToRecord by viewModel.readyToRecord.collectAsState()
        val isCalibrating by viewModel.isCalibrating.collectAsState()
        val respiratoryData by viewModel.respiratoryData.collectAsState()
        val breathingPhase by viewModel.currentBreathingPhase.collectAsState()
        val confidence by viewModel.breathingConfidence.collectAsState()
        val velocity by viewModel.currentVelocity.collectAsState()
        val patientMetadata by viewModel.patientMetadata.collectAsState()
        val isCameraStarted by viewModel.isCameraStarted.collectAsState()

        // Provide ViewModel to nested composables
        CompositionLocalProvider(LocalViewModel provides viewModel) {
                Column(modifier = Modifier.fillMaxSize()) {
                        // Show camera preview in CameraSetup, Calibrating, and Recording states
                        if ((uiState is UiState.CameraSetup && isCameraStarted) ||
                                        uiState is UiState.Calibrating ||
                                        uiState is UiState.Recording
                        ) {
                                Box(
                                        modifier =
                                                Modifier.weight(1f)
                                                        .background(
                                                                Color.Black
                                                        ) // Set background to black
                                ) { previewView() }
                        }

                        // Content area
                        Box(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .padding(
                                                        bottom = 24.dp
                                                ) // Fixed bottom padding for navigation bar
                        ) {
                                when (uiState) {
                                        is UiState.Initial -> {
                                                InitialScreen(
                                                        patientMetadata = patientMetadata,
                                                        onUpdatePatientMetadata = { metadata ->
                                                                viewModel.updatePatientMetadata(
                                                                        metadata
                                                                )
                                                        },
                                                        onProceedToCameraSetup = {
                                                                viewModel.proceedToCameraSetup()
                                                        }
                                                )
                                        }
                                        is UiState.CameraSetup -> {
                                                CameraSetupScreen(
                                                        viewModel = viewModel,
                                                        patientMetadata = patientMetadata,
                                                        onStartRecording = {
                                                                viewModel.prepareForRecording()
                                                        },
                                                        onStartCalibration = onStartCalibration,
                                                        onNewPatient = onNewPatient,
                                                        onToggleTrainingMode = onToggleTrainingMode,
                                                        onStartDiseaseDetection = {
                                                                viewModel.startDiseaseDetection()
                                                        }
                                                )
                                        }
                                        is UiState.Calibrating -> {
                                                CalibratingScreen(
                                                        patientMetadata = patientMetadata,
                                                        onForceComplete = {
                                                                viewModel.forceCompleteCalibration()
                                                        }
                                                )
                                        }
                                        is UiState.Recording -> {
                                                RecordingScreen(
                                                        isRecording = isRecording,
                                                        readyToRecord = readyToRecord,
                                                        breathingPhase = breathingPhase,
                                                        confidence = confidence,
                                                        velocity = velocity,
                                                        respiratoryData = respiratoryData,
                                                        patientMetadata = patientMetadata,
                                                        onStartRecording = {
                                                                viewModel.startRecording()
                                                        },
                                                        onStopRecording = onStopRecording,
                                                        onForceBreathingUpdate =
                                                                onForceBreathingUpdate,
                                                        onNewPatient = onNewPatient
                                                )
                                        }
                                        is UiState.Results -> {
                                                ResultsScreen(
                                                        respiratoryData = respiratoryData,
                                                        patientMetadata = patientMetadata,
                                                        onStartRecording = {
                                                                viewModel.prepareForRecording()
                                                        },
                                                        onSaveData = onSaveData,
                                                        onNewPatient = onNewPatient,
                                                        onSaveGraph = onSaveGraph,
                                                        onReturnToCameraSetup = {
                                                                viewModel.proceedToCameraSetup()
                                                        }
                                                )
                                        }
                                        is UiState.DiseaseDetection -> {
                                                DiseaseDetectionScreen(
                                                        viewModel = viewModel,
                                                        onBackToMain = {
                                                                viewModel.proceedToCameraSetup()
                                                        }
                                                )
                                        }
                                }
                        }
                }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitialScreen(
        patientMetadata: PatientMetadata?,
        onUpdatePatientMetadata: (PatientMetadata) -> Unit,
        onProceedToCameraSetup: () -> Unit
) {
        var patientId by remember { mutableStateOf(patientMetadata?.id ?: "") }
        var age by remember { mutableStateOf(patientMetadata?.age?.toString() ?: "") }
        var gender by remember { mutableStateOf(patientMetadata?.gender ?: "") }
        var healthCondition by remember { mutableStateOf(patientMetadata?.healthStatus ?: "") }

        // Define dropdown options
        val genderOptions = listOf("Male", "Female", "Other")
        val healthConditionOptions =
                listOf("Healthy", "Asthmatic", "COPD", "Respiratory Infection", "Other")

        // State for dropdown expanded status
        var genderExpanded by remember { mutableStateOf(false) }
        var healthConditionExpanded by remember { mutableStateOf(false) }

        Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
                Text(
                        text = "Patient Information",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFF2196F3)
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                        value = patientId,
                        onValueChange = { patientId = it },
                        label = { Text("Patient ID") },
                        modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                        value = age,
                        onValueChange = {
                                // Only allow numeric input for age
                                if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                        age = it
                                }
                        },
                        label = { Text("Age") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                // Gender dropdown
                Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                                value = gender,
                                onValueChange = { /* Disabled direct editing */},
                                label = { Text("Gender") },
                                modifier = Modifier.fillMaxWidth(),
                                readOnly = true,
                                trailingIcon = {
                                        IconButton(onClick = { genderExpanded = true }) {
                                                Icon(Icons.Default.ArrowDropDown, "Dropdown")
                                        }
                                }
                        )

                        // Invisible clickable box that triggers the dropdown
                        Box(
                                modifier =
                                        Modifier.matchParentSize().clickable {
                                                genderExpanded = true
                                        }
                        )

                        DropdownMenu(
                                expanded = genderExpanded,
                                onDismissRequest = { genderExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                                genderOptions.forEach { option ->
                                        DropdownMenuItem(
                                                text = { Text(option) },
                                                onClick = {
                                                        gender = option
                                                        genderExpanded = false
                                                }
                                        )
                                }
                        }
                }

                // Health condition dropdown
                Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                                value = healthCondition,
                                onValueChange = { /* Disabled direct editing */},
                                label = { Text("Health Condition") },
                                modifier = Modifier.fillMaxWidth(),
                                readOnly = true,
                                trailingIcon = {
                                        IconButton(onClick = { healthConditionExpanded = true }) {
                                                Icon(Icons.Default.ArrowDropDown, "Dropdown")
                                        }
                                }
                        )

                        // Invisible clickable box that triggers the dropdown
                        Box(
                                modifier =
                                        Modifier.matchParentSize().clickable {
                                                healthConditionExpanded = true
                                        }
                        )

                        DropdownMenu(
                                expanded = healthConditionExpanded,
                                onDismissRequest = { healthConditionExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                                healthConditionOptions.forEach { option ->
                                        DropdownMenuItem(
                                                text = { Text(option) },
                                                onClick = {
                                                        healthCondition = option
                                                        healthConditionExpanded = false
                                                }
                                        )
                                }
                        }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                        onClick = {
                                // Save patient metadata before proceeding
                                val numAge = age.toIntOrNull() ?: 0
                                val metadata =
                                        PatientMetadata(
                                                id = patientId,
                                                age = numAge,
                                                gender = gender,
                                                healthStatus = healthCondition
                                        )
                                onUpdatePatientMetadata(metadata)
                                onProceedToCameraSetup()
                        },
                        enabled = patientId.isNotBlank() && age.isNotBlank() && gender.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2196F3),
                                        disabledContainerColor = Color(0xFFBDBDBD)
                                )
                ) { Text("Proceed to Camera Setup") }
        }
}

@Composable
fun CameraSetupScreen(
        viewModel: MainViewModel,
        patientMetadata: PatientMetadata?,
        onStartRecording: () -> Unit,
        onStartCalibration: () -> Unit,
        onNewPatient: () -> Unit,
        onToggleTrainingMode: () -> Unit,
        onStartDiseaseDetection: () -> Unit = {}
) {
        // Get camera started state from ViewModel
        val isCameraStarted by viewModel.isCameraStarted.collectAsState()

        Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
                Text(
                        text = "QR Respiratory Tracking",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                )

                // Display patient info
                if (patientMetadata != null) {
                        Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor =
                                                        MaterialTheme.colorScheme.surfaceVariant
                                        )
                        ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                                "Patient: ${patientMetadata.id}",
                                                style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                                "Age: ${patientMetadata.age}, Gender: ${patientMetadata.gender}",
                                                style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                                "Health status: ${patientMetadata.healthStatus}",
                                                style = MaterialTheme.typography.bodyMedium
                                        )
                                }
                        }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Add Feature explanation card
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                        "App Features",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Camera explanation
                                Text(
                                        "• Start Camera: Initialize the camera to position the QR code",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                )

                                // Calibration explanation
                                Text(
                                        "• Start Calibration: Calibrates the QR tracking system to optimize detection",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                )

                                // QR Tracking explanation
                                Text(
                                        "• Start QR Tracking: Track chest movement to record respiratory data and classify breathing patterns (normal vs. abnormal)",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                )
                        }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Add Start Camera button
                Button(
                        onClick = { viewModel.startCamera() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) { Text(if (isCameraStarted) "Camera Started" else "Start Camera") }

                // Buttons
                Button(
                        onClick = { onStartCalibration() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("Start Calibration") }

                Button(
                        onClick = { onStartRecording() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) { Text("Start QR Tracking") }

                Button(
                        onClick = { onNewPatient() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                )
                ) { Text("New Patient") }

                // Training mode toggle
                Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                        Text("Training Data Mode")
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                                checked = viewModel.isTrainingMode.collectAsState().value,
                                onCheckedChange = { onToggleTrainingMode() }
                        )
                }
        }
}

@Composable
fun CalibratingScreen(patientMetadata: PatientMetadata?, onForceComplete: () -> Unit) {
        var elapsedTime by remember { mutableStateOf(0L) }
        var showCompleteButton by remember { mutableStateOf(false) }

        // Update elapsed time
        LaunchedEffect(Unit) {
                val startTime = System.currentTimeMillis()
                while (true) {
                        delay(100)
                        val currentTime = System.currentTimeMillis()
                        elapsedTime = currentTime - startTime

                        // After 10 seconds, show the complete button
                        if (elapsedTime >= 10000 && !showCompleteButton) {
                                showCompleteButton = true
                        }
                }
        }

        Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
        ) {
                Text(
                        text = "Calibration in Progress",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFFFF9800) // Orange
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (patientMetadata != null) {
                        Text(
                                text = "Patient ID: ${patientMetadata.id}",
                                style = MaterialTheme.typography.titleMedium
                        )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Instructions and progress
                Text(
                        text =
                                if (elapsedTime < 10000)
                                        "Please breathe normally for ${10 - (elapsedTime / 1000).toInt()} seconds"
                                else "Calibration time complete",
                        style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                        text = "Collecting data to optimize detection...",
                        style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Simple loading indicator
                CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        color = Color(0xFFFF9800) // Orange
                )

                // Show complete button after 10 seconds
                if (showCompleteButton) {
                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                                onClick = onForceComplete,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFFF5722) // Deep Orange
                                        )
                        ) { Text("Complete Calibration Now") }
                }
        }
}

@Composable
fun RecordingScreen(
        isRecording: Boolean,
        readyToRecord: Boolean,
        breathingPhase: String,
        confidence: Float,
        velocity: Float,
        respiratoryData: List<RespiratoryDataPoint>,
        patientMetadata: PatientMetadata?,
        onStartRecording: () -> Unit,
        onStopRecording: () -> Unit,
        onForceBreathingUpdate: () -> Unit,
        onNewPatient: () -> Unit
) {
        // Access the ViewModel from the CompositionLocal
        val viewModel = LocalViewModel.current

        // Access the breathing classification state
        val breathingClassification = viewModel.breathingClassification.collectAsState().value
        val classificationConfidence = viewModel.classificationConfidence.collectAsState().value

        Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                // Display patient info
                patientMetadata?.let {
                        Text(
                                text = "Patient: ${it.id}",
                                style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                                text = "Age: ${it.age}, Gender: ${it.gender}",
                                style = MaterialTheme.typography.bodyMedium
                        )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // QR positioning instructions
                if (!isRecording && readyToRecord) {
                        Text(
                                text = "Position QR Code in the frame",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFF2196F3)
                        )

                        // Start Recording button
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                                onClick = onStartRecording,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF4CAF50)
                                        )
                        ) { Text(text = "Start Recording") }
                }

                // If recording, show breathing info
                if (isRecording) {
                        // Display breathing phase info
                        Text(
                                text = "Recording Respiratory Data",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color(0xFF4CAF50)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Breathing classification result card
                        if (breathingClassification != "Unknown" &&
                                        breathingClassification != "Analyzing..."
                        ) {
                                Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        colors =
                                                CardDefaults.cardColors(
                                                        containerColor =
                                                                when (breathingClassification) {
                                                                        "Normal" ->
                                                                                Color(
                                                                                        0xFFE8F5E9
                                                                                ) // Light green
                                                                        "Abnormal" ->
                                                                                Color(
                                                                                        0xFFFFF3E0
                                                                                ) // Light orange
                                                                        else ->
                                                                                Color(
                                                                                        0xFFFFEBEE
                                                                                ) // Light red for
                                                                // errors
                                                                }
                                                )
                                ) {
                                        Column(
                                                modifier = Modifier.padding(16.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                                Text(
                                                        text = "Breathing Assessment",
                                                        style = MaterialTheme.typography.titleMedium
                                                )

                                                Spacer(modifier = Modifier.height(8.dp))

                                                Text(
                                                        text = breathingClassification,
                                                        style =
                                                                MaterialTheme.typography.titleMedium
                                                                        .copy(
                                                                                fontWeight =
                                                                                        FontWeight
                                                                                                .Bold
                                                                        ),
                                                        color =
                                                                when (breathingClassification) {
                                                                        "Normal" ->
                                                                                Color(
                                                                                        0xFF4CAF50
                                                                                ) // Green
                                                                        "Abnormal" ->
                                                                                Color(
                                                                                        0xFFFF9800
                                                                                ) // Orange
                                                                        else ->
                                                                                Color(
                                                                                        0xFFF44336
                                                                                ) // Red
                                                                }
                                                )

                                                Text(
                                                        text =
                                                                "Confidence: ${(classificationConfidence * 100).toInt()}%",
                                                        style = MaterialTheme.typography.bodyMedium
                                                )

                                                // Show which model is being used
                                                Text(
                                                        text = "Using: ${viewModel.getModelInfo()}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.Gray
                                                )

                                                // Show warning if model is missing
                                                if (viewModel.getModelInfo().contains("MISSING") ||
                                                                viewModel
                                                                        .getModelInfo()
                                                                        .contains("Rule-Based")
                                                ) {
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Card(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                colors =
                                                                        CardDefaults.cardColors(
                                                                                containerColor =
                                                                                        Color(
                                                                                                0xFFFFEBEE
                                                                                        ) // Light
                                                                                // red
                                                                                // background
                                                                                )
                                                        ) {
                                                                Column(
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        8.dp
                                                                                )
                                                                ) {
                                                                        Text(
                                                                                text =
                                                                                        "⚠️ MODEL FILES MISSING!",
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .bodyMedium
                                                                                                .copy(
                                                                                                        fontWeight =
                                                                                                                FontWeight
                                                                                                                        .Bold
                                                                                                ),
                                                                                color =
                                                                                        Color(
                                                                                                0xFFF44336
                                                                                        ) // Red
                                                                                // text
                                                                                )

                                                                        Text(
                                                                                text =
                                                                                        "Copy .tflite files to app/src/main/assets/",
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .bodySmall,
                                                                                color =
                                                                                        Color(
                                                                                                0xFFF44336
                                                                                        ) // Red
                                                                                // text
                                                                                )
                                                                }
                                                        }
                                                }
                                        }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                        }

                        Text(
                                text = "Breathing Phase: $breathingPhase",
                                style = MaterialTheme.typography.titleMedium,
                                color =
                                        when (breathingPhase.lowercase()) {
                                                "inhaling" -> Color(0xFF4CAF50)
                                                "exhaling" -> Color(0xFF2196F3)
                                                else -> Color(0xFFFFC107)
                                        }
                        )
                        Text(
                                text = "Confidence: ${(confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                                text = "Velocity: ${String.format("%.1f", velocity)}",
                                style = MaterialTheme.typography.bodyMedium
                        )

                        // Show data points count
                        Text(
                                text = "Data points: ${respiratoryData.size}",
                                style = MaterialTheme.typography.bodyMedium
                        )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Control buttons
                Button(
                        onClick = onStopRecording,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) { Text(text = if (isRecording) "Stop Recording" else "Cancel and Return") }

                Button(
                        onClick = onNewPatient,
                        modifier =
                                Modifier.fillMaxWidth()
                                        .height(48.dp)
                                        .padding(top = 8.dp, bottom = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
                ) { Text(text = "New Patient") }
        }
}

@Composable
fun ResultsScreen(
        respiratoryData: List<RespiratoryDataPoint>,
        patientMetadata: PatientMetadata?,
        onStartRecording: () -> Unit,
        onSaveData: () -> Unit,
        onNewPatient: () -> Unit,
        onSaveGraph: () -> Unit,
        onReturnToCameraSetup: () -> Unit
) {
        // Access the ViewModel from the CompositionLocal
        val viewModel = LocalViewModel.current

        // Access the breathing classification state
        val breathingClassification = viewModel.breathingClassification.collectAsState().value
        val classificationConfidence = viewModel.classificationConfidence.collectAsState().value

        Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                Text(
                        text = "Recording Results",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFF2196F3)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Display patient info
                patientMetadata?.let {
                        Text(
                                text = "Patient: ${it.id}",
                                style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                                text = "Age: ${it.age}, Gender: ${it.gender}",
                                style = MaterialTheme.typography.bodyMedium
                        )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Show recording stats
                Text(
                        text = "Data Points: ${respiratoryData.size}",
                        style = MaterialTheme.typography.bodyMedium
                )

                // Display breathing classification result
                if (breathingClassification != "Unknown" &&
                                breathingClassification != "Analyzing..."
                ) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor =
                                                        when (breathingClassification) {
                                                                "Normal" ->
                                                                        Color(
                                                                                0xFFE8F5E9
                                                                        ) // Light green
                                                                "Abnormal" ->
                                                                        Color(
                                                                                0xFFFFF3E0
                                                                        ) // Light orange
                                                                else ->
                                                                        Color(
                                                                                0xFFFFEBEE
                                                                        ) // Light red for errors
                                                        }
                                        )
                        ) {
                                Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                        Text(
                                                text = "Breathing Assessment",
                                                style = MaterialTheme.typography.titleMedium
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                                text = breathingClassification,
                                                style =
                                                        MaterialTheme.typography.headlineSmall.copy(
                                                                fontWeight = FontWeight.Bold
                                                        ),
                                                color =
                                                        when (breathingClassification) {
                                                                "Normal" ->
                                                                        Color(0xFF4CAF50) // Green
                                                                "Abnormal" ->
                                                                        Color(0xFFFF9800) // Orange
                                                                else -> Color(0xFFF44336) // Red
                                                        }
                                        )

                                        Text(
                                                text =
                                                        "Confidence: ${(classificationConfidence * 100).toInt()}%",
                                                style = MaterialTheme.typography.bodyMedium
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Add interpretation text
                                        Text(
                                                text =
                                                        when (breathingClassification) {
                                                                "Normal" ->
                                                                        "Regular breathing pattern within normal range (12-20 breaths/minute)"
                                                                "Abnormal" ->
                                                                        "Irregular or abnormal breathing pattern detected"
                                                                else ->
                                                                        "Unable to classify breathing pattern"
                                                        },
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                }
                        }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Add chart for visualization with id for saving
                Box(modifier = Modifier.fillMaxWidth().height(200.dp).padding(vertical = 16.dp)) {
                        RespirationChart(respiratoryData = respiratoryData, id = "chart_container")
                }

                Spacer(modifier = Modifier.weight(1f))

                // Control buttons row 1
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                        Button(
                                onClick = onSaveData,
                                modifier = Modifier.weight(1f),
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF4CAF50)
                                        )
                        ) { Text(text = "Save Data") }

                        Button(
                                onClick = onSaveGraph,
                                modifier = Modifier.weight(1f),
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF009688)
                                        )
                        ) { Text(text = "Save Graph") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Control buttons row 2
                Button(
                        onClick = onReturnToCameraSetup,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) { Text(text = "Record Again") }

                Button(
                        onClick = onNewPatient,
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
                ) { Text(text = "New Patient") }
        }
}
