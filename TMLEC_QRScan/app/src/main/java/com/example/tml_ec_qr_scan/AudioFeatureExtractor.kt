package com.example.tml_ec_qr_scan

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Extracts audio features from respiratory sound recordings */
class AudioFeatureExtractor {
    /**
     * Extracts features from an audio file for respiratory analysis
     * @param audioFilePath Path to the audio file to analyze
     * @return Map of feature names to feature values
     */
    fun extractFeatures(audioFilePath: String): Map<String, Float>? {
        if (!File(audioFilePath).exists()) {
            Log.e(TAG, "Audio file does not exist: $audioFilePath")
            return null
        }

        try {
            // Extract audio samples from the file
            val samples = extractAudioSamples(audioFilePath) ?: return null

            // Calculate acoustic features
            val zeroCrossingRate = calculateZeroCrossingRate(samples)
            val rmsEnergy = calculateRMSEnergy(samples)
            val variability = calculateVariability(samples)

            // Detect abnormal sounds based on audio properties
            val hasWheezes = detectWheezes(samples, zeroCrossingRate)
            val hasCrackles = detectCrackles(samples)

            // Return extracted features as a map
            return mapOf(
                    "zero_crossing_rate" to zeroCrossingRate,
                    "rms_energy" to rmsEnergy,
                    "variability" to variability,
                    "has_wheezes" to if (hasWheezes) 1f else 0f,
                    "has_crackles" to if (hasCrackles) 1f else 0f
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting audio features: ${e.message}")
            return null
        }
    }

    /** Extracts audio samples from a file */
    private fun extractAudioSamples(audioFilePath: String): DoubleArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioFilePath)

            // Find the audio track
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)

                if (mime?.startsWith("audio/") == true) {
                    extractor.selectTrack(i)

                    // Get audio format details
                    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val duration = format.getLong(MediaFormat.KEY_DURATION)

                    // Calculate buffer size (10 seconds or full duration)
                    val maxSamples = sampleRate * channelCount * 10 // 10 seconds max
                    val totalSamples =
                            (duration * sampleRate / 1000000).toInt().coerceAtMost(maxSamples)

                    // Read samples
                    val buffer = ByteBuffer.allocate(totalSamples * 2) // 16-bit samples
                    buffer.order(ByteOrder.LITTLE_ENDIAN)

                    val samples = DoubleArray(totalSamples)
                    var sampleCount = 0
                    var offset = 0

                    while (sampleCount < totalSamples) {
                        val chunkSize = extractor.readSampleData(buffer, offset)
                        if (chunkSize < 0) break

                        // Convert bytes to samples
                        buffer.rewind()
                        while (buffer.hasRemaining() && sampleCount < totalSamples) {
                            val sample =
                                    buffer.short.toDouble() / Short.MAX_VALUE // Normalize to [-1,1]
                            samples[sampleCount++] = sample
                        }

                        buffer.clear()
                        extractor.advance()
                    }

                    return if (sampleCount > 0) samples.copyOf(sampleCount) else null
                }
            }

            Log.e(TAG, "No audio track found in file")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting audio samples: ${e.message}")
            return null
        } finally {
            extractor.release()
        }
    }

    /** Calculates zero-crossing rate of audio samples */
    private fun calculateZeroCrossingRate(samples: DoubleArray): Float {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) || (samples[i] < 0 && samples[i - 1] >= 0)
            ) {
                crossings++
            }
        }
        return (crossings.toFloat() / samples.size)
    }

    /** Calculates RMS energy of audio samples */
    private fun calculateRMSEnergy(samples: DoubleArray): Float {
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        return sqrt(sum / samples.size).toFloat()
    }

    /** Calculates the variability of the signal (standard deviation) */
    private fun calculateVariability(samples: DoubleArray): Float {
        var sum = 0.0
        var sumSquared = 0.0

        for (sample in samples) {
            sum += sample
            sumSquared += sample * sample
        }

        val mean = sum / samples.size
        val variance = (sumSquared / samples.size) - (mean * mean)

        return sqrt(variance).toFloat()
    }

    /**
     * Detects presence of wheeze sounds (high-pitched continuous sounds)
     * @param samples Audio samples
     * @param zeroCrossingRate Pre-calculated ZCR
     * @return Whether wheezes are detected
     */
    private fun detectWheezes(samples: DoubleArray, zeroCrossingRate: Float): Boolean {
        // Simplified wheeze detection using zero-crossing rate
        // Wheezes typically have a high ZCR but moderate energy

        val energy = calculateRMSEnergy(samples)

        // Check for sustained high ZCR and moderate energy
        // High ZCR indicates high-frequency content typical of wheezes
        return zeroCrossingRate > 0.05f && energy > 0.01f && energy < 0.2f
    }

    /** Detects presence of crackle sounds (short explosive sounds) */
    private fun detectCrackles(samples: DoubleArray): Boolean {
        // Simplified implementation - look for sharp transients
        var transientCount = 0
        val threshold = 0.05 // Threshold for transient detection

        // Detect sudden amplitude changes
        for (i in 1 until samples.size) {
            val diff = abs(samples[i] - samples[i - 1])
            if (diff > threshold) {
                transientCount++
            }
        }

        // Calculate transient density
        val density = transientCount.toFloat() / samples.size

        // Threshold for crackle detection (adjustable)
        return density > 0.01 // Threshold based on testing
    }

    companion object {
        private const val TAG = "AudioFeatureExtractor"
    }
}
