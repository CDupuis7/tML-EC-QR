package com.example.tml_ec_qr_scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import org.tensorflow.lite.Interpreter

/**
 * YOLO-based chest region detector for breathing pattern analysis. Uses a pre-trained COCO
 * YOLOv8/YOLOv5 model to detect person and extracts chest region.
 */
class YoloChestDetector(private val context: Context) {

    data class ChestDetection(
            val personBoundingBox: RectF,
            val chestRegion: RectF,
            val confidence: Float,
            val timestamp: Long = System.currentTimeMillis()
    ) {
        // Compatibility properties for existing code
        val boundingBox: RectF
            get() = chestRegion
        val centerPoint: androidx.compose.ui.geometry.Offset
            get() =
                    androidx.compose.ui.geometry.Offset(
                            (chestRegion.left + chestRegion.right) / 2f,
                            (chestRegion.top + chestRegion.bottom) / 2f
                    )
    }

    private var interpreter: Interpreter? = null
    private val inputSize = 640
    private val personClassId = 0 // Person class in COCO
    private val confidenceThreshold = 0.3f // Reduced from 0.5f for better detection rate
    private val iouThreshold = 0.4f

    // Chest region calculation parameters
    private val chestTopOffset = 0.15f // Start chest region 15% down from top of person bbox
    private val chestHeightRatio = 0.4f // Chest region is 40% of person height

    // COCO class names (first few for reference)
    private val cocoClasses =
            arrayOf(
                    "person",
                    "bicycle",
                    "car",
                    "motorcycle",
                    "airplane",
                    "bus",
                    "train",
                    "truck",
                    "boat",
                    "traffic light",
                    "fire hydrant",
                    "stop sign",
                    "parking meter",
                    "bench",
                    "bird",
                    "cat",
                    "dog",
                    "horse",
                    "sheep",
                    "cow",
                    "elephant",
                    "bear",
                    "zebra",
                    "giraffe"
                    // ... (80 classes total)
                    )

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            Log.d("YoloChestDetector", "🔄 Starting YOLO model loading...")

            // Try YOLOv8s first, then fall back to YOLOv5s
            val (modelBuffer, modelName) =
                    try {
                        Log.d("YoloChestDetector", "📁 Attempting to load yolov8s_coco.tflite")
                        val buffer = loadModelFile("yolov8s_coco.tflite")
                        Log.d("YoloChestDetector", "✅ Successfully loaded yolov8s_coco.tflite")
                        Pair(buffer, "YOLOv8s")
                    } catch (e: Exception) {
                        Log.w(
                                "YoloChestDetector",
                                "⚠️ YOLOv8s not found, trying YOLOv5s: ${e.message}"
                        )
                        try {
                            val buffer = loadModelFile("yolov5s_coco.tflite")
                            Log.d("YoloChestDetector", "✅ Successfully loaded yolov5s_coco.tflite")
                            Pair(buffer, "YOLOv5s")
                        } catch (e2: Exception) {
                            Log.e(
                                    "YoloChestDetector",
                                    "❌ No suitable YOLO model found. Available models should be yolov8s_coco.tflite or yolov5s_coco.tflite",
                                    e2
                            )
                            throw Exception("No YOLO model available for chest detection")
                        }
                    }

            Log.d("YoloChestDetector", "📦 Model file loaded: $modelName")

            val options =
                    Interpreter.Options().apply {
                        setNumThreads(4)
                        setUseNNAPI(false) // Disable NNAPI initially for better compatibility
                    }
            interpreter = Interpreter(modelBuffer, options)

            // Log model input/output info
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            Log.d("YoloChestDetector", "✅ Model loaded successfully: $modelName")
            Log.d("YoloChestDetector", "📊 Input shape: ${inputShape?.contentToString()}")
            Log.d("YoloChestDetector", "📊 Output shape: ${outputShape?.contentToString()}")
            Log.d("YoloChestDetector", "🎯 Confidence threshold: $confidenceThreshold")
            Log.d(
                    "YoloChestDetector",
                    "🔧 Using ${options.numThreads} threads, NNAPI: ${options.useNNAPI}"
            )

