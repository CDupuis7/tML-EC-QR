package com.example.watchapp.presentation

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.samsung.android.service.health.tracking.HealthTrackerException
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import com.example.watchapp.R

class MainActivityWear : ComponentActivity(), ConnectionObserver, SensorEventListener {

    private lateinit var connectionManager: ConnectionManager
    private var heartRateListener: HeartRateListener? = null
    private var spO2Listener: SpO2Listener? = null
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    private var permissionGranted = false
    private var connected = false
    private var previousSpO2Status = SpO2Status.INITIAL_STATUS

    private val trackerDataObserver = object : TrackerDataObserver {
        override fun onHeartRateTrackerDataChanged(hrData: HeartRateData) {
            if (hrData.status == HeartRateStatus.HR_STATUS_FIND_HR) {
                hrTextState.value = "${hrData.hr} bpm"
                currentHR = hrData.hr
            } else {
                hrTextState.value = "--"
            }
        }

        override fun onSpO2TrackerDataChanged(status: Int, spO2Value: Int) {
            if (status == previousSpO2Status) return
            previousSpO2Status = status
            when (status) {
                SpO2Status.CALCULATING -> statusTextState.value = "Calculating..."
                SpO2Status.DEVICE_MOVING -> Toast.makeText(this@MainActivityWear, "Device is moving", Toast.LENGTH_SHORT).show()
                SpO2Status.LOW_SIGNAL -> Toast.makeText(this@MainActivityWear, "Low signal", Toast.LENGTH_SHORT).show()
                SpO2Status.MEASUREMENT_COMPLETED -> {
                    statusTextState.value = "Completed"
                    spO2TextState.value = "$spO2Value%"
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime > logIntervalMs) {
                        sensorLogger?.logHealth(currentHR, spO2Value)
                        lastLogTime = now
                    }
                }
            }
        }

        override fun onError(errorResourceId: Int) {
            Toast.makeText(this@MainActivityWear, getString(errorResourceId), Toast.LENGTH_LONG).show()
            statusTextState.value = "Error"
        }
    }

    private val hrTextState = mutableStateOf("--")
    private val spO2TextState = mutableStateOf("--")
    private val statusTextState = mutableStateOf("Waiting...")

    private var sensorLogger: SensorLogger? = null
    private var currentHR = 0
    private var lastLogTime = 0L
    private val logIntervalMs = 20_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 0)

        sensorLogger = SensorLogger(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        TrackerDataNotifier.getInstance().addObserver(trackerDataObserver)

        setContent {
            MeasurementScreen()
        }
    }

    @Composable
    fun MeasurementScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Image(
                painter = painterResource(id = R.drawable.logos),
                contentDescription = "App Logo",
                modifier = Modifier
                    .height(68.dp)
                    .width(98.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Heart Rate: ${hrTextState.value}")
            Text("SpO2: ${spO2TextState.value}")
            Text("Status: ${statusTextState.value}")
        }
    }


    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            sensorLogger?.logAccelerometer(x, y, z)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        heartRateListener?.startTracker()
        spO2Listener?.startTracker()
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
        heartRateListener?.stopTracker()
        spO2Listener?.stopTracker()
        sensorManager?.unregisterListener(this)
        TrackerDataNotifier.getInstance().removeObserver(trackerDataObserver)
        connectionManager.disconnect()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

