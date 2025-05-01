package com.example.watchapp

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.samsung.android.service.health.tracking.data.HealthTracker
import com.samsung.android.service.health.tracking.data.ConnectionListener


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val DATA_PATH = "/health_data"

class MainActivityWear : ComponentActivity(), HealthDataStore.ConnectionListener {

    private lateinit var healthDataStore: HealthDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Samsung Health SDK
        healthDataStore = HealthDataStore(this, this)
        healthDataStore.connect()

        setContent {

            SensorScreen(this, healthDataStore)

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        healthDataStore.disconnect()
    }

    override fun onConnected() {
        Log.d("SamsungHealth", "Connected to Samsung Health")
    }

    override fun onConnectionFailed(error: HealthConnectionErrorResult?) {
        Log.e("SamsungHealth", "Connection failed: ${error?.errorCode}")
    }

    override fun onDisconnected() {
        Log.w("SamsungHealth", "Disconnected from Samsung Health")
    }
}

@Composable
fun SensorScreen(context: Context, healthDataStore: HealthDataStore) {
    val heartRate = remember { mutableStateOf("Loading...") }
    val spO2 = remember { mutableStateOf("Loading...") }
    val dataClient = Wearable.getDataClient(context)

    LaunchedEffect(Unit) {
        fetchHealthData(healthDataStore, heartRate, spO2, dataClient)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AMS Project", fontSize = 18.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("HR: ${heartRate.value} bpm", fontSize = 16.sp)
            Text("SpO2: ${spO2.value}%", fontSize = 16.sp)
        }
    }
}

fun fetchHealthData(
    healthDataStore: HealthDataStore,
    heartRate: MutableState<String>,
    spO2: MutableState<String>,
    dataClient: DataClient
) {
    val resolver = HealthDataResolver(healthDataStore, null)
    val timeThreshold = System.currentTimeMillis() - (1000 * 60 * 10)

    val heartRateRequest = HealthDataResolver.ReadRequest.Builder()
        .setDataType(HealthConstants.HeartRate.HEART_RATE)
        .setProperties(arrayOf(HealthConstants.HeartRate.HEART_RATE, HealthConstants.HeartRate.START_TIME))
        .setFilter(HealthDataResolver.Filter.greaterThan(HealthConstants.HeartRate.START_TIME, timeThreshold))
        .setSort(HealthConstants.HeartRate.START_TIME, HealthDataResolver.SortOrder.DESC)
        .build()

    resolver.read(heartRateRequest).setResultListener { result ->
        try {
            result?.let {
                if (it.iterator().hasNext()) {
                    val data = HealthData(it.iterator().next())
                    val hrValue = data.getFloat(HealthConstants.HeartRate.HEART_RATE).toInt()
                    heartRate.value = "$hrValue"
                    sendDataToPhone(dataClient, heartRate.value, spO2.value)
                } else {
                    heartRate.value = "No Data"
                }
            }
        } catch (e: Exception) {
            Log.e("SamsungHealth", "Heart rate error", e)
            heartRate.value = "Error"
        }
    }

    val spO2Request = HealthDataResolver.ReadRequest.Builder()
        .setDataType(HealthConstants.OxygenSaturation.SPO2)
        .setProperties(arrayOf(HealthConstants.OxygenSaturation.SPO2, HealthConstants.OxygenSaturation.START_TIME))
        .setFilter(HealthDataResolver.Filter.greaterThan(HealthConstants.OxygenSaturation.START_TIME, timeThreshold))
        .setSort(HealthConstants.OxygenSaturation.START_TIME, HealthDataResolver.SortOrder.DESC)
        .build()

    resolver.read(spO2Request).setResultListener { result ->
        try {
            result?.let {
                if (it.iterator().hasNext()) {
                    val data = HealthData(it.iterator().next())
                    val spO2Value = data.getFloat(HealthConstants.OxygenSaturation.SPO2).toInt()
                    spO2.value = "$spO2Value"
                    sendDataToPhone(dataClient, heartRate.value, spO2.value)
                } else {
                    spO2.value = "No Data"
                }
            }
        } catch (e: Exception) {
            Log.e("SamsungHealth", "SpO2 error", e)
            spO2.value = "Error"
        }
    }
}

fun sendDataToPhone(dataClient: DataClient, heartRate: String, spO2: String) {
    val putDataReq = PutDataMapRequest.create(DATA_PATH).apply {
        dataMap.putString("heart_rate", heartRate)
        dataMap.putString("spO2", spO2)
    }.asPutDataRequest()

    CoroutineScope(Dispatchers.IO).launch {
        dataClient.putDataItem(putDataReq)
    }
}
