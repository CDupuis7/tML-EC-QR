package com.example.tml_ec_qr_scan

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tml_ec_qr_scan.ui.theme.TMLEC_QRScanTheme

class NFCWriteActivity : ComponentActivity() {
    private lateinit var nfcManager: NFCManager
    private var writeMode by mutableStateOf(false)
    private var patientDataToWrite: PatientNFCData? = null

    companion object {
        private const val TAG = "NFCWriteActivity"

        // Intent extras for pre-filling data
        const val EXTRA_PATIENT_ID = "patient_id"
        const val EXTRA_PATIENT_AGE = "patient_age"
        const val EXTRA_PATIENT_GENDER = "patient_gender"
        const val EXTRA_PATIENT_HEALTH = "patient_health"

        /** Create intent to launch NFC write activity with pre-filled data */
        fun createIntent(activity: Activity, patientData: PatientMetadata? = null): Intent {
            return Intent(activity, NFCWriteActivity::class.java).apply {
                patientData?.let { data ->
                    putExtra(EXTRA_PATIENT_ID, data.id)
                    putExtra(EXTRA_PATIENT_AGE, data.age)
                    putExtra(EXTRA_PATIENT_GENDER, data.gender)
                    putExtra(EXTRA_PATIENT_HEALTH, data.healthStatus)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize NFC manager
        nfcManager = NFCManager(this)

        // Get pre-filled data from intent
        val prefilledData =
                intent?.let { intent ->
                    val id = intent.getStringExtra(EXTRA_PATIENT_ID) ?: ""
                    val age = intent.getIntExtra(EXTRA_PATIENT_AGE, 0)
                    val gender = intent.getStringExtra(EXTRA_PATIENT_GENDER) ?: ""
                    val health = intent.getStringExtra(EXTRA_PATIENT_HEALTH) ?: ""

                    if (id.isNotEmpty()) {
                        PatientNFCData(id = id, age = age, gender = gender, healthStatus = health)
                    } else null
                }

        setContent {
            TMLEC_QRScanTheme {
                Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                ) {
                    NFCWriteScreen(
                            prefilledData = prefilledData,
                            nfcStatus = nfcManager.getNFCStatusMessage(),
                            isNFCAvailable = nfcManager.isNFCAvailable(),
                            isNFCEnabled = nfcManager.isNFCEnabled(),
                            writeMode = writeMode,
                            onBackClick = { finish() },
                            onPrepareWrite = { patientData -> prepareForWriting(patientData) },
                            onCancelWrite = { cancelWriteMode() },
                            onOpenNFCSettings = { nfcManager.showNFCSettings() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (nfcManager.isNFCAvailable() && nfcManager.isNFCEnabled()) {
            nfcManager.enableForegroundDispatch(this)
            Log.d(TAG, "✅ NFC foreground dispatch enabled in onResume")
        }
    }

    override fun onPause() {
        super.onPause()
        if (nfcManager.isNFCAvailable()) {
            nfcManager.disableForegroundDispatch(this)
            Log.d(TAG, "✅ NFC foreground dispatch disabled in onPause")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "📱 onNewIntent called with action: ${intent.action}")

        if (writeMode && patientDataToWrite != null) {
            handleWriteIntent(intent)
        }
    }

    private fun prepareForWriting(patientData: PatientNFCData) {
        if (!nfcManager.isNFCAvailable()) {
            Toast.makeText(this, "❌ NFC is not available on this device", Toast.LENGTH_LONG).show()
            return
        }

        if (!nfcManager.isNFCEnabled()) {
            Toast.makeText(this, "⚠️ Please enable NFC in settings", Toast.LENGTH_LONG).show()
            nfcManager.showNFCSettings()
            return
        }

        patientDataToWrite = patientData
        writeMode = true

        Toast.makeText(
                        this,
                        "📱 Ready to write! Please tap an NFC tag to write patient data",
                        Toast.LENGTH_LONG
                )
                .show()

        Log.d(TAG, "📝 Prepared to write patient data: $patientData")
    }

    private fun cancelWriteMode() {
        writeMode = false
        patientDataToWrite = null

        Toast.makeText(this, "❌ Write mode cancelled", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "❌ Write mode cancelled")
    }

    private fun handleWriteIntent(intent: Intent) {
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        val patientData = patientDataToWrite

        if (tag != null && patientData != null) {
            Log.d(TAG, "🏷️ NFC tag detected for writing: ${tag.id}")

            val success = nfcManager.writeToTag(tag, patientData)

            if (success) {
                writeMode = false
                patientDataToWrite = null

                Toast.makeText(
                                this,
                                "✅ Patient data successfully written to NFC tag!",
                                Toast.LENGTH_LONG
                        )
                        .show()

                Log.d(TAG, "✅ Successfully wrote patient data to NFC tag")
            } else {
                Toast.makeText(this, "❌ Failed to write patient data to NFC tag", Toast.LENGTH_LONG)
                        .show()

                Log.e(TAG, "❌ Failed to write patient data to NFC tag")
            }
        } else {
            Log.w(TAG, "⚠️ No tag or patient data available for writing")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NFCWriteScreen(
        prefilledData: PatientNFCData? = null,
        nfcStatus: String,
        isNFCAvailable: Boolean,
        isNFCEnabled: Boolean,
        writeMode: Boolean,
        onBackClick: () -> Unit,
        onPrepareWrite: (PatientNFCData) -> Unit,
        onCancelWrite: () -> Unit,
        onOpenNFCSettings: () -> Unit
) {
    // Form state
    var patientId by remember { mutableStateOf(prefilledData?.id ?: "") }
    var age by remember { mutableStateOf(prefilledData?.age?.toString() ?: "") }
    var gender by remember { mutableStateOf(prefilledData?.gender ?: "") }
    var healthCondition by remember { mutableStateOf(prefilledData?.healthStatus ?: "") }

    // Dropdown options
    val genderOptions = listOf("Male", "Female", "Other")
    val healthConditionOptions =
            listOf("Healthy", "Asthmatic", "COPD", "Respiratory Infection", "Other")

    // Dropdown expanded states
    var genderExpanded by remember { mutableStateOf(false) }
    var healthConditionExpanded by remember { mutableStateOf(false) }

    Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Back button and title
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                    text = "Write Patient NFC Tag",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // NFC Status Card
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                        CardDefaults.cardColors(
                                containerColor =
                                        when {
                                            !isNFCAvailable -> Color(0xFFFFEBEE) // Light red
                                            !isNFCEnabled -> Color(0xFFFFF3E0) // Light orange
                                            else -> Color(0xFFE8F5E9) // Light green
                                        }
                        )
        ) {
            Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                        text = "NFC Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                        text = nfcStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                                when {
                                    !isNFCAvailable -> Color(0xFFD32F2F) // Red
                                    !isNFCEnabled -> Color(0xFFF57C00) // Orange
                                    else -> Color(0xFF388E3C) // Green
                                }
                )

                if (!isNFCEnabled && isNFCAvailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                            onClick = onOpenNFCSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00))
                    ) { Text("Open NFC Settings") }
                }
            }
        }

        // Patient Information Form
        if (isNFCAvailable && isNFCEnabled) {
            Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                            CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                            text = "Patient Information",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                            value = patientId,
                            onValueChange = { patientId = it },
                            label = { Text("Patient ID") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !writeMode
                    )

                    OutlinedTextField(
                            value = age,
                            onValueChange = {
                                if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                    age = it
                                }
                            },
                            label = { Text("Age") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !writeMode
                    )

                    // Gender dropdown
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                                value = gender,
                                onValueChange = {},
                                label = { Text("Gender") },
                                modifier = Modifier.fillMaxWidth(),
                                readOnly = true,
                                enabled = !writeMode,
                                trailingIcon = {
                                    IconButton(
                                            onClick = { if (!writeMode) genderExpanded = true }
                                    ) { Icon(Icons.Default.ArrowDropDown, "Dropdown") }
                                }
                        )

                        if (!writeMode) {
                            Box(
                                    modifier =
                                            Modifier.matchParentSize().clickable {
                                                genderExpanded = true
                                            }
                            )
                        }

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
                                onValueChange = {},
                                label = { Text("Health Condition") },
                                modifier = Modifier.fillMaxWidth(),
                                readOnly = true,
                                enabled = !writeMode,
                                trailingIcon = {
                                    IconButton(
                                            onClick = {
                                                if (!writeMode) healthConditionExpanded = true
                                            }
                                    ) { Icon(Icons.Default.ArrowDropDown, "Dropdown") }
                                }
                        )

                        if (!writeMode) {
                            Box(
                                    modifier =
                                            Modifier.matchParentSize().clickable {
                                                healthConditionExpanded = true
                                            }
                            )
                        }

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
                }
            }

