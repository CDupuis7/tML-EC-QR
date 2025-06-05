package com.example.watchapp.presentation


import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class SensorLogger(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val accLogFile = File(context.filesDir, "accelerometer_log.csv")
    private val healthLogFile = File(context.filesDir, "health_log.csv")

    init {
        if (!accLogFile.exists()) accLogFile.writeText("timestamp,x,y,z\n")
        if (!healthLogFile.exists()) healthLogFile.writeText("timestamp,heart_rate,spo2\n")
    }

    fun logAccelerometer(x: Float, y: Float, z: Float) {
        val timestamp = dateFormat.format(Date())
        val entry = "$timestamp,$x,$y,$z\n"
        FileWriter(accLogFile, true).use { it.append(entry) }
    }

    fun logHealth(heartRate: Int, spo2: Int) {
        val timestamp = dateFormat.format(Date())
        val entry = "$timestamp,$heartRate,$spo2\n"
        FileWriter(healthLogFile, true).use { it.append(entry) }
    }

    fun getAccLogFile(): File = accLogFile
    fun getHealthLogFile(): File = healthLogFile
}
