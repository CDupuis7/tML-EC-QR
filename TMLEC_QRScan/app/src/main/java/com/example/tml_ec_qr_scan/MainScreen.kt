package com.example.tml_ec_qr_scan

// import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tml_ec_qr_scan.ChatGPT.ChatViewModel
import kotlinx.coroutines.delay

// CompositionLocal to provide ViewModel access in nested composables
val LocalViewModel = staticCompositionLocalOf<MainViewModel> { error("No ViewModel provided") }

@Composable
fun BackButton(onBackClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
        IconButton(onClick = onBackClick, enabled = enabled, modifier = modifier) {
                Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = if (enabled) MaterialTheme.colorScheme.primary else Color.Gray
                )
        }
}

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

        // Health data collection
        val currentHealthData by viewModel.currentHealthData.collectAsState()

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
                                                        bottom = 32.dp // FIXED: Increased bottom
                                                        // padding to ensure content
                                                        // visibility
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
                                                        },
                                                        onBackClick = { /* No back navigation from initial screen */
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
                                                        },
                                                        onStartYoloTracking = {
                                                                viewModel.prepareForYoloTracking()
                                                        },
                                                        onBackClick = { viewModel.navigateBack() }
                                                )
                                        }
                                        is UiState.Calibrating -> {
                                                CalibratingScreen(
                                                        patientMetadata = patientMetadata,
                                                        onForceComplete = {
                                                                viewModel.forceCompleteCalibration()
                                                        },
                                                        onBackClick = { viewModel.navigateBack() }
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
                                                        currentHealthData = currentHealthData,
                                                        onStartRecording = {
                                                                viewModel.startRecording()
                                                        },
                                                        onStopRecording = onStopRecording,
                                                        onForceBreathingUpdate =
                                                                onForceBreathingUpdate,
                                                        onNewPatient = onNewPatient,
                                                        onBackClick = { viewModel.navigateBack() }
                                                )
                                        }
                                        is UiState.Results -> {
                                                ResultsScreen(
                                                        respiratoryData = respiratoryData,
                                                        patientMetadata = patientMetadata,
                                                        onStartRecording = {
                                                                viewModel
                                                                        .restartRecordingWithSameMode()
                                                        },
                                                        onSaveData = onSaveData,
                                                        onNewPatient = onNewPatient,
                                                        onSaveGraph = onSaveGraph,
                                                        onReturnToCameraSetup = {
                                                                viewModel.proceedToCameraSetup()
                                                        },
                                                        onBackClick = { viewModel.navigateBack() }
                                                )
                                        }
                                        is UiState.DiseaseDetection -> {
                                                DiseaseDetectionScreen(
                                                        viewModel = viewModel,
                                                        onBackToMain = {
                                                                viewModel.proceedToCameraSetup()
                                                        },
                                                        onBackClick = { viewModel.navigateBack() }
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
        onProceedToCameraSetup: () -> Unit,
        onBackClick: () -> Unit,
        viewModel: ChatViewModel = viewModel()
) {
        var patientId by remember { mutableStateOf(patientMetadata?.id ?: "") }
        var age by remember { mutableStateOf(patientMetadata?.age?.toString() ?: "") }
        var gender by remember { mutableStateOf(patientMetadata?.gender ?: "") }
        var healthCondition: MutableList<String> by remember { mutableStateOf(patientMetadata?.healthStatus
                ?.split(",")
                ?.map { it.trim() }
                ?.toMutableList()
                ?: mutableListOf())
        }
        // var healthCondition by remember { mutableStateOf(patientMetadata?.healthStatus) }

        // Define dropdown options
        val genderOptions = listOf("Male", "Female", "Other")
        val healthConditionOptions =
                listOf("Healthy", "Asthmatic", "COPD", "Respiratory Infection", "Other")

        // State for dropdown expanded status
        var genderExpanded by remember { mutableStateOf(false) }
        var healthConditionExpanded by remember { mutableStateOf(false) }

        // Get context for NFC operations
        val context = androidx.compose.ui.platform.LocalContext.current
        val nfcManager = remember { NFCManager(context) }

        // Used For Assistant card
        var showAssistant by remember { mutableStateOf(false) }
        val chatResponse by viewModel.response.collectAsState()
        var userInput by remember { mutableStateOf("") }

        // Update fields when patientMetadata changes (e.g., from NFC)
        LaunchedEffect(patientMetadata) {
                patientMetadata?.let { metadata ->
                        patientId = metadata.id
                        age = if (metadata.age > 0) metadata.age.toString() else ""
                        gender = metadata.gender
                        // healthCondition = metadata.healthStatus
                        healthCondition = healthCondition.toMutableList().apply {
                                add(metadata.healthStatus)
                        }

                }
        }

        Box(modifier = Modifier.fillMaxSize()){
        // FIXED: Added scrollable column with proper spacing
        Column(
                modifier =
                        Modifier.fillMaxSize()
                                .verticalScroll(
                                        rememberScrollState()
                                ) // ADDED: Scrolling capability
                                .padding(16.dp)
                                .padding(top = 8.dp), // REDUCED: Minimal top padding
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement =
                        Arrangement.spacedBy(12.dp) // REDUCED: Smaller spacing between elements
        ) {

                // FIXED: App title with reduced spacing
                Text(
                        text = "RespirAPPtion",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFF2196F3),
                        modifier =
                                Modifier.padding(
                                        top = 18.dp,
                                )
                )
                Text(
                        text = "(A Respiratory Health Monitoring App)",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF9C27B0),
                        modifier = Modifier.padding(bottom = 4.dp) // REDUCED: Much smaller padding
                )





                        // FIXED: Patient info title with minimal spacing
                        Text(
                                text = "Patient Information",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color(0xFF4CAF50),
                                modifier =
                                        Modifier.padding(bottom = 8.dp) // REDUCED: Smaller bottom padding
                        )

                        // NFC Status Card - made more compact
                        Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor =
                                                        when {
                                                                !nfcManager.isNFCAvailable() ->
                                                                        Color(0xFFFFEBEE) // Light red
                                                                !nfcManager.isNFCEnabled() ->
                                                                        Color(0xFFFFF3E0) // Light orange
                                                                else -> Color(0xFFE8F5E9) // Light green
                                                        }
                                        )
                        ) {
                                Column(
                                        modifier =
                                                Modifier.padding(
                                                        12.dp
                                                ), // REDUCED: Smaller internal padding
                                        horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                        Text(
                                                text = "📱 NFC Quick Fill",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = Color(0xFF505050),
                                                fontWeight = FontWeight.Bold
                                        )

                                        Spacer(modifier = Modifier.height(4.dp)) // REDUCED: Smaller spacer

                                        Text(
                                                text = nfcManager.getNFCStatusMessage(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color =
                                                        when {
                                                                !nfcManager.isNFCAvailable() ->
                                                                        Color(0xFFD32F2F) // Red
                                                                !nfcManager.isNFCEnabled() ->
                                                                        Color(0xFFF57C00) // Orange
                                                                else -> Color(0xFF388E3C) // Green
                                                        }
                                        )

                                        if (nfcManager.isNFCAvailable() && nfcManager.isNFCEnabled()) {
                                                Spacer(
                                                        modifier = Modifier.height(4.dp)
                                                ) // REDUCED: Smaller spacer
                                                Text(
                                                        text =
                                                                "🏷️ Tap an NFC tag with patient data to auto-fill the form",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color(0xFF388E3C),
                                                        textAlign =
                                                                androidx.compose.ui.text.style.TextAlign
                                                                        .Center
                                                )
                                        } else if (!nfcManager.isNFCEnabled() && nfcManager.isNFCAvailable()
                                        ) {
                                                Spacer(
                                                        modifier = Modifier.height(6.dp)
                                                ) // REDUCED: Smaller spacer
                                                Button(
                                                        onClick = { nfcManager.showNFCSettings() },
                                                        colors =
                                                                ButtonDefaults.buttonColors(
                                                                        containerColor = Color(
                                                                                0xFFF57C00
                                                                        )
                                                                ),
                                                        modifier = Modifier.fillMaxWidth()
                                                ) { Text("Enable NFC") }
                                        }
                                }
                        }

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
                                        onValueChange = { /* Disabled direct editing */ },
                                        label = { Text("Gender") },
                                        modifier = Modifier.fillMaxWidth(),
                                        readOnly = true,
                                        trailingIcon = {
                                                IconButton(onClick = { genderExpanded = true }) {
                                                        Icon(
                                                                Icons.Default.ArrowDropDown,
                                                                "Dropdown"
                                                        )
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
/*
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
*/

                // Health condition dropdown
                Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.Start
                ) {
                        var selectedConditions by remember { mutableStateOf(mutableListOf<String>()) }
                        val healthConditionOptions2 =
                                listOf("Asthmatic", "COPD", "Respiratory Infection")
                        var isHealthyChecked by remember { mutableStateOf(false) }
                        var isOtherChecked by remember { mutableStateOf(false) }
                        var otherConditionText by remember {mutableStateOf("")}

                        Text("Please Select any Preexisting Conditions: ")
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()){
                                Checkbox(
                                        checked = isHealthyChecked,
                                        onCheckedChange = { checked ->
                                                isHealthyChecked = checked
                                                if (checked) {
                                                        healthCondition = mutableListOf("Healthy")
                                                        isOtherChecked = false
                                                        otherConditionText = ""
                                                }else healthCondition = healthCondition.toMutableList().apply {
                                                        remove("Healthy")
                                                }
                                        }
                                )
                                Text(text = "Healthy", modifier = Modifier.clickable{
                                        isHealthyChecked = !isHealthyChecked

                                }.padding(start = 8.dp))
                        }
                        val isEnabled = !isHealthyChecked
                        healthConditionOptions2.forEach { option ->

                                val isChecked = healthCondition.contains(option)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { checked ->
                                                        if (isEnabled) healthCondition = healthCondition.toMutableList().apply {
                                                                if (checked) add(option) else remove(option)
                                                        }
                                                }, enabled = isEnabled
                                        )
                                        Text(
                                                text = option,
                                                modifier = Modifier.clickable(enabled = isEnabled) {
                                                        healthCondition = healthCondition.toMutableList().apply{
                                                                if (contains(option)) remove(option) else add (option)
                                                        }
                                                }
                                                        .padding(start = 8.dp), color = if (isEnabled) Color.Unspecified else Color.Gray
                                        )
                                }

                        }
                        if (isHealthyChecked){
                                isOtherChecked = false
                                otherConditionText = ""
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()){

                                Checkbox(
                                        checked = isOtherChecked,
                                        onCheckedChange = { checked ->
                                                if (isEnabled) isOtherChecked = checked
                                                if(!isOtherChecked){
                                                        otherConditionText = ""
                                                }
                                        }, enabled = isEnabled

                                )
                                Text(text = "Other", modifier = Modifier.clickable (enabled = isEnabled){
                                        isOtherChecked = !isOtherChecked
                                        if (!isOtherChecked) {
                                                otherConditionText = ""
                                        }

                        // FIXED: Reduced spacing before buttons
                         // REDUCED: Much smaller spacer
                                }.padding(start = 8.dp), color = if (isEnabled) Color.Unspecified else Color.Gray)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (isOtherChecked){
                                OutlinedTextField(
                                        value = otherConditionText,
                                        onValueChange = { newText ->
                                                otherConditionText = newText
                                                healthCondition = healthCondition.toMutableList().apply{
                                                        removeAll { it !in listOf("Asthmatic", "COPD", "Respiratory Infection", "Healthy") }
                                                        if (newText.isNotBlank()){
                                                                add(newText)
                                                        }

                                                }},
                                        label = { Text("Please Specify")},
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                        }
                }
                // FIXED: Reduced spacing before buttons
                Spacer(modifier = Modifier.height(8.dp)) // REDUCED: Much smaller spacer

                        // Write to NFC button (if form is filled and NFC is available)
                        if (nfcManager.isNFCAvailable() &&
                                nfcManager.isNFCEnabled() &&
                                patientId.isNotBlank() &&
                                age.isNotBlank() &&
                                gender.isNotBlank() &&
                                healthCondition.isNotEmpty()
                ) {

                        Button(
                                onClick = {
                                        val numAge = age.toIntOrNull() ?: 0
                                        val metadata =
                                                PatientMetadata(
                                                        id = patientId,
                                                        age = numAge,
                                                        gender = gender,
                                                        healthStatus = healthCondition.joinToString(", ")
                                                )

                                                // Launch NFC write activity with current data
                                                val intent =
                                                        NFCWriteActivity.createIntent(
                                                                context as
                                                                        androidx.activity.ComponentActivity,
                                                                metadata
                                                        )
                                                context.startActivity(intent)
                                        },
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .height(48.dp), // REDUCED: Smaller button height
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF673AB7) // Purple
                                                )
                                ) { Text("📝 Write to NFC Tag") }

                                Spacer(
                                        modifier = Modifier.height(6.dp)
                                ) // REDUCED: Smaller spacer between buttons
                        }

                Button(
                        onClick = {
                                // Save patient metadata before proceeding
                                val numAge = age.toIntOrNull() ?: 0
                                val metadata =
                                        PatientMetadata(
                                                id = patientId,
                                                age = numAge,
                                                gender = gender,
                                                healthStatus = healthCondition.joinToString(", ")
                                        )
                                onUpdatePatientMetadata(metadata)
                                onProceedToCameraSetup()
                        },
                        enabled = patientId.isNotBlank() && age.isNotBlank() && gender.isNotBlank(),
                        modifier =
                                Modifier.fillMaxWidth()
                                        .height(48.dp), // REDUCED: Smaller button height
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2196F3),
                                        disabledContainerColor = Color(0xFFBDBDBD)
                                )
                ) { Text(text = "Proceed to Camera Setup",
                    color = Color(0xFF000000))
                }

                        // ADDED: Bottom padding to ensure content is not cut off
                        Spacer(modifier = Modifier.height(16.dp))
                }

                Button(
                        onClick = { showAssistant = true },

                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor =
                                                Color(0xFFFF0000) // Bright red
                                ),
                        modifier = Modifier
                                .height(48.dp)
                                .width(66.dp)
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp),


                        ) {
                        Text(
                                "?",
                                color = Color(0xFFFFFFFF),
                                textAlign = TextAlign.Center
                        )
                }


        }
        if (showAssistant) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showAssistant = false }) {

                        Box(
                                modifier = Modifier
                                        .fillMaxSize()
                        ) {
                                Card(
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .fillMaxHeight(0.5f) // Use 80% of screen height
                                                        .align(Alignment.BottomCenter),
                                        shape = RoundedCornerShape(16.dp),
                                        colors =
                                                CardDefaults.cardColors(

                                                        containerColor =
                                                                Color(
                                                                        0xFFFFFFFF
                                                                )
                                                )
                                ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                                Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                        Text(
                                                                text = "Assistant",
                                                                style = MaterialTheme.typography.titleLarge,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color.Red
                                                        )

                                                        // Close button
                                                        IconButton(onClick = {
                                                                showAssistant = false
                                                        }) {
                                                                Text(
                                                                        "✕",
                                                                        fontSize = 24.sp,
                                                                        color = Color.Red
                                                                )
                                                        }
                                                }

                                                Box(
                                                        modifier = Modifier
                                                                .fillMaxHeight(.6f)
                                                                .background(Color(0xFFF0F0F0))
                                                                .fillMaxWidth(),
                                                ) {
                                                        Text(text = chatResponse, color = Color(0xFF000000))
                                                }

                                                Spacer(modifier = Modifier.height(8.dp))

                                                OutlinedTextField(
                                                        value = userInput,
                                                        onValueChange = { userInput = it },
                                                        label = { Text("Your Question")},
                                                        modifier = Modifier.fillMaxWidth()


                                                )

                                                Button(
                                                        onClick = {
                                                                viewModel.sendMessage(userInput)
                                                                userInput = ""
                                                        },
                                                        modifier = Modifier.align(Alignment.End),
                                                        colors =
                                                                ButtonDefaults.buttonColors(
                                                                        containerColor =
                                                                                Color(0xFFFF0000) // Bright red
                                                                ),
                                                ) {
                                                        Text("Ask", color = Color(0xFFFFFFFF))
                                                }

                                        }
                                }

                        }
                }
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
        onStartDiseaseDetection: () -> Unit = {},
        onStartYoloTracking: () -> Unit = {},
        onBackClick: () -> Unit,
        chatModel: ChatViewModel = viewModel()
) {
        // Get camera started state from ViewModel
        val isCameraStarted by viewModel.isCameraStarted.collectAsState()

        // Used For Assistant card
        var showAssistant by remember { mutableStateOf(false) }
        val chatResponse by chatModel.response.collectAsState()
        var userInput by remember { mutableStateOf("") }


        Box(modifier = Modifier.fillMaxSize()) {

                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .padding(horizontal = 16.dp)
                                        .padding(top = 48.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                        // Back button at the top
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start
                        ) {
                                BackButton(
                                        onBackClick = onBackClick,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                )
                        }

                        Text(
                                text = "QR Respiratory Tracking",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 15.dp)
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

                                        // Calibration explanation
                                        Text(
                                                "• Start Calibration: Calibrates the QR tracking system to optimize detection",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                        )

                                        // QR Tracking explanation
                                        Text(
                                                "• Start QR Tracking: Track chest movement using QR codes to record respiratory data and classify breathing patterns",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                        )

                                        // YOLO Tracking explanation
                                        Text(
                                                "• Start YOLO Tracking: Track chest movement using AI person detection (no QR codes needed)",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Add Start Camera button
                        //                Button(
                        //                        onClick = { viewModel.startCamera() },
                        //                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        //                        colors = ButtonDefaults.buttonColors(containerColor =
                        // Color(0xFF2196F3))
                        //                ) { Text(if (isCameraStarted) "Camera Started" else "Start
                        // Camera") }

                        // Buttons
                        Button(
                                onClick = { onStartCalibration() },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(
                                                0xFF4CAF50
                                        )
                                )
                        ) { Text("Start Calibration") }

                        Button(
                                onClick = { onStartRecording() },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(
                                                0xFF2196F3
                                        )
                                )
                        ) { Text("Start QR Tracking") }

                        Button(
                                onClick = { onStartYoloTracking() },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(
                                                0xFFE8D200
                                        )
                                )
                        ) { Text("Start YOLO Tracking") }

                        Button(
                                onClick = { onNewPatient() },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(
                                                0xFF9C27B0
                                        )
                                )
                        ) { Text("New Patient") }

                        // Training mode toggle
                        //                Row(
                        //                        verticalAlignment = Alignment.CenterVertically,
                        //                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        //                ) {
                        //                        Text("Training Data Mode")
                        //                        Spacer(modifier = Modifier.width(8.dp))
                        //                        Switch(
                        //                                checked =
                        // viewModel.isTrainingMode.collectAsState().value,
                        //                                onCheckedChange = { onToggleTrainingMode() }
                        //                        )
                        //                }
                }


                Button(
                        onClick = { showAssistant = true },

                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor =
                                                Color(0xFFFF0000) // Bright red
                                ),
                        modifier = Modifier
                                .height(48.dp)
                                .width(66.dp)
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp),


                        ) {
                        Text(
                                "?",
                                color = Color(0xFFFFFFFF),
                                textAlign = TextAlign.Center
                        )
                }

        }
        if (showAssistant) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showAssistant = false }) {

                        Box(
                                modifier = Modifier
                                        .fillMaxSize()
                        ) {
                                Card(
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .fillMaxHeight(0.5f) // Use 80% of screen height
                                                        .align(Alignment.BottomCenter),
                                        shape = RoundedCornerShape(16.dp),
                                        colors =
                                                CardDefaults.cardColors(

                                                        containerColor =
                                                                Color(
                                                                        0xFFFFFFFF
                                                                )
                                                )
                                ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                                Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                        Text(
                                                                text = "Assistant",
                                                                style = MaterialTheme.typography.titleLarge,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color.Red
                                                        )

                                                        // Close button
                                                        IconButton(onClick = {
                                                                showAssistant = false
                                                        }) {
                                                                Text(
                                                                        "✕",
                                                                        fontSize = 24.sp,
                                                                        color = Color.Red
                                                                )
                                                        }
                                                }

                                                Box(
                                                        modifier = Modifier
                                                                .fillMaxHeight(.6f)
                                                                .background(Color(0xFFF0F0F0))
                                                                .fillMaxWidth(),
                                                ) {
                                                        Text(text = chatResponse, color = Color(0xFF000000))
                                                }

                                                Spacer(modifier = Modifier.height(8.dp))

                                                OutlinedTextField(
                                                        value = userInput,
                                                        onValueChange = { userInput = it },
                                                        label = { Text("Your Question")},
                                                        modifier = Modifier.fillMaxWidth()


                                                )

                                                Button(
                                                        onClick = {
                                                                chatModel.sendMessage(userInput)
                                                                userInput = ""
                                                        },
                                                        modifier = Modifier.align(Alignment.End),
                                                        colors =
                                                                ButtonDefaults.buttonColors(
                                                                        containerColor =
                                                                                Color(0xFFFF0000) // Bright red
                                                                ),
                                                ) {
                                                        Text("Ask", color = Color(0xFFFFFFFF))
                                                }

                                        }
                                }

                        }
                }
        }
}

