package com.example.tml_ec_qr_scan

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.IOException
import java.nio.charset.Charset

data class PatientNFCData(
        val id: String,
        val age: Int,
        val gender: String,
        val healthStatus: String,
        val timestamp: Long = System.currentTimeMillis()
)

class NFCManager(private val context: Context) {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var writeMode = false
    private var messageToWrite: NdefMessage? = null
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    companion object {
        private const val TAG = "NFCManager"
        private const val MIME_TYPE_PATIENT_DATA = "application/vnd.patient.data"
    }

    init {
        nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        setupForegroundDispatch()
    }

    /** Check if NFC is available on device */
    fun isNFCAvailable(): Boolean {
        return nfcAdapter != null
    }

    /** Check if NFC is enabled */
    fun isNFCEnabled(): Boolean {
        return nfcAdapter?.isEnabled == true
    }

    /** Setup foreground dispatch for NFC */
    private fun setupForegroundDispatch() {
        val intent =
                Intent(context, context.javaClass).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }

        val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

        pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)
    }

    /** Enable foreground dispatch in activity */
    fun enableForegroundDispatch(activity: Activity) {
        try {
            val intentFilters =
                    arrayOf(
                            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                                addDataType(MIME_TYPE_PATIENT_DATA)
                            },
                            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
                    )

            val techListsArray =
                    arrayOf(
                            arrayOf(Ndef::class.java.name),
                            arrayOf(NdefFormatable::class.java.name)
                    )

            nfcAdapter?.enableForegroundDispatch(
                    activity,
                    pendingIntent,
                    intentFilters,
                    techListsArray
            )

            Log.d(TAG, "✅ NFC foreground dispatch enabled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enabling NFC foreground dispatch: ${e.message}")
        }
    }

    /** Disable foreground dispatch in activity */
    fun disableForegroundDispatch(activity: Activity) {
        try {
            nfcAdapter?.disableForegroundDispatch(activity)
            Log.d(TAG, "✅ NFC foreground dispatch disabled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error disabling NFC foreground dispatch: ${e.message}")
        }
    }

    /** Create NDEF message from patient data */
    fun createNdefMessage(patientData: PatientNFCData): NdefMessage {
        try {
            // Convert patient data to JSON using Gson
            val json = gson.toJson(patientData)
            Log.d(TAG, "📝 Creating NDEF message with JSON: $json")

            // Create NDEF record with custom MIME type
            val mimeRecord =
                    NdefRecord.createMime(
                            MIME_TYPE_PATIENT_DATA,
                            json.toByteArray(Charset.forName("UTF-8"))
                    )

            // Create RTD text record as backup (for generic NFC readers)
            val textRecord =
                    NdefRecord.createTextRecord(
                            "en",
                            "Patient: ${patientData.id}, Age: ${patientData.age}, Gender: ${patientData.gender}, Health: ${patientData.healthStatus}"
                    )

            return NdefMessage(arrayOf(mimeRecord, textRecord))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating NDEF message: ${e.message}")
            throw e
        }
    }

    /** Write patient data to NFC tag */
    fun writeToTag(tag: Tag, patientData: PatientNFCData): Boolean {
        return try {
            val ndefMessage = createNdefMessage(patientData)
            val success = writeNdefMessage(tag, ndefMessage)

            if (success) {
                Log.d(TAG, "✅ Successfully wrote patient data to NFC tag")
                Toast.makeText(context, "✅ Patient data written to NFC tag!", Toast.LENGTH_SHORT)
                        .show()
            } else {
                Log.e(TAG, "❌ Failed to write patient data to NFC tag")
                Toast.makeText(context, "❌ Failed to write to NFC tag", Toast.LENGTH_SHORT).show()
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error writing to NFC tag: ${e.message}")
            Toast.makeText(context, "❌ Error writing to NFC tag: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
            false
        }
    }

    /** Write NDEF message to tag */
    private fun writeNdefMessage(tag: Tag, ndefMessage: NdefMessage): Boolean {
        try {
            val ndef = Ndef.get(tag)

            if (ndef != null) {
                // Tag is NDEF formatted
                ndef.connect()

                if (!ndef.isWritable) {
                    Log.e(TAG, "❌ NFC tag is not writable")
                    return false
                }

                val size = ndefMessage.toByteArray().size
                if (ndef.maxSize < size) {
                    Log.e(
                            TAG,
                            "❌ NFC tag is too small. Required: $size bytes, Available: ${ndef.maxSize} bytes"
                    )
                    return false
                }

                ndef.writeNdefMessage(ndefMessage)
                ndef.close()

                Log.d(TAG, "✅ Successfully wrote NDEF message to formatted tag")
                return true
            } else {
                // Try to format the tag
                val ndefFormatable = NdefFormatable.get(tag)
                if (ndefFormatable != null) {
                    ndefFormatable.connect()
                    ndefFormatable.format(ndefMessage)
                    ndefFormatable.close()

                    Log.d(TAG, "✅ Successfully formatted and wrote NDEF message to tag")
                    return true
                } else {
                    Log.e(TAG, "❌ Tag is not NDEF formatable")
                    return false
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "❌ IOException during tag write: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during tag write: ${e.message}")
            return false
        }
    }

    /** Read patient data from NFC tag */
    fun readFromTag(tag: Tag): PatientNFCData? {
        return try {
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                Log.e(TAG, "❌ Tag is not NDEF formatted")
                return null
            }

            ndef.connect()
            val ndefMessage = ndef.ndefMessage
            ndef.close()

            if (ndefMessage == null) {
                Log.e(TAG, "❌ No NDEF message found on tag")
                return null
            }

            parsePatientDataFromNdef(ndefMessage)
        } catch (e: IOException) {
            Log.e(TAG, "❌ IOException during tag read: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during tag read: ${e.message}")
            null
        }
    }

    /** Parse patient data from NDEF message */
    fun parsePatientDataFromNdef(ndefMessage: NdefMessage): PatientNFCData? {
        return try {
            for (record in ndefMessage.records) {
                // Check for our custom MIME type
                if (record.tnf == NdefRecord.TNF_MIME_MEDIA) {
                    val mimeType = String(record.type, Charset.forName("UTF-8"))

                    if (mimeType == MIME_TYPE_PATIENT_DATA) {
                        val payload = String(record.payload, Charset.forName("UTF-8"))
                        Log.d(TAG, "📖 Found patient data JSON: $payload")

                        val patientData = gson.fromJson(payload, PatientNFCData::class.java)
                        Log.d(TAG, "✅ Successfully parsed patient data: $patientData")

                        return patientData
                    }
                }

                // Fallback: check for text records that might contain patient data
                if (record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                                record.type.contentEquals(NdefRecord.RTD_TEXT)
                ) {

                    val payload = String(record.payload, Charset.forName("UTF-8"))
                    Log.d(TAG, "📖 Found text record: $payload")

                    // Try to parse as JSON (in case it's patient data in text format)
                    try {
                        if (payload.contains("\"id\"") && payload.contains("\"age\"")) {
                            val patientData = gson.fromJson(payload, PatientNFCData::class.java)
                            Log.d(
                                    TAG,
                                    "✅ Successfully parsed patient data from text record: $patientData"
                            )
                            return patientData
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Text record is not patient JSON data")
                    }
                }
            }

            Log.w(TAG, "⚠️ No patient data found in NFC tag")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing patient data from NDEF: ${e.message}")
            null
        }
    }

    /** Handle NFC intent from activity */
    fun handleIntent(intent: Intent): PatientNFCData? {
        val action = intent.action
        Log.d(TAG, "🏷️ NFC Intent received: $action")

        return when (action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                if (rawMessages != null && rawMessages.isNotEmpty()) {
                    val ndefMessage = rawMessages[0] as NdefMessage
                    parsePatientDataFromNdef(ndefMessage)
                } else {
                    Log.w(TAG, "⚠️ No NDEF messages found in intent")
                    null
                }
            }
            NfcAdapter.ACTION_TAG_DISCOVERED -> {
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                if (tag != null) {
                    readFromTag(tag)
                } else {
                    Log.w(TAG, "⚠️ No tag found in intent")
                    null
                }
            }
            else -> {
                Log.w(TAG, "⚠️ Unhandled NFC action: $action")
                null
            }
        }
    }

    /** Show NFC settings if NFC is disabled */
    fun showNFCSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Could not open NFC settings: ${e.message}")
            Toast.makeText(context, "Please enable NFC in system settings", Toast.LENGTH_LONG)
                    .show()
        }
    }

    /** Get NFC status message for user */
    fun getNFCStatusMessage(): String {
        return when {
            !isNFCAvailable() -> "❌ NFC is not available on this device"
            !isNFCEnabled() -> "⚠️ NFC is disabled. Please enable it in settings"
            else -> "✅ NFC is ready"
        }
    }
}
