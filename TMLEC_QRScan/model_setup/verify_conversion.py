import numpy as np
import onnxruntime
import tensorflow as tf
import cv2
import logging
from PIL import Image

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def preprocess_image(image_path, target_size=(416, 416)):
    """Preprocess image for model input"""
    # Read and resize image
    img = cv2.imread(image_path)
    if img is None:
        raise ValueError(f"Could not read image at {image_path}")
    
    img = cv2.resize(img, target_size)
    img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    
    # Normalize to [0,1] and transpose to CHW format
    img = img.astype(np.float32) / 255.0
    img = np.transpose(img, (2, 0, 1))
    img = np.expand_dims(img, axis=0)
    return img

def run_onnx_inference(model_path, input_data):
    """Run inference using ONNX model"""
    logger.info("\nRunning ONNX model inference...")
    session = onnxruntime.InferenceSession(model_path)
    input_name = session.get_inputs()[0].name
    outputs = session.run(None, {input_name: input_data})
    
    logger.info("ONNX model output shapes:")
    for i, output in enumerate(outputs):
        logger.info(f"Output {i}: shape={output.shape}")
    
    return outputs

def run_tflite_inference(model_path, input_data):
    """Run inference using TFLite model"""
    logger.info("\nRunning TFLite model inference...")
    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()
    
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    
    interpreter.set_tensor(input_details[0]['index'], input_data)
    interpreter.invoke()
    
    outputs = []
    for output_detail in output_details:
        output_data = interpreter.get_tensor(output_detail['index'])
        outputs.append(output_data)
    
    logger.info("TFLite model output shapes:")
    for i, output in enumerate(outputs):
        logger.info(f"Output {i}: shape={output.shape}")
    
    return outputs

def compare_outputs(onnx_outputs, tflite_outputs):
    """Compare ONNX and TFLite model outputs"""
    logger.info("\nComparing model outputs:")
    
    if len(onnx_outputs) != len(tflite_outputs):
        logger.warning(f"Number of outputs differ: ONNX={len(onnx_outputs)}, TFLite={len(tflite_outputs)}")
        return False
    
    all_match = True
    for i, (onnx_out, tflite_out) in enumerate(zip(onnx_outputs, tflite_outputs)):
        logger.info(f"\nOutput {i}:")
        logger.info(f"ONNX shape: {onnx_out.shape}")
        logger.info(f"TFLite shape: {tflite_out.shape}")
        
        if onnx_out.shape != tflite_out.shape:
            logger.warning(f"Shape mismatch for output {i}")
            all_match = False
            continue
        
        # Compare values
        max_diff = np.max(np.abs(onnx_out - tflite_out))
        mean_diff = np.mean(np.abs(onnx_out - tflite_out))
        logger.info(f"Max absolute difference: {max_diff}")
        logger.info(f"Mean absolute difference: {mean_diff}")
        
        if max_diff > 1e-3:
            logger.warning(f"Large difference detected in output {i}")
            all_match = False
    
    return all_match

def verify_models():
    """Main verification function"""
    try:
        # Model paths
        onnx_path = 'qr_yolov5_tiny.onnx'
        tflite_path = 'qr_yolov5_tiny.tflite'
        
        # Create sample input (random data)
        input_data = np.random.randn(1, 3, 416, 416).astype(np.float32)
        
        # Run inference on both models
        onnx_outputs = run_onnx_inference(onnx_path, input_data)
        tflite_outputs = run_tflite_inference(tflite_path, input_data)
        
        # Compare outputs
        models_match = compare_outputs(onnx_outputs, tflite_outputs)
        
        if models_match:
            logger.info("\nVerification successful: Models produce matching outputs")
        else:
            logger.warning("\nVerification failed: Models produce different outputs")
        
    except Exception as e:
        logger.error(f"Error during verification: {str(e)}")
        import traceback
        traceback.print_exc()

if __name__ == '__main__':
    verify_models() 