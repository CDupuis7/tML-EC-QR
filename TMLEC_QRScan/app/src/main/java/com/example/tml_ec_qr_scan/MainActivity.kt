package com.example.tml_ec_qr_scan

// import androidx.compose.ui.text.input.KeyboardOptions
// import androidx.compose.material3.ExposedDropdownMenu
// import androidx.compose.material3.icons.Icons
// import androidx.compose.material3.icons.filled.KeyboardArrowDown
// import androidx.compose.material3.icons.filled.KeyboardArrowUp
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import boofcv.android.ConvertBitmap
import boofcv.factory.fiducial.FactoryFiducial
import boofcv.struct.image.GrayU8
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class BoofCvQrDetection(val rawValue: String?, val center: Offset, val corners: List<Offset>)

data class TrackedPoint(
        val center: Offset,
        val lastUpdateTime: Long,
        val velocity: Offset,
        val isLocked: Boolean = false,
        val initialPosition: Offset? = null
)

data class RespiratoryDataPoint(
        val timestamp: Long,
        val position: Offset,
        val qrId: String,
        val movement: String = "unknown",
        val breathingPhase: String = "unknown",
        val amplitude: Float = 0f,
        val velocity: Float = 0f
)

data class BreathingMetrics(
        val breathingRate: Float, // breaths per minute
        val averageAmplitude: Float, // average chest movement
        val maxAmplitude: Float, // maximum chest movement
        val minAmplitude: Float, // minimum chest movement
        val breathCount: Int // total number of breaths
)

data class PatientMetadata(
        val id: String,
        val age: Int,
        val gender: String,
        val healthStatus: String,
        val additionalNotes: String = ""
)

