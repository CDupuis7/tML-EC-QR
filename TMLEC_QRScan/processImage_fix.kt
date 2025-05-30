@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImage(
        imageProxy: androidx.camera.core.ImageProxy,
        callback: (List<BoofCvQrDetection>, Float, Float, Int) -> Unit
) {
    try {
        val bitmap = convertImageProxyToBitmap(imageProxy) ?: return
        val rotation = imageProxy.imageInfo.rotationDegrees

        // Use FixedYoloQRDetector directly (matches your trained model)
        val detections =
                try {
                    val fixedYoloDetector = FixedYoloQRDetector(this@MainActivity)
                    val yoloDetections = fixedYoloDetector.detectQRCodes(bitmap)
                    fixedYoloDetector.close()

                    if (yoloDetections.isNotEmpty()) {
                        android.util.Log.d(
                                "QRDetection",
                                "FixedYOLO detected ${yoloDetections.size} QR codes"
                        )
                        yoloDetections
                    } else {
                        android.util.Log.d(
                                "QRDetection",
                                "FixedYOLO found no detections, falling back to BoofCV"
                        )
                        detectQrCodesBoof(bitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e(
                            "QRDetection",
                            "FixedYOLO error: ${e.message}, falling back to BoofCV"
                    )
                    detectQrCodesBoof(bitmap)
                }

        callback(detections, bitmap.width.toFloat(), bitmap.height.toFloat(), rotation)
    } catch (e: Exception) {
        android.util.Log.e("CameraProcess", "Error processing image", e)
    } finally {
        imageProxy.close()
    }
}