@Composable
fun CalibratingScreen(
        patientMetadata: PatientMetadata?,
        onForceComplete: () -> Unit,
        onBackClick: () -> Unit
) {
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
                // Back button at the top
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        BackButton(
                                onBackClick = onBackClick,
                                modifier = Modifier.padding(bottom = 8.dp)
                        )
                }

                Text(
                        text = "Calibration in Progress",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFFFF9800) // Orange
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Camera toggle button for calibration
                val viewModel = LocalViewModel.current
                val isFrontCamera by viewModel.isFrontCamera.collectAsState()

                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                ) {
                        Button(
                                onClick = { viewModel.toggleCamera() },
                                modifier = Modifier.padding(8.dp),
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF607D8B)
                                        )
                        ) {
                                //Text("📷   ${if (isFrontCamera) "Back" else "Front"} Camera")
                                Image(
                                        painter = painterResource(id = R.drawable.camera),
                                        modifier = Modifier.size(20.dp),
                                        contentDescription = "Switch"

                                )

                        }
                }

                Spacer(modifier = Modifier.height(24.dp)) // Increased spacing for more camera room

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
        currentHealthData: HealthData,
        onStartRecording: () -> Unit,
        onStopRecording: () -> Unit,
        onForceBreathingUpdate: () -> Unit,
        onNewPatient: () -> Unit,
        chatModel: ChatViewModel = viewModel(),
        onBackClick: () -> Unit
) {
        // Access the ViewModel from the CompositionLocal
        val viewModel = LocalViewModel.current

        // Access the breathing classification state
        val breathingClassification = viewModel.breathingClassification.collectAsState().value
        val classificationConfidence = viewModel.classificationConfidence.collectAsState().value

        // Used For Assistant card
        var showAssistant by remember { mutableStateOf(false) }
        val chatResponse by chatModel.response.collectAsState()
        var userInput by remember { mutableStateOf("") }

        // Keeps the screen active for 60 seconds during recording
        val context = LocalContext.current
        val activity = context as? MainActivity
        LaunchedEffect(Unit) {
                activity?.keepScreenOn {
                        delay(60_000)
                }
        }


        Column() {

                Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                                modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth(),

                                horizontalArrangement = Arrangement.Start
                        ) {
                                BackButton(
                                        onBackClick = onBackClick,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                )
                        }
                }


                Box(
                        modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)


                ) {

                        Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                                //Spacer(modifier = Modifier.height(160.dp))
                                // Back button at the top


                                // Camera toggle button for tracking modes
                                val trackingMode =
                                        viewModel.currentTrackingMode.collectAsState().value
                                val isFrontCamera by viewModel.isFrontCamera.collectAsState()

                                if (trackingMode == TrackingMode.QR_TRACKING ||
                                        trackingMode == TrackingMode.YOLO_TRACKING
                                ) {
                                        Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                                // Camera toggle button
                                                Button(
                                                        onClick = { viewModel.toggleCamera() },
                                                        modifier = Modifier.weight(1f)
                                                                .padding(8.dp),
                                                        colors =
                                                                ButtonDefaults.buttonColors(
                                                                        containerColor = Color(
                                                                                0xFF607D8B
                                                                        )
                                                                )
                                                ) {
                                                        //Text("📷 ${if (isFrontCamera) "Back" else "Front"} Camera")
                                                        Image(
                                                                painter = painterResource(id = R.drawable.camera),
                                                                modifier = Modifier.size(20.dp),
                                                                contentDescription = "Switch"

                                                        )

                                                }

                                                Column(modifier = Modifier.padding(4.dp)) {

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

                                                }

                                                // Health data display in the same row
                                                Box(modifier = Modifier.weight(1f)) {
                                                        HealthDataDisplay(
                                                                healthData = currentHealthData,
                                                                modifier = Modifier.fillMaxWidth()
                                                        )
                                                }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp)) // Added more spacing below button
                                }

                                // Tracking mode instructions
                                if (!isRecording && readyToRecord) {
                                        // Show current tracking mode
                                        Card(
                                                modifier = Modifier.fillMaxWidth().height(84.dp),
                                                colors =
                                                        CardDefaults.cardColors(
                                                                containerColor =
                                                                        when (trackingMode) {
                                                                                TrackingMode.QR_TRACKING ->
                                                                                        Color(
                                                                                                0xFFE3F2FD
                                                                                        ) // Light blue
                                                                                TrackingMode.YOLO_TRACKING ->
                                                                                        Color(
                                                                                                0xFFF3E5F5
                                                                                        ) // Light purple
                                                                        }
                                                        )
                                        ) {
                                                Column(
                                                        modifier = Modifier.fillMaxSize(),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                        Spacer(modifier = Modifier.height(8.dp))

                                                        Text(
                                                                text =
                                                                        when (trackingMode) {
                                                                                TrackingMode.QR_TRACKING ->
                                                                                        "🎯 QR Code Tracking Mode"

                                                                                TrackingMode.YOLO_TRACKING ->
                                                                                        "🤖 YOLO Chest Tracking Mode"
                                                                        },
                                                                style = MaterialTheme.typography.titleMedium,
                                                                fontWeight = FontWeight.Bold,
                                                                color =
                                                                        when (trackingMode) {
                                                                                TrackingMode.QR_TRACKING ->
                                                                                        Color(0xFF1976D2)

                                                                                TrackingMode.YOLO_TRACKING ->
                                                                                        Color(0xFF7B1FA2)
                                                                        }
                                                        )

                                                        Spacer(modifier = Modifier.height(8.dp))

                                                        Text(
                                                                text =
                                                                        when (trackingMode) {
                                                                                TrackingMode.QR_TRACKING ->
                                                                                        "Position QR code on your chest and align it in the frame"

                                                                                TrackingMode.YOLO_TRACKING ->
                                                                                        "Position camera towards your chest area for AI detection"
                                                                        },
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = Color(0xFF000000),
                                                                textAlign =
                                                                        androidx.compose.ui.text.style.TextAlign
                                                                                .Center
                                                        )
                                                }
                                        }

                                        // Start Recording button
                                        Spacer(modifier = Modifier.height(8.dp))
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
                                        /* Text(
                        text = "Recording Respiratory Data",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF4CAF50)
                )*/

                                        //Spacer(modifier = Modifier.height(16.dp))

                                        // Only show breathing phase information during recording, not
                                        // classification
                                        Card(
                                                modifier = Modifier.fillMaxWidth().height(84.dp),
                                                colors =
                                                        CardDefaults.cardColors(
                                                                containerColor =
                                                                        Color(0xFFE1F5FE) // Light blue background
                                                        )
                                        ) {
                                                Column(
                                                        modifier = Modifier.padding(16.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                        Text(
                                                                text = "Real-time Breathing",
                                                                style = MaterialTheme.typography.titleMedium,
                                                                color = Color(0xFF000000)
                                                        )

                                                        Spacer(modifier = Modifier.height(8.dp))

                                                        // Show message that classification will be available after
                                                        // recording
                                                        Text(
                                                                text =
                                                                        "Classification will be available after recording stops",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = Color.Gray
                                                        )
                                                }
                                        }

                                        //Make text and spacer height equal 74
                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                                text = "Breathing Phase: $breathingPhase",
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.fillMaxWidth().height(20.dp),
                                                color =
                                                        when (breathingPhase.lowercase()) {
                                                                "inhaling" -> Color(0xFF4CAF50)
                                                                else -> Color(0xFF2196F3) // Exhaling
                                                        }
                                        )
                                        Text(
                                                text = "Velocity: ${
                                                        String.format(
                                                                "%.1f",
                                                                velocity
                                                        )
                                                }",
                                                modifier = Modifier.fillMaxWidth().height(18.dp),
                                                style = MaterialTheme.typography.bodyMedium
                                        )

                                        // Show data points count
                                        Text(
                                                text = "Data points: ${respiratoryData.size}",
                                                modifier = Modifier.fillMaxWidth().height(18.dp),
                                                style = MaterialTheme.typography.bodyMedium
                                        )

                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Control buttons
                                Button(
                                        onClick = onStopRecording,
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(
                                                        0xFFF44336
                                                )
                                        )
                                ) { Text(text = if (isRecording) "Stop Recording" else "Cancel and Return") }

                                Button(
                                        onClick = onNewPatient,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(
                                                        0xFF9C27B0
                                                )
                                        )
                                ) { Text("New Patient") }

                        }
                        Button(
                                onClick = { showAssistant = true },

                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor =
                                                        Color(0xFFFF0000) // Bright red
                                        ),
                                modifier = Modifier
                                        .height(48.dp)
                                        .width(66.dp)
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 16.dp),


                                ) {
                                Text(
                                        "?",
                                        color = Color(0xFFFFFFFF),
                                        textAlign = TextAlign.Center
                                )
                        }


                        // FIXED: Added bottom spacer to ensure button is fully visible
                        Spacer(modifier = Modifier.height(32.dp))

                }
                if (showAssistant) {
                        androidx.compose.ui.window.Dialog(onDismissRequest = {
                                showAssistant = false
                        }) {

                                Box(
                                        modifier = Modifier
                                                .fillMaxSize()
                                ) {
                                        Card(
                                                modifier =
                                                        Modifier.fillMaxWidth()
                                                                .fillMaxHeight(0.5f) // Use 80% of screen height
                                                                .align(Alignment.BottomCenter),
                                                shape = RoundedCornerShape(16.dp),
                                                colors =
                                                        CardDefaults.cardColors(

                                                                containerColor =
                                                                        Color(
                                                                                0xFFFFFFFF
                                                                        )
                                                        )
                                        ) {
                                                Column(modifier = Modifier.padding(8.dp)) {
                                                        Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                                Text(
                                                                        text = "Assistant",
                                                                        style = MaterialTheme.typography.titleLarge,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = Color.Red
                                                                )

                                                                // Close button
                                                                IconButton(onClick = {
                                                                        showAssistant = false
                                                                }) {
                                                                        Text(
                                                                                "✕",
                                                                                fontSize = 24.sp,
                                                                                color = Color.Red
                                                                        )
                                                                }
                                                        }

                                                        Box(
                                                                modifier = Modifier
                                                                        .fillMaxHeight(.6f)
                                                                        .background(Color(0xFFF0F0F0))
                                                                        .fillMaxWidth(),
                                                        ) {
                                                                Text(
                                                                        text = chatResponse,
                                                                        color = Color(0xFF000000)
                                                                )
                                                        }

                                                        Spacer(modifier = Modifier.height(8.dp))

                                                        OutlinedTextField(
                                                                value = userInput,
                                                                onValueChange = { userInput = it },
                                                                label = { Text("Your Question") },
                                                                modifier = Modifier.fillMaxWidth()


                                                        )

                                                        Button(
                                                                onClick = {
                                                                        chatModel.sendMessage(
                                                                                userInput
                                                                        )
                                                                        userInput = ""
                                                                },
                                                                modifier = Modifier.align(Alignment.End),
                                                                colors =
                                                                        ButtonDefaults.buttonColors(
                                                                                containerColor =
                                                                                        Color(0xFFFF0000) // Bright red
                                                                        ),
                                                        ) {
                                                                Text(
                                                                        "Ask",
                                                                        color = Color(0xFFFFFFFF)
                                                                )
                                                        }

                                                }
                                        }

                                }
                        }
                }
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
        onReturnToCameraSetup: () -> Unit,
        onBackClick: () -> Unit
) {
        val viewModel = LocalViewModel.current
        val breathingRate by viewModel.breathingRate.collectAsState()
        val breathingClassification by viewModel.breathingClassification.collectAsState()
        val classificationConfidence by viewModel.classificationConfidence.collectAsState()

        // State for recommendations dialog
        var showRecommendationsDialog by remember { mutableStateOf(false) }

        // State for graph dialog
        var showGraphDialog by remember { mutableStateOf(false) }

        // Get detected conditions from the breathing classifier result if available
        val detectedConditions =
                remember(breathingRate, breathingClassification) {
                        // Try to get conditions from the actual classification result
                        val classificationResult = viewModel.getLastClassificationResult()
                        if (classificationResult != null &&
                                        classificationResult.detectedConditions.isNotEmpty()
                        ) {
                                // Use the actual detected conditions from the classifier
                                classificationResult.detectedConditions
                        } else {
                                // Fallback to manual detection if no classification result
                                // available
                                mutableListOf<String>().apply {
                                        // Check for tachypnea (fast breathing)
                                        if (breathingRate > 24f) {
                                                add("TACHYPNEA (fast breathing)")
                                        }
                                        // Check for bradypnea (slow breathing)
                                        else if (breathingRate < 10f) {
                                                add("BRADYPNEA (slow breathing)")
                                        }

                                        // Add other detected conditions from assessed data
                                        val irregularityIndex =
                                                respiratoryData.calculateIrregularityIndex()
                                        val amplitudeVariation =
                                                respiratoryData.calculateAmplitudeVariation()

                                        if (irregularityIndex > 0.6f) { // Updated threshold
                                                add(
                                                        "HIGH IRREGULARITY (breathing rhythm variability)"
                                                )
                                        }

                                        if (amplitudeVariation > 60f) { // Updated threshold
                                                add(
                                                        "HIGH AMPLITUDE VARIATION (inconsistent breath depth)"
                                                )
                                        }
                                }
                        }
                }

        // Generate recommendations based on detected conditions
        val recommendations =
                remember(detectedConditions) {
                        mutableListOf<String>().apply {
                                if (detectedConditions.isEmpty()) {
                                        add("Continue with healthy breathing practices")
                                } else {
                                        if (detectedConditions.any { it.contains("TACHYPNEA") }) {
                                                add(
                                                        "Try slow breathing exercises to reduce your breathing rate"
                                                )
                                                add(
                                                        "Practice diaphragmatic breathing to regulate breath pace"
                                                )
                                        }

                                        if (detectedConditions.any { it.contains("BRADYPNEA") }) {
                                                add(
                                                        "Practice respiratory exercises to normalize breathing rate"
                                                )
                                                add(
                                                        "Monitor for associated symptoms like dizziness or fatigue"
                                                )
                                        }

                                        if (detectedConditions.any { it.contains("IRREGULARITY") }
                                        ) {
                                                add(
                                                        "Practice rhythmic breathing exercises for consistency"
                                                )
                                        }

                                        if (detectedConditions.any { it.contains("AMPLITUDE") }) {
                                                add(
                                                        "Focus on consistent breath depth during breathing exercises"
                                                )
                                        }

                                        add(
                                                "Consider consulting a healthcare professional for a complete evaluation"
                                        )
                                }
                        }
                }

        Column(
                modifier =
                        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                // Back button at the top
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        BackButton(
                                onBackClick = onBackClick,
                                modifier = Modifier.padding(top = 13.dp)
                        )
                }

                Text(
                        text = "Breathing Assessment Results",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                                Modifier.fillMaxSize().padding(bottom = 16.dp).padding(top = 20.dp)
                )

                // Display patient information
                if (patientMetadata != null) {
                        Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor =
                                                        MaterialTheme.colorScheme.surfaceVariant
                                        )
                        ) {
                                Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                        Text(
                                                text = "Patient: ${patientMetadata.id}",
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.padding(bottom = 4.dp)
                                        )
                                        Text(
                                                text =
                                                        "Age: ${patientMetadata.age}, Gender: ${patientMetadata.gender}",
                                                style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                                text = "Data Points: ${respiratoryData.size}",
                                                style = MaterialTheme.typography.bodyMedium
                                        )
                                }
                        }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Breathing Classification Result
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor =
                                                when (breathingClassification.lowercase()) {
                                                        "normal" ->
                                                                Color(
                                                                        0xFFE8F5E9
                                                                ) // Light green background
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
                                        text = "Breathing Assessment",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color(0xFF000000),
                                        modifier = Modifier.padding(bottom = 16.dp)
                                )

                                Text(
                                        text = breathingClassification,
                                        style = MaterialTheme.typography.displaySmall,
                                        color =
                                                when (breathingClassification.lowercase()) {
                                                        "normal" ->
                                                                Color(
                                                                        0xFF4CAF50
                                                                ) // Green for normal
                                                        else ->
                                                                Color(
                                                                        0xFFFF9800
                                                                ) // Orange for abnormal
                                                },
                                        fontWeight = FontWeight.Bold
                                )

                                Text(
                                        text =
                                                "Confidence: ${(classificationConfidence * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color(0xFF000000)
                                )

                                Text(
                                        text =
                                                "Breathing Rate: ${String.format("%.2f", breathingRate)} breaths/min",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF000000),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 8.dp)
                                )

                                // Display normal range
                                Text(
                                        text =
                                                "Breathing rate ${if (breathingRate >= 10f && breathingRate <= 24f) "within" else "outside"} normal range (10-24 breaths/minute)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF000000),
                                        modifier = Modifier.padding(vertical = 8.dp)
                                )

                                // Display detected conditions if any
                                if (detectedConditions.isNotEmpty() &&
                                                breathingClassification.lowercase() == "abnormal"
                                ) {
                                        Spacer(modifier = Modifier.height(8.dp))

                                        Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors =
                                                        CardDefaults.cardColors(
                                                                containerColor =
                                                                        Color(
                                                                                0xFFFCE4EC
                                                                        ) // Light pink background
                                                        )
                                        ) {
                                                Column(modifier = Modifier.padding(16.dp)) {
                                                        Text(
                                                                text = "Detected Conditions:",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .titleMedium,
                                                                color = Color(0xFF000000),
                                                                fontWeight = FontWeight.Bold,
                                                                modifier =
                                                                        Modifier.padding(
                                                                                bottom = 8.dp
                                                                        )
                                                        )

                                                        // List each detected condition
                                                        detectedConditions.forEach { condition ->
                                                                Row(
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        vertical =
                                                                                                4.dp
                                                                                ),
                                                                        verticalAlignment =
                                                                                Alignment
                                                                                        .CenterVertically
                                                                ) {
                                                                        Text(
                                                                                text = "•",
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .bodyLarge,
                                                                                color = Color(0xFF000000),
                                                                                modifier =
                                                                                        Modifier.padding(
                                                                                                end =
                                                                                                        8.dp
                                                                                        )
                                                                        )
                                                                        Text(
                                                                                text = condition,
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .bodyMedium,
                                                                                fontWeight =
                                                                                        FontWeight
                                                                                                .Bold,
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

                                // Action buttons inside the assessment card
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                        // View Respiratory Graph button
                                        Button(
                                                onClick = { showGraphDialog = true },
                                                colors =
                                                        ButtonDefaults.buttonColors(
                                                                containerColor =
                                                                        Color(
                                                                                0xFF673AB7
                                                                        ) // Deep purple
                                                        ),
                                                modifier = Modifier.weight(1f).padding(end = 4.dp)
                                        ) { Text("View Graph", fontSize = 14.sp) }

                                        // Recommendations button (only if recommendations exist)
                                        if (recommendations.isNotEmpty()) {
                                                Button(
                                                        onClick = {
                                                                showRecommendationsDialog = true
                                                        },
                                                        colors =
                                                                ButtonDefaults.buttonColors(
                                                                        containerColor =
                                                                                Color(
                                                                                        0xFF3F51B5
                                                                                ) // Indigo
                                                                ),
                                                        modifier =
                                                                Modifier.weight(1f)
                                                                        .padding(start = 4.dp)
                                                ) { Text("Recommendations", fontSize = 14.sp) }
                                        }
                                }
                        }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Action buttons - using the original layout
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
                        ) { Text("Save Data") }

                        Button(
                                onClick = onSaveGraph,
                                modifier = Modifier.weight(1f),
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF009688)
                                        )
                        ) { Text("Save Graph") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                        onClick = onStartRecording,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) { Text("Record Again") }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                        onClick = onNewPatient,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
                ) { Text("New Patient") }

                // FIXED: Added bottom spacer to ensure button is fully visible
                Spacer(modifier = Modifier.height(32.dp))
        }

        // Recommendations Dialog
        if (showRecommendationsDialog) {
                androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showRecommendationsDialog = false }
                ) {
                        Card(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor =
                                                        Color(0xFFE3F2FD) // Light blue background
                                        )
                        ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                        ) {
                                                Text(
                                                        text = "Recommendations",
                                                        style = MaterialTheme.typography.titleLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF000000)

                                                )

                                                // Close button
                                                IconButton(
                                                        onClick = {
                                                                showRecommendationsDialog = false
                                                        }
                                                ) { Text("✕", fontSize = 24.sp, color = Color(0xFF000000)) }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // List each recommendation
                                        recommendations.forEach { recommendation ->
                                                Row(
                                                        modifier =
                                                                Modifier.padding(vertical = 4.dp),
                                                        verticalAlignment = Alignment.Top
                                                ) {
                                                        Text(
                                                                text = "•",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .bodyLarge,
                                                                color = Color(0xFF000000),
                                                                modifier =
                                                                        Modifier.padding(
                                                                                end = 8.dp,
                                                                                top = 2.dp
                                                                        )
                                                        )
                                                        Text(
                                                                text = recommendation,
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .bodyMedium,
                                                                color = Color(0xFF000000)
                                                        )
                                                }
                                        }

                                        // Disclaimer
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                                text =
                                                        "Note: This is not a medical diagnosis. Consult a healthcare professional for proper evaluation.",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontStyle =
                                                        androidx.compose.ui.text.font.FontStyle
                                                                .Italic,
                                                color = Color.Gray
                                        )
                                }
                        }
                }
        }

        // Graph Dialog - using the original RespirationChart component
        if (showGraphDialog) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showGraphDialog = false }) {
                        Card(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .fillMaxHeight(0.8f) // Use 80% of screen height
                                                .padding(8.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor =
                                                        Color(
                                                                0xFF212121
                                                        ) // Dark background for graph
                                        )
                        ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                        Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                        ) {
                                                Text(
                                                        text = "Respiratory Graph",
                                                        style = MaterialTheme.typography.titleLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                )

                                                // Close button
                                                IconButton(onClick = { showGraphDialog = false }) {
                                                        Text(
                                                                "✕",
                                                                fontSize = 24.sp,
                                                                color = Color.White
                                                        )
                                                }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Original RespirationChart (a scrollable visualization
                                        // with phase colors)
                                        if (respiratoryData.isNotEmpty()) {
                                                RespirationChart(
                                                        respiratoryData = respiratoryData,
                                                        modifier =
                                                                Modifier.fillMaxWidth().weight(1f)
                                                )
                                        } else {
                                                Box(
                                                        modifier =
                                                                Modifier.fillMaxWidth()
                                                                        .weight(1f)
                                                                        .background(Color.Black),
                                                        contentAlignment = Alignment.Center
                                                ) {
                                                        Text(
                                                                "No respiratory data available",
                                                                color = Color.White
                                                        )
                                                }
                                        }
                                }
                        }
                }
        }
}