fun Offset.distanceTo(other: Offset): Float {
        return sqrt((this.x - other.x).pow(2) + (this.y - other.y).pow(2))
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
        private lateinit var cameraExecutor: ExecutorService

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                enableEdgeToEdge()
                cameraExecutor = Executors.newSingleThreadExecutor()

                setContent {
                        // Move state declarations outside remember blocks
                        val context = LocalContext.current
                        val lifecycleOwner = LocalLifecycleOwner.current

                        // State declarations
                        var qrResult by remember { mutableStateOf("") }
                        var boofDetections by remember {
                                mutableStateOf<List<BoofCvQrDetection>>(emptyList())
                        }
                        var imageWidth by remember { mutableStateOf(1f) }
                        var imageHeight by remember { mutableStateOf(1f) }
                        var rotationDegrees by remember { mutableStateOf(0) }
                        var isTracking by remember { mutableStateOf(false) }
                        var trackingStartTime by remember { mutableStateOf(0L) }
                        var respiratoryData by remember {
                                mutableStateOf<MutableList<RespiratoryDataPoint>>(mutableListOf())
                        }
                        var currentBreathingPhase by remember { mutableStateOf("Unknown") }
                        var breathingConfidence by remember { mutableStateOf(0.0f) }
                        var showMetadataForm by remember { mutableStateOf(true) }
                        var currentPatientMetadata by remember {
                                mutableStateOf<PatientMetadata?>(null)
                        }

                        // Constants and non-state variables
                        val smoothingFactor = 0.95f
                        val smoothedCenters = remember { mutableMapOf<String, TrackedPoint>() }

                        // Create breathing classifier outside remember
                        val breathingClassifier =
                                produceState<BreathingClassifier?>(initialValue = null) {
                                                value = BreathingClassifier(context)
                                                awaitDispose { value?.close() }
                                        }
                                        .value

                        LaunchedEffect(Unit) {
                                Log.d("BreathingClassifierInit", "Initializing BreathingClassifier")
                        }

                        LaunchedEffect(currentBreathingPhase, breathingConfidence) {
                                Log.d(
                                        "BreathingState",
                                        "UI Update: Phase: $currentBreathingPhase, Confidence: $breathingConfidence"
                                )
                        }

                        // Add state observer for breathing phase updates
                        LaunchedEffect(key1 = currentBreathingPhase, key2 = breathingConfidence) {
                                Log.d(
                                        "BreathingStateObserver",
                                        "State changed: Phase=$currentBreathingPhase, Confidence=${(breathingConfidence * 100).toInt()}%"
                                )
                        }

                        MaterialTheme {
                                Scaffold(
                                        topBar = {
                                                CenterAlignedTopAppBar(
                                                        title = {
                                                                Text(
                                                                        "Respiratory QR Monitor",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .titleLarge
                                                                )
                                                        }
                                                )
                                        }
                                ) { innerPadding ->
                                        Column(
                                                modifier =
                                                        Modifier.fillMaxSize().padding(innerPadding)
                                        ) {
                                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                                        CameraPreview(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                onDetections = {
                                                                        detections,
                                                                        imgW,
                                                                        imgH,
                                                                        rotation ->
                                                                        val currentTime =
                                                                                System.currentTimeMillis()

                                                                        val stabilizedDetections =
                                                                                detections.map {
                                                                                        detection ->
                                                                                        processDetections(
                                                                                                detection,
                                                                                                currentTime,
                                                                                                smoothedCenters
                                                                                        )
                                                                                }

                                                                        boofDetections =
                                                                                stabilizedDetections
                                                                        imageWidth = imgW
                                                                        imageHeight = imgH
                                                                        rotationDegrees = rotation
                                                                        qrResult =
                                                                                stabilizedDetections
                                                                                        .joinToString {
                                                                                                it.rawValue
                                                                                                        ?: "unknown"
                                                                                        }

                                                                        if (stabilizedDetections
                                                                                        .isNotEmpty()
                                                                        ) {
                                                                                val detection =
                                                                                        stabilizedDetections
                                                                                                .first()
                                                                                val key =
                                                                                        detection
                                                                                                .rawValue
                                                                                                ?: "unknown"

                                                                                // Calculate
                                                                                // velocity for
                                                                                // this point
                                                                                var velocity = 0f
                                                                                if (respiratoryData
                                                                                                .isNotEmpty()
                                                                                ) {
                                                                                        val lastPoint =
                                                                                                respiratoryData
                                                                                                        .last()
                                                                                        val timeDiff =
                                                                                                (currentTime -
                                                                                                        trackingStartTime -
                                                                                                        lastPoint
                                                                                                                .timestamp) /
                                                                                                        1000f
                                                                                        if (timeDiff >
                                                                                                        0
                                                                                        ) {
                                                                                                velocity =
                                                                                                        (detection
                                                                                                                .center
                                                                                                                .y -
                                                                                                                lastPoint
                                                                                                                        .position
                                                                                                                        .y) /
                                                                                                                timeDiff
                                                                                                Log.d(
                                                                                                        "Velocity",
                                                                                                        "Raw velocity: $velocity, timeDiff: $timeDiff, y-diff: ${detection.center.y - lastPoint.position.y}, isTracking: $isTracking"
                                                                                                )
                                                                                        }
                                                                                }

                                                                                // Create new
                                                                                // respiratory
                                                                                // data point
                                                                                val dataPoint =
                                                                                        RespiratoryDataPoint(
                                                                                                timestamp =
                                                                                                        currentTime -
                                                                                                                trackingStartTime,
                                                                                                position =
                                                                                                        detection
                                                                                                                .center,
                                                                                                qrId =
                                                                                                        key,
                                                                                                movement =
                                                                                                        if (velocity <
                                                                                                                        -5f
                                                                                                        )
                                                                                                                "upward"
                                                                                                        else if (velocity >
                                                                                                                        5f
                                                                                                        )
                                                                                                                "downward"
                                                                                                        else
                                                                                                                "stable",
                                                                                                breathingPhase =
                                                                                                        determineBreathingPhase(
                                                                                                                velocity
                                                                                                        ),
                                                                                                amplitude =
                                                                                                        detection
                                                                                                                .center
                                                                                                                .y -
                                                                                                                (if (respiratoryData
                                                                                                                                .isNotEmpty()
                                                                                                                )
                                                                                                                        respiratoryData
                                                                                                                                .first()
                                                                                                                                .position
                                                                                                                                .y
                                                                                                                else
                                                                                                                        detection
                                                                                                                                .center
                                                                                                                                .y),
                                                                                                velocity =
                                                                                                        velocity
                                                                                        )

                                                                                // Process breathing
                                                                                // phase and
                                                                                // IMMEDIATELY
                                                                                // update
                                                                                // UI state
                                                                                breathingClassifier
                                                                                        ?.let {
                                                                                                classifier
                                                                                                ->
                                                                                                try {
                                                                                                        val result =
                                                                                                                classifier
                                                                                                                        .processNewDataPoint(
                                                                                                                                dataPoint
                                                                                                                        )
                                                                                                        // Force UI update with direct state assignments
                                                                                                        currentBreathingPhase =
                                                                                                                result.phase
                                                                                                                        .replaceFirstChar {
                                                                                                                                it.uppercase()
                                                                                                                        }
                                                                                                        breathingConfidence =
                                                                                                                result.confidence

                                                                                                        // Enhanced logging for debugging
                                                                                                        Log.d(
                                                                                                                "BreathingUI",
                                                                                                                """
                                                                            UI STATE UPDATE:
                                                                            Phase: ${result.phase}
                                                                            Previous Phase: $currentBreathingPhase
                                                                            Confidence: ${result.confidence}
                                                                            Velocity: $velocity
                                                                            Is Tracking: $isTracking
                                                                            Data Point: ${dataPoint.movement}, ${dataPoint.breathingPhase}
                                                                            """.trimIndent()
                                                                                                        )
                                                                                                } catch (
                                                                                                        e:
                                                                                                                Exception) {
                                                                                                        Log.e(
                                                                                                                "BreathingClassifier",
                                                                                                                "Error processing data point: ${e.message}"
                                                                                                        )
                                                                                                }
                                                                                        }
                                                                                        ?: run {
                                                                                                Log.e(
                                                                                                        "BreathingClassifier",
                                                                                                        "Classifier is null!"
                                                                                                )
                                                                                                // Fall back to direct phase detection if classifier is not available
                                                                                                currentBreathingPhase =
                                                                                                        dataPoint
                                                                                                                .breathingPhase
                                                                                                                .replaceFirstChar {
                                                                                                                        it.uppercase()
                                                                                                                }
                                                                                                breathingConfidence =
                                                                                                        if (dataPoint
                                                                                                                        .breathingPhase ==
                                                                                                                        "pause"
                                                                                                        )
                                                                                                                0.5f
                                                                                                        else
                                                                                                                0.7f
                                                                                                Log.d(
                                                                                                        "BreathingUI",
                                                                                                        "Fallback UI update: Phase=${dataPoint.breathingPhase}, Confidence=${breathingConfidence}"
                                                                                                )
                                                                                        }

                                                                                // Add to data
                                                                                // collection if
                                                                                // tracking
                                                                                if (isTracking) {
                                                                                        respiratoryData
                                                                                                .add(
                                                                                                        dataPoint
                                                                                                )
                                                                                }
                                                                        }
                                                                }
                                                        )

                                                        // QR code overlay - this needs to be
                                                        // outside the
                                                        // onDetections callback
                                                        BoofCVQRCodeOverlay(
                                                                qrDetections = boofDetections,
                                                                imageWidth = imageWidth,
                                                                imageHeight = imageHeight,
                                                                rotationDegrees = rotationDegrees,
                                                                modifier = Modifier.fillMaxSize()
                                                        )

                                                        // Real-time breathing indicator overlay
                                                        if (boofDetections.isNotEmpty()) {
                                                                Box(
                                                                        modifier =
                                                                                Modifier.align(
                                                                                                Alignment
                                                                                                        .TopEnd
                                                                                        )
                                                                                        .padding(
                                                                                                16.dp
                                                                                        )
                                                                ) {
                                                                        Column(
                                                                                horizontalAlignment =
                                                                                        Alignment
                                                                                                .CenterHorizontally,
                                                                                modifier =
                                                                                        Modifier.background(
                                                                                                        Color.Black
                                                                                                                .copy(
                                                                                                                        alpha =
                                                                                                                                0.7f
                                                                                                                ),
                                                                                                        shape =
                                                                                                                MaterialTheme
                                                                                                                        .shapes
                                                                                                                        .small
                                                                                                )
                                                                                                .padding(
                                                                                                        8.dp
                                                                                                )
                                                                        ) {
                                                                                Text(
                                                                                        text =
                                                                                                currentBreathingPhase
                                                                                                        .replaceFirstChar {
                                                                                                                if (it.isLowerCase()
                                                                                                                )
                                                                                                                        it.titlecase()
                                                                                                                else
                                                                                                                        it.toString()
                                                                                                        },
                                                                                        color =
                                                                                                when (currentBreathingPhase
                                                                                                                .lowercase()
                                                                                                ) {
                                                                                                        "inhaling" ->
                                                                                                                Color(
                                                                                                                        0xFF81C784
                                                                                                                ) // Green
                                                                                                        "exhaling" ->
                                                                                                                Color(
                                                                                                                        0xFF64B5F6
                                                                                                                ) // Blue
                                                                                                        else ->
                                                                                                                Color.White
                                                                                                },
                                                                                        style =
                                                                                                MaterialTheme
                                                                                                        .typography
                                                                                                        .titleMedium
                                                                                )
                                                                                Text(
                                                                                        text =
                                                                                                "Confidence: ${(breathingConfidence * 100).toInt()}%",
                                                                                        color =
                                                                                                Color.White,
                                                                                        style =
                                                                                                MaterialTheme
                                                                                                        .typography
                                                                                                        .bodySmall
                                                                                )

                                                                                // Add velocity
                                                                                // debug info
                                                                                if (respiratoryData
                                                                                                .isNotEmpty()
                                                                                ) {
                                                                                        val lastVelocity =
                                                                                                respiratoryData
                                                                                                        .last()
                                                                                                        .velocity
                                                                                        Text(
                                                                                                text =
                                                                                                        "Velocity: ${lastVelocity.format(1)}",
                                                                                                color =
                                                                                                        Color.Yellow,
                                                                                                style =
                                                                                                        MaterialTheme
                                                                                                                .typography
                                                                                                                .bodySmall
                                                                                        )
                                                                                }

                                                                                // Add recording
                                                                                // status
                                                                                if (isTracking) {
                                                                                        Text(
                                                                                                text =
                                                                                                        "Recording Active",
                                                                                                color =
                                                                                                        Color.Green,
                                                                                                style =
                                                                                                        MaterialTheme
                                                                                                                .typography
                                                                                                                .bodySmall
                                                                                        )
                                                                                }
                                                                        }
                                                                }
                                                        }

                                                        // Add Respiration Chart
                                                        Box(
                                                                modifier =
                                                                        Modifier.fillMaxWidth()
                                                                                .height(200.dp)
                                                                                .padding(8.dp)
                                                        ) {
                                                                RespirationChart(
                                                                        respiratoryData =
                                                                                respiratoryData,
                                                                        modifier =
                                                                                Modifier.fillMaxSize(),
                                                                        lineColor =
                                                                                Color(0xFF81C784),
                                                                        maxPoints = 150
                                                                )
                                                        }
                                                }

                                                Column(
                                                        modifier =
                                                                Modifier.fillMaxWidth()
                                                                        .padding(16.dp),
                                                        horizontalAlignment =
                                                                Alignment.CenterHorizontally
                                                ) {
                                                        if (showMetadataForm) {
                                                                MetadataInputForm(
                                                                        onSubmit = { metadata ->
                                                                                currentPatientMetadata =
                                                                                        metadata
                                                                                showMetadataForm =
                                                                                        false
                                                                        }
                                                                )
                                                        } else {
                                                                // Show recording controls only
                                                                // after metadata is entered
                                                                Column(
                                                                        modifier =
                                                                                Modifier.fillMaxWidth(),
                                                                        horizontalAlignment =
                                                                                Alignment
                                                                                        .CenterHorizontally
                                                                ) {
                                                                        Text(
                                                                                "Patient ID: ${currentPatientMetadata?.id}",
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .titleMedium
                                                                        )

                                                                        Spacer(
                                                                                modifier =
                                                                                        Modifier.height(
                                                                                                8.dp
                                                                                        )
                                                                        )

                                                                        Button(
                                                                                onClick = {
                                                                                        if (!isTracking
                                                                                        ) {
                                                                                                // START RECORDING - initialize everything properly
                                                                                                isTracking =
                                                                                                        true
                                                                                                trackingStartTime =
                                                                                                        System.currentTimeMillis()
                                                                                                respiratoryData
                                                                                                        .clear()

                                                                                                // Initialize with some default phase for better UX
                                                                                                // but don't reset if already detecting something
                                                                                                if (currentBreathingPhase
                                                                                                                .lowercase() ==
                                                                                                                "unknown"
                                                                                                ) {
                                                                                                        currentBreathingPhase =
                                                                                                                "Pause"
                                                                                                        breathingConfidence =
                                                                                                                0.5f
                                                                                                }

                                                                                                Log.d(
                                                                                                        "Tracking",
                                                                                                        "Started tracking: isTracking=$isTracking, Phase=$currentBreathingPhase, Confidence=$breathingConfidence"
                                                                                                )

                                                                                                // Execute initial breathing phase detection if QR code is detected
                                                                                                if (boofDetections
                                                                                                                .isNotEmpty() &&
                                                                                                                breathingClassifier !=
                                                                                                                        null
                                                                                                ) {
                                                                                                        // Force an immediate update with current detection to jumpstart the detection
                                                                                                        val detection =
                                                                                                                boofDetections
                                                                                                                        .first()
                                                                                                        val dataPoint =
                                                                                                                RespiratoryDataPoint(
                                                                                                                        timestamp =
                                                                                                                                0,
                                                                                                                        position =
                                                                                                                                detection
                                                                                                                                        .center,
                                                                                                                        qrId =
                                                                                                                                detection
                                                                                                                                        .rawValue
                                                                                                                                        ?: "unknown",
                                                                                                                        movement =
                                                                                                                                "stable",
                                                                                                                        breathingPhase =
                                                                                                                                "pause",
                                                                                                                        amplitude =
                                                                                                                                0f,
                                                                                                                        velocity =
                                                                                                                                0f
                                                                                                                )
                                                                                                        respiratoryData
                                                                                                                .add(
                                                                                                                        dataPoint
                                                                                                                )

                                                                                                        Toast.makeText(
                                                                                                                        context,
                                                                                                                        "Recording started. Begin breathing normally.",
                                                                                                                        Toast.LENGTH_SHORT
                                                                                                                )
                                                                                                                .show()
                                                                                                }
                                                                                        } else {
                                                                                                // STOP RECORDING
                                                                                                isTracking =
                                                                                                        false
                                                                                                currentPatientMetadata
                                                                                                        ?.let {
                                                                                                                metadata
                                                                                                                ->
                                                                                                                saveRespiratoryData(
                                                                                                                        context,
                                                                                                                        respiratoryData,
                                                                                                                        metadata
                                                                                                                )
                                                                                                        }
                                                                                                Log.d(
                                                                                                        "Tracking",
                                                                                                        "Stopped tracking: isTracking=$isTracking"
                                                                                                )
                                                                                        }
                                                                                }
                                                                        ) {
                                                                                Text(
                                                                                        if (isTracking
                                                                                        )
                                                                                                "Stop Recording"
                                                                                        else
                                                                                                "Start Recording"
                                                                                )
                                                                        }

                                                                        Button(
                                                                                onClick = {
                                                                                        showMetadataForm =
                                                                                                true
                                                                                        isTracking =
                                                                                                false
                                                                                        respiratoryData
                                                                                                .clear()
                                                                                },
                                                                                modifier =
                                                                                        Modifier.padding(
                                                                                                top =
                                                                                                        8.dp
                                                                                        )
                                                                        ) { Text("New Patient") }

                                                                        Spacer(
                                                                                modifier =
                                                                                        Modifier.height(
                                                                                                8.dp
                                                                                        )
                                                                        )
                                                                        Button(
                                                                                onClick = {
                                                                                        // Simplify
                                                                                        // the test
                                                                                        // to avoid
                                                                                        // complex
                                                                                        // logic
                                                                                        // that
                                                                                        // might
                                                                                        // fail
                                                                                        // Test both
                                                                                        // inhaling
                                                                                        // and
                                                                                        // exhaling
                                                                                        // directly

                                                                                        // First
                                                                                        // test
                                                                                        // inhaling
                                                                                        // with
                                                                                        // extreme
                                                                                        // velocity
                                                                                        val inhaleVelocity =
                                                                                                -30f // Strong upward motion (inhaling)
                                                                                        val inhalePoint =
                                                                                                RespiratoryDataPoint(
                                                                                                        timestamp =
                                                                                                                System.currentTimeMillis() -
                                                                                                                        trackingStartTime,
                                                                                                        position =
                                                                                                                Offset(
                                                                                                                        0f,
                                                                                                                        0f
                                                                                                                ), // Simple placeholder
                                                                                                        qrId =
                                                                                                                "test",
                                                                                                        movement =
                                                                                                                "upward",
                                                                                                        breathingPhase =
                                                                                                                "inhaling",
                                                                                                        velocity =
                                                                                                                inhaleVelocity
                                                                                                )

                                                                                        breathingClassifier
                                                                                                ?.let {
                                                                                                        classifier
                                                                                                        ->
                                                                                                        try {
                                                                                                                // Process inhale point
                                                                                                                val inhaleResult =
                                                                                                                        classifier
                                                                                                                                .processNewDataPoint(
                                                                                                                                        inhalePoint
                                                                                                                                )

                                                                                                                // Force UI update immediately
                                                                                                                currentBreathingPhase =
                                                                                                                        "Inhaling"
                                                                                                                breathingConfidence =
                                                                                                                        0.9f

                                                                                                                // Show debug toast
                                                                                                                Toast.makeText(
                                                                                                                                context,
                                                                                                                                "Forced update to Inhaling (conf: 90%)",
                                                                                                                                Toast.LENGTH_SHORT
                                                                                                                        )
                                                                                                                        .show()

                                                                                                                // Schedule exhaling test after a short delay
                                                                                                                android.os
                                                                                                                        .Handler(
                                                                                                                                android.os
                                                                                                                                        .Looper
                                                                                                                                        .getMainLooper()
                                                                                                                        )
                                                                                                                        .postDelayed(
                                                                                                                                {
                                                                                                                                        val exhaleVelocity =
                                                                                                                                                30f // Strong downward motion (exhaling)
                                                                                                                                        val exhalePoint =
                                                                                                                                                inhalePoint
                                                                                                                                                        .copy(
                                                                                                                                                                timestamp =
                                                                                                                                                                        System.currentTimeMillis() -
                                                                                                                                                                                trackingStartTime,
                                                                                                                                                                movement =
                                                                                                                                                                        "downward",
                                                                                                                                                                breathingPhase =
                                                                                                                                                                        "exhaling",
                                                                                                                                                                velocity =
                                                                                                                                                                        exhaleVelocity
                                                                                                                                                        )

                                                                                                                                        val exhaleResult =
                                                                                                                                                classifier
                                                                                                                                                        .processNewDataPoint(
                                                                                                                                                                exhalePoint
                                                                                                                                                        )

                                                                                                                                        // Update UI again
                                                                                                                                        currentBreathingPhase =
                                                                                                                                                "Exhaling"
                                                                                                                                        breathingConfidence =
                                                                                                                                                0.9f

                                                                                                                                        Toast.makeText(
                                                                                                                                                        context,
                                                                                                                                                        "Forced update to Exhaling (conf: 90%)",
                                                                                                                                                        Toast.LENGTH_SHORT
                                                                                                                                                )
                                                                                                                                                .show()
                                                                                                                                },
                                                                                                                                2000
                                                                                                                        ) // 2 second delay
                                                                                                        } catch (
                                                                                                                e:
                                                                                                                        Exception) {
                                                                                                                Log.e(
                                                                                                                        "ForceBreathingUpdate",
                                                                                                                        "Error: ${e.message}"
                                                                                                                )
                                                                                                                Toast.makeText(
                                                                                                                                context,
                                                                                                                                "Error updating breathing: ${e.message}",
                                                                                                                                Toast.LENGTH_SHORT
                                                                                                                        )
                                                                                                                        .show()
                                                                                                        }
                                                                                                }
                                                                                                ?: run {
                                                                                                        Toast.makeText(
                                                                                                                        context,
                                                                                                                        "Breathing classifier not initialized",
                                                                                                                        Toast.LENGTH_SHORT
                                                                                                                )
                                                                                                                .show()
                                                                                                }
                                                                                },
                                                                                modifier =
                                                                                        Modifier.padding(
                                                                                                top =
                                                                                                        8.dp
                                                                                        )
                                                                        ) {
                                                                                Text(
                                                                                        "Force Breathing Update"
                                                                                )
                                                                        }

                                                                        Spacer(
                                                                                modifier =
                                                                                        Modifier.height(
                                                                                                8.dp
                                                                                        )
                                                                        )
                                                                        Text(
                                                                                "Recording Status: ${if (isTracking) "Active" else "Stopped"}",
                                                                                fontSize = 16.sp,
                                                                                color =
                                                                                        if (isTracking
                                                                                        )
                                                                                                Color.Green
                                                                                        else
                                                                                                Color.Red
                                                                        )

                                                                        if (isTracking) {
                                                                                Text(
                                                                                        "Data points collected: ${respiratoryData.size}",
                                                                                        fontSize =
                                                                                                14.sp
                                                                                )
                                                                        }

                                                                        Spacer(
                                                                                modifier =
                                                                                        Modifier.height(
                                                                                                8.dp
                                                                                        )
                                                                        )
                                                                        Text(
                                                                                "Detected QR Codes: $qrResult",
                                                                                fontSize = 14.sp,
                                                                                color =
                                                                                        Color.DarkGray
                                                                        )

                                                                        Spacer(
                                                                                modifier =
                                                                                        Modifier.height(
                                                                                                8.dp
                                                                                        )
                                                                        )
                                                                        Text(
                                                                                "Breathing Phase: $currentBreathingPhase",
                                                                                fontSize = 16.sp,
                                                                                color =
                                                                                        when (currentBreathingPhase
                                                                                                        .lowercase()
                                                                                        ) {
                                                                                                "inhaling" ->
                                                                                                        Color(
                                                                                                                0xFF388E3C
                                                                                                        ) // Darker Green
                                                                                                "exhaling" ->
                                                                                                        Color(
                                                                                                                0xFF1976D2
                                                                                                        ) // Darker Blue
                                                                                                else ->
                                                                                                        Color.Gray
                                                                                        }
                                                                        )

                                                                        Spacer(
                                                                                modifier =
                                                                                        Modifier.height(
                                                                                                4.dp
                                                                                        )
                                                                        )

                                                                        // Debug helper - hidden
                                                                        // feature - clicking on
                                                                        // this text will force
                                                                        // re-detection
                                                                        Text(
                                                                                text =
                                                                                        "Debug: Tap to force refresh",
                                                                                fontSize = 12.sp,
                                                                                color = Color.Gray,
                                                                                modifier =
                                                                                        Modifier.padding(
                                                                                                        4.dp
                                                                                                )
                                                                                                .clickable {
                                                                                                        // Force a breathing phase re-detection with the current data
                                                                                                        if (boofDetections
                                                                                                                        .isNotEmpty() &&
                                                                                                                        respiratoryData
                                                                                                                                .isNotEmpty()
                                                                                                        ) {
                                                                                                                val detection =
                                                                                                                        boofDetections
                                                                                                                                .first()
                                                                                                                val lastPoint =
                                                                                                                        respiratoryData
                                                                                                                                .last()
                                                                                                                val timeDiff =
                                                                                                                        0.3f

                                                                                                                // Calculate test velocities
                                                                                                                val upVelocity =
                                                                                                                        -20f
                                                                                                                val downVelocity =
                                                                                                                        20f

                                                                                                                // Create test data points
                                                                                                                val inhalePoint =
                                                                                                                        RespiratoryDataPoint(
                                                                                                                                timestamp =
                                                                                                                                        System.currentTimeMillis() -
                                                                                                                                                trackingStartTime,
                                                                                                                                position =
                                                                                                                                        detection
                                                                                                                                                .center,
                                                                                                                                qrId =
                                                                                                                                        detection
                                                                                                                                                .rawValue
                                                                                                                                                ?: "unknown",
                                                                                                                                movement =
                                                                                                                                        "upward",
                                                                                                                                breathingPhase =
                                                                                                                                        "inhaling",
                                                                                                                                amplitude =
                                                                                                                                        10f,
                                                                                                                                velocity =
                                                                                                                                        upVelocity
                                                                                                                        )
                                                                                                                val exhalePoint =
                                                                                                                        RespiratoryDataPoint(
                                                                                                                                timestamp =
                                                                                                                                        System.currentTimeMillis() -
                                                                                                                                                trackingStartTime,
                                                                                                                                position =
                                                                                                                                        detection
                                                                                                                                                .center,
                                                                                                                                qrId =
                                                                                                                                        detection
                                                                                                                                                .rawValue
                                                                                                                                                ?: "unknown",
                                                                                                                                movement =
                                                                                                                                        "downward",
                                                                                                                                breathingPhase =
                                                                                                                                        "exhaling",
                                                                                                                                amplitude =
                                                                                                                                        10f,
                                                                                                                                velocity =
                                                                                                                                        downVelocity
                                                                                                                        )

                                                                                                                // Test both points and log results
                                                                                                                breathingClassifier
                                                                                                                        ?.let {
                                                                                                                                classifier
                                                                                                                                ->
                                                                                                                                val inhaleResult =
                                                                                                                                        classifier
                                                                                                                                                .processNewDataPoint(
                                                                                                                                                        inhalePoint
                                                                                                                                                )
                                                                                                                                val exhaleResult =
                                                                                                                                        classifier
                                                                                                                                                .processNewDataPoint(
                                                                                                                                                        exhalePoint
                                                                                                                                                )

                                                                                                                                Log.d(
                                                                                                                                        "BreathingTest",
                                                                                                                                        """
                                                                                        Breathing phase test:
                                                                                        Up velocity ($upVelocity) → ${inhaleResult.phase} (conf: ${inhaleResult.confidence})
                                                                                        Down velocity ($downVelocity) → ${exhaleResult.phase} (conf: ${exhaleResult.confidence})
                                                                                    """.trimIndent()
                                                                                                                                )

                                                                                                                                // Update UI with real detection
                                                                                                                                val realVelocity =
                                                                                                                                        (detection
                                                                                                                                                .center
                                                                                                                                                .y -
                                                                                                                                                lastPoint
                                                                                                                                                        .position
                                                                                                                                                        .y) /
                                                                                                                                                timeDiff
                                                                                                                                val realPoint =
                                                                                                                                        RespiratoryDataPoint(
                                                                                                                                                timestamp =
                                                                                                                                                        System.currentTimeMillis() -
                                                                                                                                                                trackingStartTime,
                                                                                                                                                position =
                                                                                                                                                        detection
                                                                                                                                                                .center,
                                                                                                                                                qrId =
                                                                                                                                                        detection
                                                                                                                                                                .rawValue
                                                                                                                                                                ?: "unknown",
                                                                                                                                                movement =
                                                                                                                                                        if (realVelocity <
                                                                                                                                                                        0
                                                                                                                                                        )
                                                                                                                                                                "upward"
                                                                                                                                                        else
                                                                                                                                                                "downward",
                                                                                                                                                breathingPhase =
                                                                                                                                                        determineBreathingPhase(
                                                                                                                                                                realVelocity
                                                                                                                                                        ),
                                                                                                                                                amplitude =
                                                                                                                                                        10f,
                                                                                                                                                velocity =
                                                                                                                                                        realVelocity
                                                                                                                                        )
                                                                                                                                val result =
                                                                                                                                        classifier
                                                                                                                                                .processNewDataPoint(
                                                                                                                                                        realPoint
                                                                                                                                                )

                                                                                                                                // Force UI update
                                                                                                                                currentBreathingPhase =
                                                                                                                                        result.phase
                                                                                                                                                .replaceFirstChar {
                                                                                                                                                        it.uppercase()
                                                                                                                                                }
                                                                                                                                breathingConfidence =
                                                                                                                                        result.confidence

                                                                                                                                // Show toast with debug info
                                                                                                                                val message =
                                                                                                                                        "Updated to ${result.phase} (${(result.confidence*100).toInt()}%), velocity: $realVelocity"
                                                                                                                                Toast.makeText(
                                                                                                                                                context,
                                                                                                                                                message,
                                                                                                                                                Toast.LENGTH_SHORT
                                                                                                                                        )
                                                                                                                                        .show()
                                                                                                                        }
                                                                                                        } else {
                                                                                                                Toast.makeText(
                                                                                                                                context,
                                                                                                                                "No QR codes detected yet",
                                                                                                                                Toast.LENGTH_SHORT
                                                                                                                        )
                                                                                                                        .show()
                                                                                                        }
                                                                                                }
                                                                        )
                                                                }
                                                        }
                                                }
                                        }
                                }
                        }
                }
        }

        override fun onDestroy() {
                super.onDestroy()
                cameraExecutor.shutdown()
        }

        @Composable
        fun CameraPreview(
                modifier: Modifier = Modifier,
                onDetections: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit
        ) {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
                val previewView = remember { PreviewView(context) }

                Box(modifier = modifier) {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth())

                        LaunchedEffect(previewView) {
                                cameraProviderFuture.addListener(
                                        {
                                                val cameraProvider = cameraProviderFuture.get()
                                                val preview =
                                                        Preview.Builder().build().also {
                                                                it.setSurfaceProvider(
                                                                        previewView.surfaceProvider
                                                                )
                                                        }

                                                val imageAnalysis =
                                                        ImageAnalysis.Builder()
                                                                .setTargetResolution(
                                                                        android.util.Size(1280, 720)
                                                                )
                                                                .setBackpressureStrategy(
                                                                        ImageAnalysis
                                                                                .STRATEGY_KEEP_ONLY_LATEST
                                                                )
                                                                .build()

                                                val analysisExecutor =
                                                        Executors.newSingleThreadExecutor()
                                                imageAnalysis.setAnalyzer(analysisExecutor) {
                                                        imageProxy ->
                                                        processImage(imageProxy, onDetections)
                                                }

                                                try {
                                                        cameraProvider.unbindAll()
                                                        cameraProvider.bindToLifecycle(
                                                                lifecycleOwner,
                                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                                preview,
                                                                imageAnalysis
                                                        )
                                                } catch (e: Exception) {
                                                        Log.e(
                                                                "CameraPreview",
                                                                "Camera binding failed",
                                                                e
                                                        )
                                                }
                                        },
                                        ContextCompat.getMainExecutor(context)
                                )
                        }
                }
        }

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        private fun processImage(
                imageProxy: ImageProxy,
                callback: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit
        ) {
                try {
                        val bitmap = convertImageProxyToBitmap(imageProxy) ?: return
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val detections = detectQrCodesBoof(bitmap)
                        callback(
                                detections,
                                bitmap.width.toFloat(),
                                bitmap.height.toFloat(),
                                rotation
                        )
                } catch (e: Exception) {
                        Log.e("CameraProcess", "Error processing image", e)
                } finally {
                        imageProxy.close()
                }
        }

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        private fun convertImageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
                val image = imageProxy.image ?: return null
                val yBuffer = image.planes[0].buffer
                val uBuffer = image.planes[1].buffer
                val vBuffer = image.planes[2].buffer

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                val nv21 = ByteArray(ySize + uSize + vSize)
                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)

                val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
                val imageBytes = out.toByteArray()
                return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }

        private fun detectQrCodesBoof(bitmap: Bitmap): List<BoofCvQrDetection> {
                return try {
                        val gray = GrayU8(bitmap.width, bitmap.height)
                        ConvertBitmap.bitmapToGray(bitmap, gray, null)
                        val detector = FactoryFiducial.qrcode(null, GrayU8::class.java)
                        detector.process(gray)

                        detector.detections.map { detection ->
                                val corners =
                                        (0 until detection.bounds.size()).map { i ->
                                                val pt = detection.bounds.get(i)
                                                Offset(pt.x.toFloat(), pt.y.toFloat())
                                        }
                                val centerX = corners.map { it.x }.average().toFloat()
                                val centerY = corners.map { it.y }.average().toFloat()

                                BoofCvQrDetection(
                                        detection.message,
                                        Offset(centerX, centerY),
                                        corners
                                )
                        }
                } catch (e: Exception) {
                        Log.e("BoofCV", "Detection failed", e)
                        emptyList()
                }
        }

        @Composable
        fun BoofCVQRCodeOverlay(
                qrDetections: List<BoofCvQrDetection>,
                imageWidth: Float,
                imageHeight: Float,
                rotationDegrees: Int,
                modifier: Modifier = Modifier
        ) {
                Canvas(modifier = modifier.fillMaxSize()) {
                        val viewWidth = size.width
                        val viewHeight = size.height

                        // Handle rotation
                        val (width, height) =
                                if (rotationDegrees % 180 == 90) {
                                        Pair(imageHeight, imageWidth)
                                } else {
                                        Pair(imageWidth, imageHeight)
                                }

                        // Calculate proper scaling factors
                        val scaleX = viewWidth / width
                        val scaleY = viewHeight / height
                        val scale = minOf(scaleX, scaleY)

                        // Calculate centering offset
                        val offsetX = (viewWidth - width * scale) / 2
                        val offsetY = (viewHeight - height * scale) / 2

                        qrDetections.forEach { detection ->
                                // Transform corners based on rotation
                                val transformedCorners =
                                        detection.corners.map { corner ->
                                                val rotatedCorner =
                                                        when (rotationDegrees) {
                                                                90 ->
                                                                        Offset(
                                                                                corner.y,
                                                                                imageWidth -
                                                                                        corner.x
                                                                        )
                                                                180 ->
                                                                        Offset(
                                                                                imageWidth -
                                                                                        corner.x,
                                                                                imageHeight -
                                                                                        corner.y
                                                                        )
                                                                270 ->
                                                                        Offset(
                                                                                imageHeight -
                                                                                        corner.y,
                                                                                corner.x
                                                                        )
                                                                else -> corner
                                                        }

                                                Offset(
                                                        x = offsetX + rotatedCorner.x * scale,
                                                        y = offsetY + rotatedCorner.y * scale
                                                )
                                        }

                                // Draw QR code bounds
                                for (i in transformedCorners.indices) {
                                        drawLine(
                                                color = Color.Green,
                                                start = transformedCorners[i],
                                                end =
                                                        transformedCorners[
                                                                (i + 1) % transformedCorners.size],
                                                strokeWidth = 3f
                                        )
                                }

                                // Draw center point and ID
                                val center =
                                        when (rotationDegrees) {
                                                90 ->
                                                        Offset(
                                                                detection.center.y,
                                                                imageWidth - detection.center.x
                                                        )
                                                180 ->
                                                        Offset(
                                                                imageWidth - detection.center.x,
                                                                imageHeight - detection.center.y
                                                        )
                                                270 ->
                                                        Offset(
                                                                imageHeight - detection.center.y,
                                                                detection.center.x
                                                        )
                                                else -> detection.center
                                        }

                                val screenCenter =
                                        Offset(
                                                x = offsetX + center.x * scale,
                                                y = offsetY + center.y * scale
                                        )

                                drawCircle(
                                        color = Color.Red.copy(alpha = 0.7f),
                                        radius = 8f,
                                        center = screenCenter
                                )

                                // Draw QR code ID
                                drawContext.canvas.nativeCanvas.apply {
                                        drawText(
                                                detection.rawValue ?: "unknown",
                                                screenCenter.x,
                                                screenCenter.y - 20,
                                                android.graphics.Paint().apply {
                                                        color = android.graphics.Color.WHITE
                                                        textSize = 30f
                                                        textAlign =
                                                                android.graphics.Paint.Align.CENTER
                                                }
                                        )
                                }
                        }
                }
        }

        fun calculateBreathingMetrics(data: List<RespiratoryDataPoint>): BreathingMetrics {
                if (data.size < 2) return BreathingMetrics(0f, 0f, 0f, 0f, 0)

                // Add velocity analysis
                val (minVel, maxVel, avgVel) = analyzeVelocityRange(data)
                Log.d("BreathingAnalysis", "Velocity range: min=$minVel, max=$maxVel, avg=$avgVel")

                val duration =
                        (data.last().timestamp - data.first().timestamp) /
                                1000f // duration in seconds
                var breathCount = 0
                var lastDirection = "unknown"
                var maxAmp = Float.MIN_VALUE
                var minAmp = Float.MAX_VALUE
                var totalAmp = 0f

                // Detect breath cycles by finding peaks and troughs
                data.windowed(3, 1).forEach { window ->
                        val mid = window[1].position.y
                        if (window[0].position.y > mid &&
                                        window[2].position.y > mid &&
                                        lastDirection != "peak"
                        ) {
                                // Found a trough (end of inhale)
                                breathCount++
                                lastDirection = "trough"
                        } else if (window[0].position.y < mid &&
                                        window[2].position.y < mid &&
                                        lastDirection != "trough"
                        ) {
                                // Found a peak (end of exhale)
                                lastDirection = "peak"
                        }

                        maxAmp = maxOf(maxAmp, mid)
                        minAmp = minOf(minAmp, mid)
                        totalAmp += mid
                }

                val breathingRate = if (duration > 0) (breathCount * 60f) / duration else 0f
                val averageAmplitude = if (data.isNotEmpty()) totalAmp / data.size else 0f

                return BreathingMetrics(
                        breathingRate = breathingRate,
                        averageAmplitude = averageAmplitude,
                        maxAmplitude = maxAmp,
                        minAmplitude = minAmp,
                        breathCount = breathCount
                )
        }

        private fun stabilizeQrPosition(
                currentCenter: Offset,
                trackedPoint: TrackedPoint?,
                currentTime: Long
        ): TrackedPoint {
                val lockingThreshold = 15f // Reduced from 30f
                val stabilityThreshold = 0.3f // Reduced from 0.5f

                if (trackedPoint == null) {
                        return TrackedPoint(
                                center = currentCenter,
                                lastUpdateTime = currentTime,
                                velocity = Offset.Zero,
                                isLocked = false,
                                initialPosition = currentCenter
                        )
                }

                // Calculate movement from initial position with exponential smoothing
                val initialPos = trackedPoint.initialPosition ?: currentCenter
                val distanceFromInitial =
                        sqrt(
                                (currentCenter.x - initialPos.x).pow(2) +
                                        (currentCenter.y - initialPos.y).pow(2)
                        )

                // Apply stronger smoothing for more stable positions
                val smoothingFactor = 0.8f
                val stabilizedCenter =
                        if (distanceFromInitial < lockingThreshold && trackedPoint.isLocked) {
                                Offset(
                                        x =
                                                trackedPoint.center.x * smoothingFactor +
                                                        currentCenter.x * (1 - smoothingFactor),
                                        y =
                                                trackedPoint.center.y * smoothingFactor +
                                                        currentCenter.y * (1 - smoothingFactor)
                                )
                        } else {
                                currentCenter
                        }

                val timeDelta = (currentTime - trackedPoint.lastUpdateTime) / 1000f
                val velocity =
                        if (timeDelta > 0) {
                                Offset(
                                        x =
                                                (stabilizedCenter.x - trackedPoint.center.x) /
                                                        timeDelta,
                                        y = (stabilizedCenter.y - trackedPoint.center.y) / timeDelta
                                )
                        } else {
                                Offset.Zero
                        }

                // More gradual locking mechanism
                val shouldLock =
                        velocity.getDistance() < stabilityThreshold ||
                                (trackedPoint.isLocked &&
                                        distanceFromInitial < lockingThreshold * 1.5f)

                return TrackedPoint(
                        center = stabilizedCenter,
                        lastUpdateTime = currentTime,
                        velocity = velocity,
                        isLocked = shouldLock,
                        initialPosition = trackedPoint.initialPosition ?: currentCenter
                )
        }

        private fun determineBreathingPhase(velocity: Float): String {
                val inhaleThreshold =
                        -5f // Threshold for inhaling (matches movement classification)
                val exhaleThreshold = 5f // Threshold for exhaling (matches movement classification)

                return when {
                        velocity < inhaleThreshold -> "inhaling"
                        velocity > exhaleThreshold -> "exhaling"
                        else -> "pause"
                }
        }

        private fun processDetections(
                detection: BoofCvQrDetection,
                currentTime: Long,
                smoothedCenters: MutableMap<String, TrackedPoint>
        ): BoofCvQrDetection {
                val key = detection.rawValue ?: "unknown"
                val trackedPoint = smoothedCenters[key]

                // Apply stabilization
                val stabilizedPoint =
                        stabilizeQrPosition(detection.center, trackedPoint, currentTime)
                smoothedCenters[key] = stabilizedPoint

                return detection.copy(center = stabilizedPoint.center)
        }

        private fun saveRespiratoryData(
                context: Context,
                data: List<RespiratoryDataPoint>,
                metadata: PatientMetadata
        ) {
                try {
                        val timestamp = System.currentTimeMillis()
                        val filename = "respiratory_data_${metadata.id}_$timestamp.csv"
                        val metrics = calculateBreathingMetrics(data)

                        // Save to Downloads folder
                        val downloadsDir =
                                Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOWNLOADS
                                )
                        val file = File(downloadsDir, filename)

                        FileOutputStream(file).bufferedWriter().use { writer ->
                                // Write metadata and metrics header
                                writer.write(
                                        """
                Patient Information
                ID: ${metadata.id}
                Age: ${metadata.age}
                Gender: ${metadata.gender}
                Health Status: ${metadata.healthStatus}
                Additional Notes: ${metadata.additionalNotes}
                
                Breathing Analysis Summary
                Total Duration: ${(data.lastOrNull()?.timestamp ?: 0L) / 1000f} seconds
                Breathing Rate: ${metrics.breathingRate.format(2)} breaths/minute
                Average Amplitude: ${metrics.averageAmplitude.format(2)}
                Maximum Amplitude: ${metrics.maxAmplitude.format(2)}
                Minimum Amplitude: ${metrics.minAmplitude.format(2)}
                Total Breaths: ${metrics.breathCount}

                Visualization Instructions:
                - The velocity column can be used to generate respiratory flow graphs
                - Negative velocity indicates inhaling (upward movement)
                - Positive velocity indicates exhaling (downward movement)
                - Timestamps can be used for the x-axis (time domain)
                - For best results, plot using a line graph with smoothing

                timestamp,qrId,x,y,movement,breathing_phase,amplitude,velocity,patient_id,age,gender,health_status
            """.trimIndent()
                                )
                                writer.newLine()

                                // Write data with metadata
                                data.forEach { point ->
                                        writer.write(
                                                "${point.timestamp},${point.qrId},${point.position.x},${point.position.y}," +
                                                        "${point.movement},${point.breathingPhase},${
                                                                point.amplitude.format(
                                                                        2
                                                                )
                                                        }," +
                                                        "${point.velocity.format(2)},${metadata.id},${metadata.age}," +
                                                        "${metadata.gender},${metadata.healthStatus}"
                                        )
                                        writer.newLine()
                                }
                        }

                        Log.d(
                                "RespiratoryTracking",
                                "Saved ${data.size} data points to ${file.absolutePath}"
                        )
                        Toast.makeText(
                                        context,
                                        "Respiratory data saved to Downloads/$filename",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                } catch (e: Exception) {
                        Log.e("RespiratoryTracking", "Error saving data", e)
                        Toast.makeText(
                                        context,
                                        "Error saving data: ${e.message}",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                }
        }

        // Add a debugging function to help understand velocity distribution
        private fun analyzeVelocityRange(
                data: List<RespiratoryDataPoint>
        ): Triple<Float, Float, Float> {
                if (data.size < 2) return Triple(0f, 0f, 0f)

                var minVelocity = Float.MAX_VALUE
                var maxVelocity = Float.MIN_VALUE
                var sumVelocity = 0f
                var count = 0

                data.windowed(2, 1).forEach { window ->
                        val point1 = window[0]
                        val point2 = window[1]
                        val timeDiff = (point2.timestamp - point1.timestamp) / 1000f

                        if (timeDiff > 0) {
                                val velocity = (point2.position.y - point1.position.y) / timeDiff
                                minVelocity = minOf(minVelocity, velocity)
                                maxVelocity = maxOf(maxVelocity, velocity)
                                sumVelocity += velocity
                                count++
                        }
                }

                val avgVelocity = if (count > 0) sumVelocity / count else 0f
                return Triple(minVelocity, maxVelocity, avgVelocity)
        }

        // Helper function to format float numbers
        private fun Float.format(digits: Int) = "%.${digits}f".format(this)

        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        fun MetadataInputForm(onSubmit: (PatientMetadata) -> Unit) {
                var patientId by remember { mutableStateOf("") }
                var age by remember { mutableStateOf("") }
                var gender by remember { mutableStateOf("") }
                var healthStatus by remember { mutableStateOf("") }
                var notes by remember { mutableStateOf("") }
                var expandedGender by remember { mutableStateOf(false) }
                var expandedHealth by remember { mutableStateOf(false) }

                Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        Text(
                                "Patient Information",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(bottom = 16.dp)
                        )

                        OutlinedTextField(
                                value = patientId,
                                onValueChange = { patientId = it },
                                label = { Text("Patient ID") },
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                                value = age,
                                onValueChange = { age = it },
                                label = { Text("Age") },
                                keyboardOptions =
                                        KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Gender Dropdown
                        Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                        value = gender,
                                        onValueChange = {},
                                        label = { Text("Gender") },
                                        readOnly = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = {
                                                IconButton(
                                                        onClick = {
                                                                expandedGender = !expandedGender
                                                        }
                                                ) {
                                                        Icon(
                                                                imageVector =
                                                                        if (expandedGender)
                                                                                Icons.Default
                                                                                        .KeyboardArrowUp
                                                                        else
                                                                                Icons.Default
                                                                                        .KeyboardArrowDown,
                                                                contentDescription =
                                                                        "Toggle dropdown"
                                                        )
                                                }
                                        }
                                )
                                DropdownMenu(
                                        expanded = expandedGender,
                                        onDismissRequest = { expandedGender = false },
                                        modifier = Modifier.fillMaxWidth(0.9f)
                                ) {
                                        listOf("Male", "Female", "Other").forEach { option ->
                                                DropdownMenuItem(
                                                        text = { Text(option) },
                                                        onClick = {
                                                                gender = option
                                                                expandedGender = false
                                                        }
                                                )
                                        }
                                }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Health Status Dropdown
                        Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                        value = healthStatus,
                                        onValueChange = {},
                                        label = { Text("Health Status") },
                                        readOnly = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = {
                                                IconButton(
                                                        onClick = {
                                                                expandedHealth = !expandedHealth
                                                        }
                                                ) {
                                                        Icon(
                                                                imageVector =
                                                                        if (expandedHealth)
                                                                                Icons.Default
                                                                                        .KeyboardArrowUp
                                                                        else
                                                                                Icons.Default
                                                                                        .KeyboardArrowDown,
                                                                contentDescription =
                                                                        "Toggle dropdown"
                                                        )
                                                }
                                        }
                                )
                                DropdownMenu(
                                        expanded = expandedHealth,
                                        onDismissRequest = { expandedHealth = false },
                                        modifier = Modifier.fillMaxWidth(0.9f)
                                ) {
                                        listOf("Healthy", "Asthmatic", "COPD", "Other").forEach {
                                                option ->
                                                DropdownMenuItem(
                                                        text = { Text(option) },
                                                        onClick = {
                                                                healthStatus = option
                                                                expandedHealth = false
                                                        }
                                                )
                                        }
                                }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                label = { Text("Additional Notes") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                                onClick = {
                                        if (patientId.isNotBlank() &&
                                                        age.isNotBlank() &&
                                                        gender.isNotBlank() &&
                                                        healthStatus.isNotBlank()
                                        ) {
                                                onSubmit(
                                                        PatientMetadata(
                                                                id = patientId,
                                                                age = age.toIntOrNull() ?: 0,
                                                                gender = gender,
                                                                healthStatus = healthStatus,
                                                                additionalNotes = notes
                                                        )
                                                )
                                        }
                                },
                                enabled =
                                        patientId.isNotBlank() &&
                                                age.isNotBlank() &&
                                                gender.isNotBlank() &&
                                                healthStatus.isNotBlank()
                        ) { Text("Start Recording Session") }
                }
        }

        @Composable
        fun RespirationChart(
                respiratoryData: List<RespiratoryDataPoint>,
                modifier: Modifier = Modifier,
                lineColor: Color = Color(0xFF81C784),
                maxPoints: Int = 100
        ) {
                if (respiratoryData.isEmpty()) {
                        Box(modifier = modifier.background(Color.Black.copy(alpha = 0.3f))) {
                                Text(
                                        "Waiting for respiratory data...",
                                        color = Color.White,
                                        modifier = Modifier.align(Alignment.Center)
                                )
                        }
                        return
                }

                val recentData =
                        if (respiratoryData.size > maxPoints) {
                                respiratoryData.takeLast(maxPoints)
                        } else {
                                respiratoryData
                        }

                Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f))) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                val width = size.width
                                val height = size.height
                                val middleY = height / 2

                                // Find min/max for scaling
                                val maxVelocity =
                                        recentData
                                                .maxOfOrNull { abs(it.velocity) }
                                                ?.coerceAtLeast(10f)
                                                ?: 10f
                                val maxTimestamp =
                                        recentData.maxOfOrNull { it.timestamp.toFloat() } ?: 1000f
                                val minTimestamp =
                                        recentData.minOfOrNull { it.timestamp.toFloat() } ?: 0f
                                val timeRange = maxTimestamp - minTimestamp

                                // Draw horizontal midline
                                drawLine(
                                        color = Color.White.copy(alpha = 0.5f),
                                        start = Offset(0f, middleY),
                                        end = Offset(width, middleY),
                                        strokeWidth = 1.dp.toPx()
                                )

                                // Draw vertical grid lines (every 5 seconds)
                                val secondsInterval = 5
                                val millisecondsInterval = secondsInterval * 1000
                                val intervalWidth = (millisecondsInterval / timeRange) * width
                                var currentX = intervalWidth
                                while (currentX < width) {
                                        drawLine(
                                                color = Color.White.copy(alpha = 0.2f),
                                                start = Offset(currentX, 0f),
                                                end = Offset(currentX, height),
                                                strokeWidth = 0.5.dp.toPx()
                                        )
                                        currentX += intervalWidth
                                }

                                // Draw horizontal grid lines
                                val yIntervals = 5
                                val yStep = height / yIntervals
                                for (i in 1 until yIntervals) {
                                        drawLine(
                                                color = Color.White.copy(alpha = 0.2f),
                                                start = Offset(0f, i * yStep),
                                                end = Offset(width, i * yStep),
                                                strokeWidth = 0.5.dp.toPx()
                                        )
                                }

                                // Draw the data path
                                val path = Path()
                                var isFirstPoint = true

                                recentData.forEachIndexed { index, point ->
                                        val x =
                                                ((point.timestamp - minTimestamp) / timeRange) *
                                                        width

                                        // Invert velocity because in UI, up is negative but
                                        // visually we want up to be inhaling
                                        val normalizedVelocity =
                                                -point.velocity /
                                                        maxVelocity // Negative because inhaling
                                        // should go up
                                        val y = middleY * (1 - normalizedVelocity)

                                        if (isFirstPoint) {
                                                path.moveTo(x, y)
                                                isFirstPoint = false
                                        } else {
                                                path.lineTo(x, y)
                                        }

                                        // Draw phase indicators
                                        val phaseColor =
                                                when (point.breathingPhase.lowercase()) {
                                                        "inhaling" -> Color(0xFF81C784) // Green
                                                        "exhaling" -> Color(0xFF64B5F6) // Blue
                                                        else -> Color.White
                                                }

                                        drawCircle(
                                                color = phaseColor,
                                                radius = 3.dp.toPx(),
                                                center = Offset(x, y)
                                        )
                                }

                                // Draw the path with the appropriate color
                                drawPath(
                                        path = path,
                                        color = lineColor,
                                        style =
                                                Stroke(
                                                        width = 2.dp.toPx(),
                                                        pathEffect =
                                                                PathEffect.cornerPathEffect(
                                                                        5.dp.toPx()
                                                                )
                                                )
                                )

                                // Draw axis labels
                                drawContext.canvas.nativeCanvas.apply {
                                        val textPaint =
                                                android.graphics.Paint().apply {
                                                        color = android.graphics.Color.WHITE
                                                        textAlign =
                                                                android.graphics.Paint.Align.RIGHT
                                                        textSize = 30f
                                                }

                                        // Y-axis labels
                                        drawText("Inhaling", 100f, 40f, textPaint)
                                        drawText("Exhaling", 100f, height - 20f, textPaint)

                                        textPaint.textAlign = android.graphics.Paint.Align.LEFT
                                        // Time labels
                                        val seconds = (timeRange / 1000).toInt()
                                        drawText("0s", 10f, height - 10f, textPaint)
                                        drawText(
                                                "${seconds}s",
                                                width - 50f,
                                                height - 10f,
                                                textPaint
                                        )
                                }
                        }
                }
        }
}
