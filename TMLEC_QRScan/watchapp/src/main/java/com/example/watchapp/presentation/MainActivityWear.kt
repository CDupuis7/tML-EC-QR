package com.example.watchapp.presentation

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.samsung.android.service.health.tracking.HealthTrackerException
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import com.example.watchapp.R

class MainActivityWear : ComponentActivity(), ConnectionObserver {

    private lateinit var connectionManager: ConnectionManager
    private var heartRateListener: HeartRateListener? = null
    private var spO2Listener: SpO2Listener? = null

    private val isMeasuring = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private var permissionGranted = false
    private var connected = false
    private var previousSpO2Status = SpO2Status.INITIAL_STATUS
    private var lastHR = 0

    // ✅ Tracker observer made class-level so it can be removed in onDestroy
    private val trackerDataObserver = object : TrackerDataObserver {
        override fun onHeartRateTrackerDataChanged(hrData: HeartRateData) {
            lastHR = hrData.hr
        }

        override fun onSpO2TrackerDataChanged(status: Int, spO2Value: Int) {
            // UI is updated in LaunchedEffect
        }

        override fun onError(errorResourceId: Int) {
            Toast.makeText(this@MainActivityWear, getString(errorResourceId), Toast.LENGTH_LONG).show()
            stopMeasurement()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 0)

        setContent {
            MeasurementScreen()
        }
    }

    @Composable
    fun MeasurementScreen() {
        val context = LocalContext.current
        val spO2Text = remember { mutableStateOf("--") }
        val hrText = remember { mutableStateOf("--") }
        val statusText = remember { mutableStateOf("Idle") }
        val buttonText = remember { mutableStateOf("Start") }

        LaunchedEffect(Unit) {
            TrackerDataNotifier.getInstance().addObserver(object : TrackerDataObserver {
                override fun onHeartRateTrackerDataChanged(hrData: HeartRateData) {
                    if (hrData.status == HeartRateStatus.HR_STATUS_FIND_HR) {
                        hrText.value = "${hrData.hr} bpm"
                        lastHR = hrData.hr
                    } else {
                        hrText.value = "--"
                    }
                }

                override fun onSpO2TrackerDataChanged(status: Int, spO2Value: Int) {
                    if (status == previousSpO2Status) return
                    previousSpO2Status = status
                    when (status) {
                        SpO2Status.CALCULATING -> statusText.value = "Calculating..."
                        SpO2Status.DEVICE_MOVING -> Toast.makeText(context, "Device is moving", Toast.LENGTH_SHORT).show()
                        SpO2Status.LOW_SIGNAL -> Toast.makeText(context, "Low signal", Toast.LENGTH_SHORT).show()
                        SpO2Status.MEASUREMENT_COMPLETED -> {
                            statusText.value = "Completed"
                            spO2Text.value = "$spO2Value%"
                            stopMeasurement()
                            buttonText.value = "Start"
                        }
                    }
                }

                override fun onError(errorResourceId: Int) {
                    Toast.makeText(context, context.getString(errorResourceId), Toast.LENGTH_LONG).show()
                    stopMeasurement()
                    statusText.value = "Error"
                    buttonText.value = "Start"
                }
            })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Heart Rate: ${hrText.value}")
            Text("SpO2: ${spO2Text.value}")
            Text("Status: ${statusText.value}")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                if (!connected) {
                    Toast.makeText(context, "Not connected to Health Service", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (!isMeasuring.get()) {
                    buttonText.value = "Stop"
                    statusText.value = "Starting..."
                    startMeasurement()
                } else {
                    buttonText.value = "Start"
                    stopMeasurement()
                    statusText.value = "Stopped"
                }
            }) {
                Text(buttonText.value)
            }
        }
    }

    private fun startMeasurement() {
        if (spO2Listener == null || heartRateListener == null) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        previousSpO2Status = SpO2Status.INITIAL_STATUS
        spO2Listener?.startTracker()
        heartRateListener?.startTracker()
        isMeasuring.set(true)

        coroutineScope.launch {
            delay(35000L)
            if (isMeasuring.get()) {
                stopMeasurement()
                Toast.makeText(this@MainActivityWear, "Measurement timed out", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopMeasurement() {
        spO2Listener?.stopTracker()
        heartRateListener?.stopTracker()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isMeasuring.set(false)
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            permissionGranted = true
            initConnection()
        } else {
            Toast.makeText(this, "Sensor permission denied", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initConnection() {
        try {
            connectionManager = ConnectionManager(this)
            connectionManager.connect(applicationContext)
        } catch (e: Exception) {
            Log.e("MainActivityWear", "Connection error: ${e.message}")
        }
    }

    override fun onConnectionResult(stringResourceId: Int) {
        runOnUiThread {
            Toast.makeText(this, getString(stringResourceId), Toast.LENGTH_SHORT).show()
        }
        if (stringResourceId != R.string.ConnectedToHs) {
            finish()
            return
        }

        connected = true
        spO2Listener = SpO2Listener()
        heartRateListener = HeartRateListener()
        connectionManager.initSpO2(spO2Listener)
        connectionManager.initHeartRate(heartRateListener)
    }

    override fun onError(e: HealthTrackerException?) {
        e?.let {
            if (it.hasResolution()) {
                it.resolve(this)
            } else {
                Toast.makeText(this, "Connection failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMeasurement()
        TrackerDataNotifier.getInstance().removeObserver(trackerDataObserver)
        connectionManager.disconnect()
    }
}