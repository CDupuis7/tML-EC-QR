package com.example.tml_ec_qr_scan

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Handles audio recording functionality for respiratory sound analysis */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var isRecording = false
    private var outputFile: String? = null

    /**
     * Starts recording audio and returns the file path
     * @return The path to the recorded audio file
     */
    fun startRecording(): String? {
        if (isRecording) {
            Log.w(TAG, "Recording is already in progress")
            return outputFile
        }

        try {
            // Create recorder instance
            recorder =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else {
                        @Suppress("DEPRECATION") MediaRecorder()
                    }

            // Create output directory if it doesn't exist
            val outputDir = File(context.filesDir, "audio")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            // Create output file with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            outputFile = File(outputDir, "respiratory_$timestamp.3gp").absolutePath

            // Configure recorder
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setOutputFile(outputFile)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setAudioSamplingRate(44100) // Higher sampling rate for better quality
                setAudioEncodingBitRate(128000) // Higher bitrate for better quality

                try {
                    prepare()
                    start()
                    isRecording = true
                    Log.d(TAG, "Started recording to $outputFile")
                } catch (e: IOException) {
                    Log.e(TAG, "Error preparing recorder: ${e.message}")
                    release()
                    recorder = null
                    outputFile = null
                    isRecording = false
                }
            }

            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}")
            stopRecording()
            return null
        }
    }

    /**
     * Stops recording and returns the file path
     * @return The path to the recorded audio file
     */
    fun stopRecording(): String? {
        if (!isRecording) {
            Log.w(TAG, "No recording in progress")
            return outputFile
        }

        try {
            recorder?.apply {
                stop()
                release()
            }
            Log.d(TAG, "Stopped recording: $outputFile")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}")
        } finally {
            recorder = null
            isRecording = false
        }

        return outputFile
    }

    /**
     * Checks if recording is in progress
     * @return True if recording, false otherwise
     */
    fun isRecording(): Boolean {
        return isRecording
    }

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
