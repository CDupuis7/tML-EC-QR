package com.example.tml_ec_qr_scan

// Explicitly import our model classes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Screen for respiratory disease detection using audio analysis */
@Composable
fun DiseaseDetectionScreen(viewModel: MainViewModel, onBackToMain: () -> Unit) {
        val diseaseUiState by viewModel.diseaseUiState.collectAsState()

        Column(
                modifier =
                        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                Text(
                        text = "Respiratory Health Analysis",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 24.dp)
                )

                // Show different UI based on the current state
                AnimatedContent(targetState = diseaseUiState, label = "DiseaseStateTransition") {
                        state ->
                        // Force type to be explicitly recognized as a DiseaseUiState
                        val typedState: DiseaseUiState = state

                        when (typedState) {
                                is DiseaseUiState.Ready -> {
                                        ReadyState(
                                                onStartRecording = {
                                                        viewModel.startDiseaseRecording()
                                                }
                                        )
                                }
                                is DiseaseUiState.Recording -> {
                                        RecordingState(
                                                remainingSeconds = typedState.remainingSeconds,
                                                onStopRecording = {
                                                        viewModel.stopDiseaseDetection()
                                                }
                                        )
                                }
                                is DiseaseUiState.Analyzing -> {
                                        AnalyzingState()
                                }
                                is DiseaseUiState.Result -> {
                                        ResultState(
                                                result = typedState.diagnosisResult,
                                                onReset = { viewModel.resetDiseaseDetection() }
                                        )
                                }
                        }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                        onClick = onBackToMain,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                )
                ) { Text("Back to Main Screen") }
        }
}

@Composable
private fun ReadyState(onStartRecording: () -> Unit) {
        Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                // Add explanation about the feature
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                ) {
                        Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                                Text(
                                        text = "What is Respiratory Health Analysis?",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Text(
                                        text =
                                                "This feature uses QR code tracking to measure your chest movement during breathing. It analyzes respiratory patterns to detect potential breathing abnormalities associated with conditions like:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                )

                                Column(
                                        modifier =
                                                Modifier.padding(
                                                        start = 16.dp,
                                                        top = 8.dp,
                                                        bottom = 8.dp
                                                )
                                ) {
                                        Text(
                                                text = "• Asthma",
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                        Text(
                                                text = "• COPD",
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                        Text(
                                                text = "• Respiratory distress",
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                        Text(
                                                text = "• Abnormal breathing patterns",
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                }

                                Text(
                                        text =
                                                "The analysis will provide you with information about your breathing rate, detected irregularities, and chest movement patterns to help identify normal or abnormal breathing.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                )

                                Text(
                                        text =
                                                "Note: This is not a medical diagnosis. Always consult a healthcare professional for medical advice.",
                                        style =
                                                MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = FontWeight.Bold
                                                ),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 8.dp)
                                )
                        }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                ) {
                        Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                                Text(
                                        text = "Instructions",
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Text(
                                        text = "1. Place the QR code on your chest",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                )

                                Text(
                                        text =
                                                "2. Ensure the QR code is clearly visible to the camera",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                )

                                Text(
                                        text = "3. Breathe normally for 30 seconds",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                )

                                Text(
                                        text = "4. Try not to move other than normal breathing",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))
                        }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                        onClick = onStartRecording,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("Start QR Tracking") }
        }
}

@Composable
private fun RecordingState(remainingSeconds: Int, onStopRecording: () -> Unit) {
        Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                ) {
                        Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                                Text(
                                        text = "Tracking in Progress",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                )

                                Text(
                                        text = "$remainingSeconds",
                                        style = MaterialTheme.typography.displayLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(vertical = 16.dp)
                                )

                                Text(
                                        text = "seconds remaining",
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                )

                                // Add visual feedback for QR tracking
                                Text(
                                        text = "Tracking QR movement...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF4CAF50),
                                        modifier = Modifier.padding(vertical = 8.dp)
                                )

                                Text(
                                        text = "Breathe normally while keeping the QR code visible",
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                )
                        }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                        onClick = onStopRecording,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
                ) { Text("Stop Recording") }
        }
}

