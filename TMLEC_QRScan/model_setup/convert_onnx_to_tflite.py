import tensorflow as tf
import onnx
import os
import shutil
import logging
import numpy as np
from onnx_tf.backend import prepare

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def create_sample_input():
    # Create a sample input tensor with the same shape as the model expects
    return np.random.randn(1, 3, 416, 416).astype(np.float32)

def convert_onnx_to_tflite():
    try:
        # Input ONNX model path
        onnx_path = 'qr_yolov5_tiny.onnx'
        
        if not os.path.exists(onnx_path):
            logger.error(f"Error: ONNX model not found at {onnx_path}")
            return
            
        logger.info(f'Loading ONNX model from {onnx_path}...')
        onnx_model = onnx.load(onnx_path)
        
        # Convert ONNX to TensorFlow
        logger.info('Converting ONNX to TensorFlow...')
        tf_rep = prepare(onnx_model)
        
        # Save the TensorFlow model
        tf_path = 'tf_saved_model'
        logger.info(f'Saving TensorFlow model to {tf_path}...')
        tf_rep.export_graph(tf_path)
        
        if not os.path.exists(tf_path):
            logger.error(f"Error: TensorFlow model was not created at {tf_path}")
            return
            
        logger.info('Converting to TFLite...')
        # Convert to TFLite
        converter = tf.lite.TFLiteConverter.from_saved_model(tf_path)
        
        # Configure the converter for YOLOv5
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float32]
        converter.allow_custom_ops = True
        converter.experimental_new_converter = True
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS
        ]
        
        # Additional YOLOv5 specific settings
        converter.inference_input_type = tf.float32
        converter.inference_output_type = tf.float32
        
        # Preserve NHWC format
        converter.target_spec.supported_ops.append(tf.lite.OpsSet.TFLITE_BUILTINS_INT8)
        
        # Convert the model
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
        
        # Copy to Android assets folder
        android_assets_dir = '../app/src/main/assets'
        os.makedirs(android_assets_dir, exist_ok=True)
        android_model_path = os.path.join(android_assets_dir, 'qr_yolov5_tiny.tflite')
        shutil.copy2(tflite_path, android_model_path)
        logger.info(f'Model copied to Android assets: {android_model_path}')
        
        # Clean up temporary files
        if os.path.exists(tf_path):
            shutil.rmtree(tf_path)
            
    except Exception as e:
        logger.error(f"Error during conversion process: {str(e)}")
        import traceback
        traceback.print_exc()

if __name__ == '__main__':
    convert_onnx_to_tflite() 