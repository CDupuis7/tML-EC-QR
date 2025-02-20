package com.example.tml_ec_qr_scan

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraX;
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.tml_ec_qr_scan.ui.theme.TMLEC_QRScanTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        //Check Opencv
        if (OpenCVLoader.initLocal()) {
            Log.i("OpenCV", "OpenCV Loaded")
        }

        setContent {
            TMLEC_QRScanTheme {
                var isCameraVisible by remember { mutableStateOf(false) }

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
                        // Show Camera Preview if enabled
                        if (isCameraVisible) {
                            CameraPreview(modifier = Modifier.align(Alignment.Center))
                        }

                        // Button to toggle the camera preview
                        Button(
                            onClick = {
                                isCameraVisible = !isCameraVisible
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        ) {
                            Text(
                                text = if (isCameraVisible) "Hide Camera" else "Show Camera"
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown() // Clean up the camera executor
    }
}

//Camera Preview Composable
@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current // Replace LifecycleOwnerAmbient with this
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }

    AndroidView(
        factory = { previewView },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f) // Standard camera preview aspect ratio
    ) {
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            try {
                // Bind the camera to the lifecycle and preview
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
            } catch (e: Exception) {
                Log.e("CameraPreview", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}










//Compose Examples:

//@Composable
//fun Greeting(name: String, modifier: Modifier = Modifier) {
//    Text(
//        text = "Hello $name!",
//        modifier = modifier
//    )
//}

//@Preview(showBackground = true)
//@Composable
//fun GreetingPreview() {
//    TMLEC_QRScanTheme {
//        Greeting("Android")
//    }
//}