@Composable
private fun AnalyzingState() {
        Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                ) {
                        Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                                Text(
                                        text = "Analyzing Breathing Patterns",
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.padding(bottom = 24.dp)
                                )

                                CircularProgressIndicator(
                                        modifier = Modifier.size(64.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Text(
                                        text = "Please wait while we analyze your QR movement data",
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center
                                )
                        }
                }
        }
}

@Composable
private fun ResultState(result: DiagnosisResult, onReset: () -> Unit) {
        val scrollState = rememberScrollState()

        Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                // Classification result
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor =
                                                when (result.classification.lowercase()) {
                                                        "normal" ->
                                                                Color(
                                                                        0xFFE8F5E9
                                                                ) // Light green for normal
                                                        "error" ->
                                                                Color(
                                                                        0xFFFFEBEE
                                                                ) // Light red for errors
                                                        else ->
                                                                Color(
                                                                        0xFFFFF3E0
                                                                ) // Light orange for abnormal
                                                }
                                )
                ) {
                        Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                                Text(
                                        text = "Analysis Results",
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                )

                                // Classification result
                                Text(
                                        text = "Result: ${result.classification}",
                                        style =
                                                MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.Bold
                                                ),
                                        color =
                                                when (result.classification.lowercase()) {
                                                        "normal" ->
                                                                Color(
                                                                        0xFF4CAF50
                                                                ) // Green for normal
                                                        "error" ->
                                                                Color(0xFFF44336) // Red for errors
                                                        else ->
                                                                Color(
                                                                        0xFFFF9800
                                                                ) // Orange for abnormal
                                                },
                                        modifier = Modifier.padding(bottom = 8.dp)
                                )

                                // Confidence
                                if (result.confidence > 0) {
                                        Text(
                                                text =
                                                        "Confidence: ${(result.confidence * 100).toInt()}%",
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                }

                                // Breathing rate
                                if (result.breathingRate > 0) {
                                        Text(
                                                text =
                                                        "Breathing Rate: ${String.format("%.1f", result.breathingRate)} breaths/min",
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        // Add a card for detected conditions if any
                                        if (result.detectedConditions.isNotEmpty() &&
                                                        result.classification == "Abnormal"
                                        ) {
                                                Card(
                                                        modifier =
                                                                Modifier.fillMaxWidth()
                                                                        .padding(vertical = 8.dp),
                                                        colors =
                                                                CardDefaults.cardColors(
                                                                        containerColor =
                                                                                Color(
                                                                                        0xFFFFF3E0
                                                                                ) // Light orange
                                                                        // background
                                                                        )
                                                ) {
                                                        Column(modifier = Modifier.padding(16.dp)) {
                                                                Text(
                                                                        text =
                                                                                "Detected Conditions:",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .titleMedium,
                                                                        fontWeight =
                                                                                FontWeight.Bold,
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        bottom =
                                                                                                8.dp
                                                                                )
                                                                )

                                                                // Display each condition with
                                                                // bullet points
                                                                result.detectedConditions.forEach {
                                                                        condition ->
                                                                        Row(
                                                                                modifier =
                                                                                        Modifier.padding(
                                                                                                vertical =
                                                                                                        4.dp
                                                                                        ),
                                                                                verticalAlignment =
                                                                                        Alignment
                                                                                                .Top
                                                                        ) {
                                                                                Text(
                                                                                        text = "•",
                                                                                        style =
                                                                                                MaterialTheme
                                                                                                        .typography
                                                                                                        .bodyMedium,
                                                                                        modifier =
                                                                                                Modifier.padding(
                                                                                                        end =
                                                                                                                8.dp,
                                                                                                        top =
                                                                                                                2.dp
                                                                                                )
                                                                                )
                                                                                Text(
                                                                                        text =
                                                                                                condition,
                                                                                        style =
                                                                                                MaterialTheme
                                                                                                        .typography
                                                                                                        .bodyMedium
                                                                                )
                                                                        }
                                                                }
                                                        }
                                                }
                                        }

                                        // Add detailed metrics
                                        if (result.amplitudeVariability > 0 ||
                                                        result.durationVariability > 0
                                        ) {
                                                Spacer(modifier = Modifier.height(8.dp))

                                                Card(
                                                        modifier =
                                                                Modifier.fillMaxWidth()
                                                                        .padding(vertical = 8.dp),
                                                        colors =
                                                                CardDefaults.cardColors(
                                                                        containerColor =
                                                                                Color(
                                                                                        0xFFE3F2FD
                                                                                ) // Light blue
                                                                        // background
                                                                        )
                                                ) {
                                                        Column(modifier = Modifier.padding(16.dp)) {
                                                                Text(
                                                                        text = "Breathing Metrics:",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .titleMedium,
                                                                        fontWeight =
                                                                                FontWeight.Bold,
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        bottom =
                                                                                                8.dp
                                                                                )
                                                                )

                                                                Text(
                                                                        text =
                                                                                "Amplitude Variability: ${String.format("%.2f", result.amplitudeVariability)}",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodyMedium,
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        vertical =
                                                                                                2.dp
                                                                                )
                                                                )

                                                                Text(
                                                                        text =
                                                                                "Duration Variability: ${String.format("%.2f", result.durationVariability)}",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodyMedium,
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        vertical =
                                                                                                2.dp
                                                                                )
                                                                )

                                                                // Interpretation of metrics
                                                                Spacer(
                                                                        modifier =
                                                                                Modifier.height(
                                                                                        8.dp
                                                                                )
                                                                )
                                                                Text(
                                                                        text =
                                                                                "Amplitude variability reflects consistency in breathing depth. Duration variability indicates how regular your breathing rhythm is. Higher values suggest more irregularity.",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodySmall,
                                                                        color = Color.DarkGray
                                                                )
                                                        }
                                                }
                                        }
                                }

                                // Irregularity index
                                if (result.irregularityIndex > 0) {
                                        Text(
                                                text =
                                                        "Irregularity Index: ${String.format("%.2f", result.irregularityIndex)}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        // Add interpretation of irregularity
                                        val irregularityInterpretation =
                                                when {
                                                        result.irregularityIndex < 0.3 ->
                                                                "Regular breathing pattern"
                                                        result.irregularityIndex < 0.6 ->
                                                                "Mildly irregular breathing pattern"
                                                        else ->
                                                                "Significantly irregular breathing pattern"
                                                }

                                        Text(
                                                text = irregularityInterpretation,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color =
                                                        when {
                                                                result.irregularityIndex < 0.3 ->
                                                                        Color(0xFF4CAF50) // Green
                                                                result.irregularityIndex < 0.6 ->
                                                                        Color(0xFFFF9800) // Orange
                                                                else -> Color(0xFFF44336) // Red
                                                        },
                                                modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                }
                        }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Recommendations
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                ) {
                        Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.Start
                        ) {
                                Text(
                                        text = "Recommendations",
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.padding(bottom = 16.dp)
                                )

                                // List of recommendations
                                result.recommendations.forEach { recommendation ->
                                        Row(
                                                modifier = Modifier.padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.Top
                                        ) {
                                                Text(
                                                        text = "•",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier =
                                                                Modifier.padding(
                                                                        end = 8.dp,
                                                                        top = 2.dp
                                                                )
                                                )
                                                Text(
                                                        text = recommendation,
                                                        style = MaterialTheme.typography.bodyMedium
                                                )
                                        }
                                }

                                // Add general interpretation at the bottom
                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                        text = "Understanding Your Results:",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(bottom = 8.dp, top = 8.dp)
                                )

                                Text(
                                        text =
                                                "Normal breathing is typically characterized by a rate of 12-20 breaths per minute with regular patterns. Variations outside this range or irregular patterns may indicate respiratory issues.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Text(
                                        text =
                                                "Note: This analysis is not a medical diagnosis. Always consult a healthcare professional for proper medical advice and treatment.",
                                        style =
                                                MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = FontWeight.Bold
                                                ),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 8.dp)
                                )
                        }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                        onClick = onReset,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) { Text("New Analysis") }
        }
}
