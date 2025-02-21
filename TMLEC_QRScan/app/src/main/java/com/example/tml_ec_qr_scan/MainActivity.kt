package com.example.tml_ec_qr_scan

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private val scannedQrCodes = mutableSetOf<Pair<String, Rect>>()

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            var qrResult by remember { mutableStateOf("") }
            var boundingBox by remember { mutableStateOf<Rect?>(null) }
            val isCameraVisible by remember { mutableStateOf(true) }
            val context = LocalContext.current

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = "tML-EC QR App",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isCameraVisible) {
                            CameraPreview(
                                modifier = Modifier.fillMaxWidth(),
                                onQRCodeScanned = { detectedQRCodes ->
                                    detectedQRCodes.forEach { (qrCode, box) ->
                                        box?.let {
                                            if (!scannedQrCodes.contains(qrCode to it)) {
                                                scannedQrCodes.add(qrCode to it)
                                                qrResult = qrCode
                                                boundingBox = it
                                                Log.d("QR_Code", "Scanned QR code: $qrCode")
                                                Toast.makeText(
                                                    context,
                                                    "Scanned: $qrCode",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        Button(
                            onClick = { sendQrDataToAPI() },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text("Send QR Codes to Server")
                        }
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun sendQrDataToAPI() {
        if (scannedQrCodes.isNotEmpty()) {
            val qrDataList = scannedQrCodes.map { it.first } // Extract only QR code strings
            val qrRequest = QRDataRequest(qrDataList)

            val call = Retrofit.api.sendQrData(qrRequest)
            call.enqueue(object : Callback<QRResponse> {
                override fun onResponse(call: Call<QRResponse>, response: Response<QRResponse>) {
                    if (response.isSuccessful) {
                        val receivedData = response.body()?.qr_data
                        receivedData?.let {
                            Toast.makeText(
                                this@MainActivity,
                                "Received: ${it.joinToString(", ")}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Failed to send QR Codes",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onFailure(call: Call<QRResponse>, t: Throwable) {
                    Toast.makeText(
                        this@MainActivity,
                        "API Error: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        } else {
            Toast.makeText(this, "No QR codes scanned yet!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onQRCodeScanned: (List<Pair<String, Rect?>>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxWidth()
        ) { previewView ->
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy) { detectedQRCodes ->
                        onQRCodeScanned(detectedQRCodes)
                    }
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
                    Log.e("CameraPreview", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)

private fun processImageProxy(
    imageProxy: ImageProxy,
    onQRCodeScanned: (List<Pair<String, Rect?>>) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val scanner = BarcodeScanning.getClient()
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val qrCodeList = mutableListOf<Pair<String, Rect?>>()
                for (barcode in barcodes) {
                    val qrData = barcode.rawValue
                    val boundingBox = barcode.boundingBox
                    if (!qrData.isNullOrEmpty()) {
                        qrCodeList.add(Pair(qrData, boundingBox))
                    }
                }
                if (qrCodeList.isNotEmpty()) {
                    onQRCodeScanned(qrCodeList)
                }
            }
            .addOnFailureListener { e ->
                Log.e("QR Scan", "Error processing image", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