// Helper extension functions
private fun List<RespiratoryDataPoint>.calculateIrregularityIndex(): Float {
        if (this.isEmpty()) return 0f

        // Calculate time intervals between phase changes
        val phaseChanges = mutableListOf<Long>()
        var lastPhase = ""

        this.forEach { point ->
                if (point.breathingPhase != lastPhase && lastPhase.isNotEmpty()) {
                        phaseChanges.add(point.timestamp)
                }
                lastPhase = point.breathingPhase
        }

        // Calculate coefficient of variation for intervals
        if (phaseChanges.size < 2) return 0f

        val intervals = mutableListOf<Float>()
        for (i in 1 until phaseChanges.size) {
                intervals.add((phaseChanges[i] - phaseChanges[i - 1]).toFloat())
        }

        val mean = intervals.average().toFloat()
        if (mean == 0f) return 0f

        val variance = intervals.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev = kotlin.math.sqrt(variance)

        // Return coefficient of variation (CV = stdDev / mean)
        // Capped at 1.0 for UI purposes
        return (stdDev / mean).coerceAtMost(1.0f)
}

private fun List<RespiratoryDataPoint>.calculateAmplitudeVariation(): Float {
        if (this.isEmpty()) return 0f

        val amplitudes = this.map { it.amplitude }
        val mean = amplitudes.average().toFloat()
        if (mean == 0f) return 0f

        val variance = amplitudes.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev = kotlin.math.sqrt(variance)

        // Return scaled coefficient of variation (CV = stdDev / mean * 100)
        return (stdDev / mean * 100f).coerceAtMost(100f)
}