//class MainActivityWear : ComponentActivity(), ConnectionObserver {
//
//    private lateinit var connectionManager: ConnectionManager
//    private var heartRateListener: HeartRateListener? = null
//    private var spO2Listener: SpO2Listener? = null
//
//    private lateinit var sensorManager: SensorManager
//    private var accelerometerListener: SensorEventListener? = null
//
//    private var permissionGranted = false
//    private var connected = false
//    private var previousSpO2Status = SpO2Status.INITIAL_STATUS
//
//    private val trackerDataObserver = object : TrackerDataObserver {
//        override fun onHeartRateTrackerDataChanged(hrData: HeartRateData) {
//            if (hrData.status == HeartRateStatus.HR_STATUS_FIND_HR) {
//                hrTextState.value = "${hrData.hr} bpm"
//            } else {
//                hrTextState.value = "--"
//            }
//        }
//
//        override fun onSpO2TrackerDataChanged(status: Int, spO2Value: Int) {
//            if (status == previousSpO2Status) return
//            previousSpO2Status = status
//            when (status) {
//                SpO2Status.CALCULATING -> statusTextState.value = "Calculating..."
//                SpO2Status.DEVICE_MOVING -> Toast.makeText(this@MainActivityWear, "Device is moving", Toast.LENGTH_SHORT).show()
//                SpO2Status.LOW_SIGNAL -> Toast.makeText(this@MainActivityWear, "Low signal", Toast.LENGTH_SHORT).show()
//                SpO2Status.MEASUREMENT_COMPLETED -> {
//                    statusTextState.value = "Completed"
//                    spO2TextState.value = "$spO2Value%"
//                }
//            }
//        }
//
//        override fun onError(errorResourceId: Int) {
//            Toast.makeText(this@MainActivityWear, getString(errorResourceId), Toast.LENGTH_LONG).show()
//            statusTextState.value = "Error"
//        }
//    }
//
//    private val hrTextState = mutableStateOf("--")
//    private val spO2TextState = mutableStateOf("--")
//    private val statusTextState = mutableStateOf("Waiting...")
//
//    private val accelX = mutableStateOf("--")
//    private val accelY = mutableStateOf("--")
//    private val accelZ = mutableStateOf("--")
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 0)
//
//        TrackerDataNotifier.getInstance().addObserver(trackerDataObserver)
//        registerAccelerometerListener()
//
//        setContent {
//            MeasurementScreen()
//        }
//    }
//
//    @Composable
//    fun MeasurementScreen() {
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(MaterialTheme.colors.background)
//                .padding(16.dp),
//            horizontalAlignment = Alignment.CenterHorizontally,
//            verticalArrangement = Arrangement.Center
//        ) {
//            Text("Heart Rate: ${hrTextState.value}")
//            Text("SpO2: ${spO2TextState.value}")
//            Text("Status: ${statusTextState.value}")
//            Spacer(modifier = Modifier.height(8.dp))
//            Text("Accel X: ${accelX.value}")
//            Text("Accel Y: ${accelY.value}")
//            Text("Accel Z: ${accelZ.value}")
//        }
//    }
//
//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
//            permissionGranted = true
//            initConnection()
//        } else {
//            Toast.makeText(this, "Sensor permission denied", Toast.LENGTH_LONG).show()
//            finish()
//        }
//    }
//
//    private fun initConnection() {
//        try {
//            connectionManager = ConnectionManager(this)
//            connectionManager.connect(applicationContext)
//        } catch (e: Exception) {
//            Log.e("MainActivityWear", "Connection error: ${e.message}")
//        }
//    }
//
//    override fun onConnectionResult(stringResourceId: Int) {
//        runOnUiThread {
//            Toast.makeText(this, getString(stringResourceId), Toast.LENGTH_SHORT).show()
//        }
//
//        if (stringResourceId != R.string.ConnectedToHs) {
//            finish()
//            return
//        }
//
//        connected = true
//        spO2Listener = SpO2Listener()
//        heartRateListener = HeartRateListener()
//        connectionManager.initSpO2(spO2Listener)
//        connectionManager.initHeartRate(heartRateListener)
//
//        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
//        heartRateListener?.startTracker()
//        spO2Listener?.startTracker()
//    }
//
//    override fun onError(e: HealthTrackerException?) {
//        e?.let {
//            if (it.hasResolution()) {
//                it.resolve(this)
//            } else {
//                Toast.makeText(this, "Connection failed: ${it.message}", Toast.LENGTH_LONG).show()
//            }
//            finish()
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        heartRateListener?.stopTracker()
//        spO2Listener?.stopTracker()
//        unregisterAccelerometerListener()
//        TrackerDataNotifier.getInstance().removeObserver(trackerDataObserver)
//        connectionManager.disconnect()
//        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
//    }
//
//    private fun registerAccelerometerListener() {
//        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
//        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
//
//        if (accelerometer != null) {
//            accelerometerListener = object : SensorEventListener {
//                override fun onSensorChanged(event: SensorEvent) {
//                    if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
//                        accelX.value = "%.2f".format(event.values[0])
//                        accelY.value = "%.2f".format(event.values[1])
//                        accelZ.value = "%.2f".format(event.values[2])
//                    }
//                }
//
//                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
//            }
//            sensorManager.registerListener(accelerometerListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
//        } else {
//            Log.w("MainActivityWear", "Accelerometer not available.")
//        }
//    }
//
//    private fun unregisterAccelerometerListener() {
//        accelerometerListener?.let {
//            sensorManager.unregisterListener(it)
//        }
//    }
//}

