# import tensorflow as tf
import tensorflow as tf
import onnx
import os
import shutil
import logging
import numpy as np
from onnx import helper, numpy_helper
from onnx_tf.backend import prepare
import sys

# Set up detailed logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger(__name__)

@tf.function(experimental_relax_shapes=True)
def preprocess_input(x):
    """Preprocess input tensor"""
    return tf.cast(x, tf.float32)

def verify_model_io(onnx_model):
    """Verify and log model input/output specifications"""
    logger.info("Model I/O Specifications:")
    
    # Check inputs
    for input in onnx_model.graph.input:
        shape = [dim.dim_value for dim in input.type.tensor_type.shape.dim]
        logger.info(f"Input '{input.name}': shape={shape}")
    
    # Check outputs
    for output in onnx_model.graph.output:
        shape = [dim.dim_value for dim in output.type.tensor_type.shape.dim]
        logger.info(f"Output '{output.name}': shape={shape}")

def create_yolov5_model():
    """Create a simplified TensorFlow model that preserves YOLOv5's output structure"""
    input_shape = (1, 3, 416, 416)
    inputs = tf.keras.Input(shape=input_shape[1:], batch_size=input_shape[0])
    
    # Create a simplified feature extraction backbone
    x = tf.keras.layers.Conv2D(32, 3, padding='same')(inputs)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.LeakyReLU(0.1)(x)
    
    # Create detection heads with correct output shapes
    # 52x52 head
    x1 = tf.keras.layers.Conv2D(18, 1, padding='same')(x)
    x1 = tf.keras.layers.Reshape((1, 3, 52, 52, 6))(x1)
    output1 = tf.keras.layers.Lambda(lambda x: x[:, 0])(x1)  # Remove batch dim
    
    # 26x26 head
    x2 = tf.keras.layers.Conv2D(18, 1, padding='same')(x)
    x2 = tf.keras.layers.Reshape((1, 3, 26, 26, 6))(x2)
    output2 = tf.keras.layers.Lambda(lambda x: x[:, 0])(x2)
    
    # 13x13 head
    x3 = tf.keras.layers.Conv2D(18, 1, padding='same')(x)
    x3 = tf.keras.layers.Reshape((1, 3, 13, 13, 6))(x3)
    output3 = tf.keras.layers.Lambda(lambda x: x[:, 0])(x3)
    
    # Combined output for detection
    output_combined = tf.keras.layers.Concatenate(axis=1)([
        tf.keras.layers.Reshape((1, -1))(output1),
        tf.keras.layers.Reshape((1, -1))(output2),
        tf.keras.layers.Reshape((1, -1))(output3)
    ])
    
    model = tf.keras.Model(inputs=inputs, outputs=[output_combined, output1, output2, output3])
    return model

def convert_onnx_to_tflite():
    try:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        onnx_path = os.path.join(script_dir, 'qr_yolov5_tiny.onnx')
        
        if not os.path.exists(onnx_path):
            logger.error(f"Error: ONNX model not found at {onnx_path}")
            return
            
        logger.info(f'Loading ONNX model from {onnx_path}...')
        onnx_model = onnx.load(onnx_path)
        logger.info('ONNX model loaded successfully')
        
        # Verify original model I/O
        verify_model_io(onnx_model)
        
        # Modify the ONNX model to fix output shapes
        graph = onnx_model.graph
        
        # Update input shape to have batch size 1
        input_tensor = graph.input[0]
        input_shape = input_tensor.type.tensor_type.shape
        input_shape.dim[0].dim_value = 1
        
        # Update output shapes
        for output in graph.output:
            if output.name == 'output':
                # Remove the dummy output
                graph.output.remove(output)
            else:
                # Set batch size to 1 for detection outputs
                output_shape = output.type.tensor_type.shape
                output_shape.dim[0].dim_value = 1
        
        # Save modified ONNX model
        modified_onnx_path = 'modified_qr_yolov5_tiny.onnx'
        onnx.save(onnx_model, modified_onnx_path)
        logger.info('Modified ONNX model saved successfully')
        
        # Convert to TensorFlow using onnx-tf
        logger.info('Converting modified ONNX model to TensorFlow...')
        
        # Load and convert the modified ONNX model
        onnx_model = onnx.load(modified_onnx_path)
        
        # Convert ONNX model to TensorFlow
        tf_rep = prepare(onnx_model)
        
        # Save the model
        tf_path = 'tf_saved_model'
        tf_rep.export_graph(tf_path)
        
        logger.info('TensorFlow model created successfully')
        
        # Convert to TFLite
        logger.info('Converting to TFLite...')
        converter = tf.lite.TFLiteConverter.from_saved_model(tf_path)
        
        # Configure the converter
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS
        ]
        converter.allow_custom_ops = True
        converter.experimental_new_converter = True
        converter.target_spec.supported_types = [tf.float32]
        
        # Disable optimizations to preserve the model structure
        converter.optimizations = []
        
        logger.info('Starting TFLite conversion...')
        try:
            tflite_model = converter.convert()
            logger.info('TFLite conversion completed successfully')
        except Exception as e:
            logger.error(f"Error during TFLite conversion: {str(e)}")
            return
        
        # Save TFLite model
        tflite_path = 'qr_yolov5_tiny.tflite'
        with open(tflite_path, 'wb') as f:
            f.write(tflite_model)
        
        logger.info(f'TFLite model saved to {tflite_path}')
        
        # Verify the converted model
        logger.info('Verifying converted model...')
        interpreter = tf.lite.Interpreter(model_path=tflite_path)
        try:
            interpreter.allocate_tensors()
            
            # Get input and output details
            input_details = interpreter.get_input_details()
            output_details = interpreter.get_output_details()
            
            logger.info("\nConverted TFLite model specifications:")
            logger.info("Input details:")
            for detail in input_details:
                logger.info(f"  Name: {detail['name']}")
                logger.info(f"  Shape: {detail['shape']}")
                logger.info(f"  Type: {detail['dtype']}")
            
            logger.info("\nOutput details:")
            for detail in output_details:
                logger.info(f"  Name: {detail['name']}")
                logger.info(f"  Shape: {detail['shape']}")
                logger.info(f"  Type: {detail['dtype']}")
            
            # Test inference with random input
            test_input = np.random.rand(1, 3, 416, 416).astype(np.float32)
            interpreter.set_tensor(input_details[0]['index'], test_input)
            interpreter.invoke()
            
            # Get outputs
            outputs = []
            for output_detail in output_details:
                output_data = interpreter.get_tensor(output_detail['index'])
                outputs.append(output_data)
                logger.info(f"Output shape: {output_data.shape}")
            
            logger.info("Successfully ran inference on test input")
            
        except Exception as e:
            logger.error(f"Error during model verification: {str(e)}")
            return
        
        # Copy to Android assets folder
        android_assets_dir = '../app/src/main/assets'
        os.makedirs(android_assets_dir, exist_ok=True)
        android_model_path = os.path.join(android_assets_dir, 'qr_yolov5_tiny.tflite')
        shutil.copy2(tflite_path, android_model_path)
        logger.info(f'Model copied to Android assets: {android_model_path}')
        
        # Clean up temporary files
        if os.path.exists(tf_path):
            shutil.rmtree(tf_path)
        if os.path.exists(modified_onnx_path):
            os.remove(modified_onnx_path)
        logger.info('Cleaned up temporary files')
            
    except Exception as e:
        logger.error(f"Error during conversion process: {str(e)}")
        import traceback
        traceback.print_exc()

if __name__ == '__main__':
    convert_onnx_to_tflite() 