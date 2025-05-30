import onnx
import tensorflow as tf
import numpy as np
import logging
from PIL import Image
import cv2

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def load_and_verify_onnx():
    logger.info("Verifying ONNX model...")
    # Load ONNX model
    onnx_model = onnx.load('qr_yolov5_tiny.onnx')
    
    # Get input shape
    input_shape = None
    for input in onnx_model.graph.input:
        input_shape = [d.dim_value for d in input.type.tensor_type.shape.dim]
        logger.info(f"ONNX Input shape: {input_shape}")
    
    # Get output shape
    output_shapes = []
    for output in onnx_model.graph.output:
        output_shape = [d.dim_value for d in output.type.tensor_type.shape.dim]
        output_shapes.append(output_shape)
        logger.info(f"ONNX Output shape: {output_shape}")
    
    return input_shape, output_shapes

def load_and_verify_tflite():
    logger.info("Verifying TFLite model...")
    # Load TFLite model
    interpreter = tf.lite.Interpreter(model_path='qr_yolov5_tiny.tflite')
    interpreter.allocate_tensors()
    
    # Get input and output details
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    
    logger.info(f"TFLite Input shape: {input_details[0]['shape']}")
    for output in output_details:
        logger.info(f"TFLite Output shape: {output['shape']}")
    
    return input_details[0]['shape'], [output['shape'] for output in output_details]

def test_model_with_sample_image():
    logger.info("Testing model with sample image...")
    # Create a sample 416x416 image with a QR code pattern
    img = np.zeros((416, 416, 3), dtype=np.uint8)
    # Draw a white square in the middle (simulating a QR code)
    img[156:260, 156:260] = 255
    
    # Preprocess image
    img = img.astype(np.float32) / 255.0  # Normalize
    img = np.transpose(img, (2, 0, 1))  # HWC to CHW
    img = np.expand_dims(img, axis=0)  # Add batch dimension
    
    # Load and test TFLite model
    interpreter = tf.lite.Interpreter(model_path='qr_yolov5_tiny.tflite')
    interpreter.allocate_tensors()
    
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    
    # Set input tensor
    interpreter.set_tensor(input_details[0]['index'], img)
    
    # Run inference
    try:
        interpreter.invoke()
        
        # Get output tensor
        outputs = []
        for output in output_details:
            output_data = interpreter.get_tensor(output['index'])
            outputs.append(output_data)
            logger.info(f"Output shape: {output_data.shape}")
            logger.info(f"Output min/max values: {np.min(output_data)}, {np.max(output_data)}")
        
        logger.info("Model successfully ran inference!")
        return True
    except Exception as e:
        logger.error(f"Error running inference: {str(e)}")
        return False

if __name__ == '__main__':
    # Verify ONNX model
    onnx_input_shape, onnx_output_shapes = load_and_verify_onnx()
    
    # Verify TFLite model
    tflite_input_shape, tflite_output_shapes = load_and_verify_tflite()
    
    # Compare shapes
    logger.info("\nComparing shapes:")
    logger.info(f"ONNX input shape: {onnx_input_shape}")
    logger.info(f"TFLite input shape: {tflite_input_shape}")
    logger.info(f"ONNX output shapes: {onnx_output_shapes}")
    logger.info(f"TFLite output shapes: {tflite_output_shapes}")
    
    # Test with sample image
    success = test_model_with_sample_image()
    if success:
        logger.info("\nModel verification completed successfully!")
    else:
        logger.error("\nModel verification failed!") 