@Composable
fun RespiratoryGraph(respiratoryData: List<RespiratoryDataPoint>, modifier: Modifier = Modifier) {
        if (respiratoryData.isEmpty()) return

        // Draw respiratory graph using Canvas
        androidx.compose.foundation.Canvas(modifier = modifier.background(Color(0xFF424242))) {
                val width = size.width
                val height = size.height
                val padding = 30f

                // Determine time range from first to last data point
                val startTime = respiratoryData.first().timestamp
                val endTime = respiratoryData.last().timestamp
                val timeRange = (endTime - startTime).coerceAtLeast(1L) // Avoid division by zero

                // Calculate min and max position values for scaling
                val minY = respiratoryData.minByOrNull { it.position.y }?.position?.y ?: 0f
                val maxY = respiratoryData.maxByOrNull { it.position.y }?.position?.y ?: 0f
                val yRange = (maxY - minY).coerceAtLeast(1f) // Avoid division by zero

                // Helper function to convert data points to canvas coordinates
                fun dataPointToCanvasPoint(
                        point: RespiratoryDataPoint
                ): androidx.compose.ui.geometry.Offset {
                        val x =
                                padding +
                                        (point.timestamp - startTime) / timeRange.toFloat() *
                                                (width - 2 * padding)
                        val y =
                                height -
                                        padding -
                                        (point.position.y - minY) / yRange * (height - 2 * padding)
                        return androidx.compose.ui.geometry.Offset(x, y)
                }

                // Draw axes
                val axisColor = androidx.compose.ui.graphics.Color.White
                drawLine(
                        color = axisColor,
                        start = androidx.compose.ui.geometry.Offset(padding, padding),
                        end = androidx.compose.ui.geometry.Offset(padding, height - padding),
                        strokeWidth = 2f
                )
                drawLine(
                        color = axisColor,
                        start = androidx.compose.ui.geometry.Offset(padding, height - padding),
                        end =
                                androidx.compose.ui.geometry.Offset(
                                        width - padding,
                                        height - padding
                                ),
                        strokeWidth = 2f
                )

                // Draw data points for each breathing phase
                var lastPoint: androidx.compose.ui.geometry.Offset? = null
                var lastPhase = ""

                respiratoryData.forEach { point ->
                        val canvasPoint = dataPointToCanvasPoint(point)

                        // Determine color based on breathing phase
                        val lineColor =
                                when (point.breathingPhase.lowercase()) {
                                        "inhaling" ->
                                                androidx.compose.ui.graphics.Color(
                                                        0xFF4CAF50
                                                ) // Green
                                        "exhaling" ->
                                                androidx.compose.ui.graphics.Color(
                                                        0xFF2196F3
                                                ) // Blue
                                        "pause" ->
                                                androidx.compose.ui.graphics.Color(
                                                        0xFFFFC107
                                                ) // Yellow/Amber
                                        else -> androidx.compose.ui.graphics.Color.Gray
                                }

                        // Draw point
                        drawCircle(color = lineColor, radius = 2f, center = canvasPoint)

                        // Draw line to previous point if same phase
                        if (lastPoint != null && point.breathingPhase == lastPhase) {
                                drawLine(
                                        color = lineColor,
                                        start = lastPoint!!,
                                        end = canvasPoint,
                                        strokeWidth = 2f
                                )
                        }

                        lastPoint = canvasPoint
                        lastPhase = point.breathingPhase
                }

                // Draw time markers on x-axis (seconds)
                val secondsInterval =
                        kotlin.math.max(
                                1,
                                (timeRange / 1000) / 5
                        ) // Adjust for reasonable number of markers
                val textPaint =
                        android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = 24f
                                textAlign = android.graphics.Paint.Align.CENTER
                        }

                for (second in 0..(timeRange / 1000) step secondsInterval) {
                        val x =
                                padding +
                                        second * 1000 / timeRange.toFloat() * (width - 2 * padding)
                        drawLine(
                                color = axisColor.copy(alpha = 0.5f),
                                start = androidx.compose.ui.geometry.Offset(x, height - padding),
                                end = androidx.compose.ui.geometry.Offset(x, height - padding + 10),
                                strokeWidth = 1f
                        )

                        drawContext.canvas.nativeCanvas.drawText(
                                "${second}s",
                                x,
                                height - padding / 3,
                                textPaint
                        )
                }

                // Draw y-axis labels (optional)
                val yTextPaint =
                        android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = 24f
                                textAlign = android.graphics.Paint.Align.RIGHT
                        }

                drawContext.canvas.nativeCanvas.drawText(
                        "Position",
                        padding - 10f,
                        padding - 10f,
                        yTextPaint
                )
        }
}