            // Test inference to ensure model works
            testModelInference()
        } catch (e: Exception) {
            Log.e("YoloChestDetector", "❌ Error loading model", e)
            interpreter = null
        }
    }

    private fun testModelInference() {
        try {
            Log.d("YoloChestDetector", "🧪 Testing model inference...")
            val testBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            testBitmap.eraseColor(android.graphics.Color.BLACK)

            val inputArray = preprocessImage(testBitmap)
            val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: return

            val output =
                    if (outputShape.size == 3 && outputShape[1] == 84) {
                        Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
                    } else {
                        Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
                    }

            interpreter?.run(inputArray, output)
            Log.d("YoloChestDetector", "✅ Model inference test successful")
        } catch (e: Exception) {
            Log.e("YoloChestDetector", "❌ Model inference test failed", e)
        }
    }

    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Detects chest region from the input bitmap Returns a ChestDetection object containing the
     * chest bounding box and confidence
     */
    fun detectChest(bitmap: Bitmap): List<ChestDetection> {
        val interpreter = this.interpreter
        if (interpreter == null) {
            Log.e("YoloChestDetector", "❌ Interpreter is null - model not loaded properly")
            return emptyList()
        }

        try {
            Log.d("YoloChestDetector", "🔍 Processing image: ${bitmap.width}x${bitmap.height}")

            // Preprocess image
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputArray = preprocessImage(resizedBitmap)
            Log.d("YoloChestDetector", "✅ Image preprocessed to ${inputSize}x${inputSize}")

            // Get output shape and prepare output array
            val outputShape = interpreter.getOutputTensor(0).shape()
            Log.d("YoloChestDetector", "📊 Output shape: ${outputShape.contentToString()}")

            // Try YOLOv8 format first: [1, 84, 8400] or YOLOv5 format: [1, 25200, 85]
            val output =
                    if (outputShape.size == 3 && outputShape[1] == 84) {
                        // YOLOv8 format: [1, 84, 8400]
                        Log.d("YoloChestDetector", "🎯 Using YOLOv8 output format")
                        Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
                    } else if (outputShape.size == 3 && outputShape[2] == 6) {
                        // Custom QR YOLO model format: [1, 25200, 6]
                        Log.d("YoloChestDetector", "🎯 Using custom QR YOLO format")
                        Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
                    } else {
                        // Standard YOLOv5 format: [1, 25200, 85]
                        Log.d("YoloChestDetector", "🎯 Using YOLOv5 output format")
                        Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
                    }

            // Run inference
            val startTime = System.currentTimeMillis()
            interpreter.run(inputArray, output)
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d("YoloChestDetector", "⚡ Inference completed in ${inferenceTime}ms")

            // Process output based on format
            val personDetections =
                    if (outputShape.size == 3 && outputShape[1] == 84) {
                        processYolov8Output(output[0], bitmap.width, bitmap.height)
                    } else if (outputShape.size == 3 && outputShape[2] == 6) {
                        // Custom QR YOLO model format: [1, 25200, 6]
                        Log.d("YoloChestDetector", "🎯 Using custom QR YOLO format")
                        processCustomQrYoloOutput(output[0], bitmap.width, bitmap.height)
                    } else {
                        // Standard YOLOv5 format: [1, 25200, 85]
                        processYolov5Output(output[0], bitmap.width, bitmap.height)
                    }

            Log.d("YoloChestDetector", "👤 Found ${personDetections.size} person detections")

            // Convert person detections to chest detections
            val chestDetections =
                    personDetections.map { detection ->
                        val chestRegion = calculateChestRegion(detection.boundingBox)
                        Log.d(
                                "YoloChestDetector",
                                "🫁 Person bbox: ${detection.boundingBox}, Chest region: $chestRegion, Confidence: ${detection.confidence}"
                        )
                        ChestDetection(
                                personBoundingBox = detection.boundingBox,
                                chestRegion = chestRegion,
                                confidence = detection.confidence
                        )
                    }

            if (chestDetections.isNotEmpty()) {
                Log.d("YoloChestDetector", "✅ Returning ${chestDetections.size} chest detections")
            } else {
                Log.d("YoloChestDetector", "❌ No valid chest detections found")
            }

            return chestDetections
        } catch (e: Exception) {
            Log.e("YoloChestDetector", "💥 Error during chest detection: ${e.message}", e)
            return emptyList()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val interpreter =
                this.interpreter
                        ?: return Array(1) {
                            Array(3) { Array(inputSize) { FloatArray(inputSize) } }
                        }

        // Check input format from model
        val inputShape = interpreter.getInputTensor(0).shape()
        val isNCHW = inputShape[1] == 3 // [N, C, H, W] format
        val isNHWC = inputShape[3] == 3 // [N, H, W, C] format

        Log.d(
                "YoloChestDetector",
                "📊 Input format: ${if (isNCHW) "NCHW" else if (isNHWC) "NHWC" else "Unknown"}"
        )

        return if (isNCHW) {
            // NCHW format: [1, 3, 640, 640] - for custom QR model
            val inputArray = Array(1) { Array(3) { Array(inputSize) { FloatArray(inputSize) } } }
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val pixel = bitmap.getPixel(x, y)
                    // Normalize to [0, 1] and convert to RGB
                    inputArray[0][0][y][x] = ((pixel shr 16) and 0xFF) / 255.0f // R
                    inputArray[0][1][y][x] = ((pixel shr 8) and 0xFF) / 255.0f // G
                    inputArray[0][2][y][x] = (pixel and 0xFF) / 255.0f // B
                }
            }
            inputArray
        } else {
            // NHWC format: [1, 640, 640, 3] - for YOLOv8
            val inputArray = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val pixel = bitmap.getPixel(x, y)
                    // Normalize to [0, 1] and convert to RGB
                    inputArray[0][y][x][0] = ((pixel shr 16) and 0xFF) / 255.0f // R
                    inputArray[0][y][x][1] = ((pixel shr 8) and 0xFF) / 255.0f // G
                    inputArray[0][y][x][2] = (pixel and 0xFF) / 255.0f // B
                }
            }
            inputArray
        }
    }

    private fun processYolov8Output(
            output: Array<FloatArray>,
            originalWidth: Int,
            originalHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        // YOLOv8 format: [84, 8400] where 84 = [x, y, w, h, class0_conf, class1_conf, ...]
        val numDetections = output[0].size

        for (i in 0 until numDetections) {
            val x = output[0][i]
            val y = output[1][i]
            val w = output[2][i]
            val h = output[3][i]

            // Person class confidence (class 0)
            val personConfidence = output[4][i] // Class 0 confidence

            if (personConfidence > confidenceThreshold) {
                // Convert from normalized coordinates to pixel coordinates
                val centerX = x * originalWidth
                val centerY = y * originalHeight
                val width = w * originalWidth
                val height = h * originalHeight

                val bbox =
                        RectF(
                                centerX - width / 2,
                                centerY - height / 2,
                                centerX + width / 2,
                                centerY + height / 2
                        )

                detections.add(Detection(bbox, personConfidence, personClassId))
            }
        }

        // Apply Non-Maximum Suppression
        return applyNMS(detections, iouThreshold)
    }

    private fun processYolov5Output(
            output: Array<FloatArray>,
            originalWidth: Int,
            originalHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        // YOLOv5 format: [25200, 85] where 85 = [x, y, w, h, obj_conf, class0_conf, class1_conf,
        // ...]
        for (i in output.indices) {
            val detection = output[i]
            val objectness = detection[4] // Objectness score

            if (objectness > confidenceThreshold) {
                val personConfidence = detection[5] // Person class confidence (class 0)

                if (personConfidence > confidenceThreshold) {
                    val finalConfidence = objectness * personConfidence

                    // Convert from YOLO format to bounding box
                    val centerX = detection[0] * originalWidth
                    val centerY = detection[1] * originalHeight
                    val width = detection[2] * originalWidth
                    val height = detection[3] * originalHeight

                    val bbox =
                            RectF(
                                    centerX - width / 2,
                                    centerY - height / 2,
                                    centerX + width / 2,
                                    centerY + height / 2
                            )

                    detections.add(Detection(bbox, finalConfidence, personClassId))
                }
            }
        }

        // Apply Non-Maximum Suppression
        return applyNMS(detections, iouThreshold)
    }

    private fun processCustomQrYoloOutput(
            output: Array<FloatArray>,
            originalWidth: Int,
            originalHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        // Custom QR YOLO format: [25200, 6] where 6 = [x, y, w, h, obj_conf, class_conf]
        // This model appears to be trained specifically for QR/object detection
        for (i in output.indices) {
            val detection = output[i]
            if (detection.size < 6) continue

            val centerX = detection[0]
            val centerY = detection[1]
            val width = detection[2]
            val height = detection[3]
            val objectness = detection[4] // Objectness score
            val classConfidence = detection[5] // Class confidence

            // Use a lower threshold for this custom model
            val customThreshold = 0.1f

            if (objectness > customThreshold && classConfidence > customThreshold) {
                val finalConfidence = objectness * classConfidence

                Log.d(
                        "YoloChestDetector",
                        "🔍 Custom detection: obj=${String.format("%.3f", objectness)}, " +
                                "class=${String.format("%.3f", classConfidence)}, " +
                                "final=${String.format("%.3f", finalConfidence)}"
                )

                // Convert from normalized coordinates to pixel coordinates
                val pixelCenterX = centerX * originalWidth
                val pixelCenterY = centerY * originalHeight
                val pixelWidth = width * originalWidth
                val pixelHeight = height * originalHeight

                val bbox =
                        RectF(
                                pixelCenterX - pixelWidth / 2,
                                pixelCenterY - pixelHeight / 2,
                                pixelCenterX + pixelWidth / 2,
                                pixelCenterY + pixelHeight / 2
                        )

                // Validate bounding box
                if (bbox.width() > 0 &&
                                bbox.height() > 0 &&
                                bbox.left >= 0 &&
                                bbox.top >= 0 &&
                                bbox.right <= originalWidth &&
                                bbox.bottom <= originalHeight
                ) {

                    detections.add(Detection(bbox, finalConfidence, 0)) // Use class 0 for person
                }
            }
        }

        Log.d("YoloChestDetector", "🎯 Custom QR YOLO found ${detections.size} raw detections")

        // Apply Non-Maximum Suppression
        val nmsDetections = applyNMS(detections, iouThreshold)
        Log.d("YoloChestDetector", "🎯 After NMS: ${nmsDetections.size} detections")

        return nmsDetections
    }

    private fun calculateChestRegion(personBbox: RectF): RectF {
        // Calculate chest region as upper portion of person bounding box
        val personHeight = personBbox.bottom - personBbox.top
        val chestHeight = personHeight * chestHeightRatio
        val chestTop = personBbox.top + (personHeight * chestTopOffset)

        return RectF(personBbox.left, chestTop, personBbox.right, chestTop + chestHeight)
    }

    private fun applyNMS(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        // Sort by confidence (highest first)
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = mutableListOf<Detection>()

        for (detection in sortedDetections) {
            var shouldSelect = true

            for (selectedDetection in selectedDetections) {
                val iou = calculateIoU(detection.boundingBox, selectedDetection.boundingBox)
                if (iou > iouThreshold) {
                    shouldSelect = false
                    break
                }
            }

            if (shouldSelect) {
                selectedDetections.add(detection)
            }
        }

        return selectedDetections
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)

        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return 0f
        }

        val intersectionArea =
                (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    data class Detection(val boundingBox: RectF, val confidence: Float, val classId: Int)

    fun release() {
        interpreter?.close()
        interpreter = null
        Log.d("YoloChestDetector", "YoloChestDetector released")
    }

    /**
     * Debug function to test YOLO detection with a simple test image Call this to verify the model
     * is working properly
     */
    fun debugTestDetection(): String {
        val interpreter = this.interpreter
        if (interpreter == null) {
            return "❌ YOLO model not loaded - check logs for loading errors"
        }

        try {
            Log.d("YoloChestDetector", "🧪 Starting debug test detection...")

            // Create a test image with some content
            val testBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(testBitmap)

            // Draw a simple person-like shape for testing
            val paint =
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        style = android.graphics.Paint.Style.FILL
                    }

            // Draw a simple rectangle representing a person
            canvas.drawRect(
                    inputSize * 0.3f,
                    inputSize * 0.2f,
                    inputSize * 0.7f,
                    inputSize * 0.8f,
                    paint
            )

            // Test detection
            val detections = detectChest(testBitmap)

            val result = StringBuilder()
            result.append("🧪 YOLO Debug Test Results:\n")
            result.append("📊 Model loaded: ✅\n")
            result.append("🎯 Input size: ${inputSize}x${inputSize}\n")
            result.append("🔍 Detections found: ${detections.size}\n")

            if (detections.isNotEmpty()) {
                detections.forEachIndexed { index, detection ->
                    result.append("Detection $index:\n")
                    result.append(
                            "  - Confidence: ${String.format("%.3f", detection.confidence)}\n"
                    )
                    result.append("  - Person bbox: ${detection.personBoundingBox}\n")
                    result.append("  - Chest region: ${detection.chestRegion}\n")
                }
                result.append("✅ YOLO detection is working!")
            } else {
                result.append("⚠️ No detections found - this may be normal for test image")
            }

            Log.d("YoloChestDetector", result.toString())
            return result.toString()
        } catch (e: Exception) {
            val errorMsg = "❌ Debug test failed: ${e.message}"
            Log.e("YoloChestDetector", errorMsg, e)
            return errorMsg
        }
    }

    /** Get model information for debugging */
    fun getModelInfo(): String {
        val interpreter = this.interpreter
        if (interpreter == null) {
            return "❌ Model not loaded"
        }

        try {
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)

            return """
                📊 YOLO Model Information:
                Input shape: ${inputTensor.shape().contentToString()}
                Input type: ${inputTensor.dataType()}
                Output shape: ${outputTensor.shape().contentToString()}
                Output type: ${outputTensor.dataType()}
                Confidence threshold: $confidenceThreshold
                IoU threshold: $iouThreshold
                Input size: ${inputSize}x${inputSize}
            """.trimIndent()
        } catch (e: Exception) {
            return "❌ Error getting model info: ${e.message}"
        }
    }
}