            // Write/Cancel Buttons
            if (writeMode) {
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = Color(0xFFE3F2FD) // Light blue
                                )
                ) {
                    Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                                text = "📱 Ready to Write!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1976D2)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                                text = "Please tap an NFC tag to write the patient data",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF1976D2)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                                onClick = onCancelWrite,
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFFF5722)
                                        ),
                                modifier = Modifier.fillMaxWidth()
                        ) { Text("Cancel Write Mode") }
                    }
                }
            } else {
                // Write button
                val isFormValid =
                        patientId.isNotBlank() &&
                                age.isNotBlank() &&
                                gender.isNotBlank() &&
                                healthCondition.isNotBlank()

                Button(
                        onClick = {
                            val patientData =
                                    PatientNFCData(
                                            id = patientId,
                                            age = age.toIntOrNull() ?: 0,
                                            gender = gender,
                                            healthStatus = healthCondition
                                    )
                            onPrepareWrite(patientData)
                        },
                        enabled = isFormValid,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF4CAF50),
                                        disabledContainerColor = Color(0xFFBDBDBD)
                                )
                ) {
                    Text(
                            text = "📝 Prepare to Write NFC Tag",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                    )
                }
            }

            // Instructions
            Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                            text = "📋 Instructions:",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Text(
                            text = "1. Fill in all patient information fields",
                            style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                            text = "2. Tap 'Prepare to Write NFC Tag' button",
                            style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                            text = "3. Hold an NFC tag near your device",
                            style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                            text = "4. The patient data will be written to the tag",
                            style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                            text = "💡 Tip: Use NTAG213/215/216 tags for best compatibility",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF666666)
                    )
                }
            }
        }
    }
}
