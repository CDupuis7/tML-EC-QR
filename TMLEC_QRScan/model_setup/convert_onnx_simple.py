#!/usr/bin/env python3
"""
Simple ONNX to TFLite conversion for the trained QR grid model
"""

import os
import sys
import shutil
import logging
import numpy as np
from pathlib import Path

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

def convert_onnx_to_tflite():
    """Convert the ONNX model to TFLite with fixed batch size"""
    
    script_dir = Path(__file__).parent
    onnx_path = script_dir / "qr_yolov5_tiny.onnx"
    output_tflite = script_dir / "qr_yolov5_tiny_new.tflite"
    android_assets = script_dir.parent / "app" / "src" / "main" / "assets"
    
    if not onnx_path.exists():
        logger.error(f"ONNX model not found at: {onnx_path}")
        return False
    
    try:
        import onnx
        import tensorflow as tf
        from onnx_tf.backend import prepare
        
        logger.info("Loading ONNX model...")
        onnx_model = onnx.load(str(onnx_path))
        
        # Fix dynamic batch size to 1
        logger.info("Fixing batch size to 1...")
        for input_tensor in onnx_model.graph.input:
            if input_tensor.type.tensor_type.shape.dim[0].dim_value == 0:
                input_tensor.type.tensor_type.shape.dim[0].dim_value = 1
        
        for output_tensor in onnx_model.graph.output:
            if output_tensor.type.tensor_type.shape.dim[0].dim_value == 0:
                output_tensor.type.tensor_type.shape.dim[0].dim_value = 1
        
        # Save the fixed ONNX model
        fixed_onnx_path = script_dir / "qr_yolov5_tiny_fixed.onnx"
        onnx.save(onnx_model, str(fixed_onnx_path))
        
        logger.info("Converting to TensorFlow...")
        tf_rep = prepare(onnx_model)
        tf_model_path = script_dir / "tf_model_fixed"
        tf_rep.export_graph(str(tf_model_path))
        
        logger.info("Converting to TFLite...")
        converter = tf.lite.TFLiteConverter.from_saved_model(str(tf_model_path))
        
        # More permissive converter settings
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS
        ]
        converter.allow_custom_ops = True
        converter.target_spec.supported_types = [tf.float32]
        converter.experimental_new_converter = True
        
        # Disable some optimizations that might cause issues
        converter.optimizations = []
        
        try:
            tflite_model = converter.convert()
            
            # Save TFLite model
            with open(output_tflite, 'wb') as f:
                f.write(tflite_model)
            
            logger.info(f"TFLite model saved to: {output_tflite}")
            
            # Test the model
            logger.info("Testing TFLite model...")
            interpreter = tf.lite.Interpreter(model_path=str(output_tflite))
            interpreter.allocate_tensors()
            
            input_details = interpreter.get_input_details()
            output_details = interpreter.get_output_details()
            
            logger.info("TFLite model details:")
            for detail in input_details:
                logger.info(f"  Input: {detail['name']}, shape: {detail['shape']}, dtype: {detail['dtype']}")
            
            for detail in output_details:
                logger.info(f"  Output: {detail['name']}, shape: {detail['shape']}, dtype: {detail['dtype']}")
            
            # Test inference
            test_input = np.random.rand(*input_details[0]['shape']).astype(np.float32)
            interpreter.set_tensor(input_details[0]['index'], test_input)
            interpreter.invoke()
            
            for i, detail in enumerate(output_details):
                output_data = interpreter.get_tensor(detail['index'])
                logger.info(f"Output {i} shape: {output_data.shape}, range: [{output_data.min():.3f}, {output_data.max():.3f}]")
            
            # Copy to Android assets
            android_assets.mkdir(exist_ok=True)
            android_model_path = android_assets / "qr_yolov5_tiny.tflite"
            shutil.copy2(output_tflite, android_model_path)
            logger.info(f"Model copied to Android assets: {android_model_path}")
            
            # Clean up
            if tf_model_path.exists():
                shutil.rmtree(tf_model_path)
            
            logger.info("Conversion completed successfully!")
            return True
            
        except Exception as e:
            logger.error(f"TFLite conversion failed: {e}")
            logger.info("Trying alternative conversion method...")
            
            # Alternative: Use representative dataset
            def representative_dataset():
                for _ in range(100):
                    yield [np.random.rand(1, 3, 640, 640).astype(np.float32)]
            
            converter.representative_dataset = representative_dataset
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            
            try:
                tflite_model = converter.convert()
                
                with open(output_tflite, 'wb') as f:
                    f.write(tflite_model)
                
                logger.info(f"Alternative conversion successful: {output_tflite}")
                
                # Copy to Android assets
                android_model_path = android_assets / "qr_yolov5_tiny.tflite"
                shutil.copy2(output_tflite, android_model_path)
                logger.info(f"Model copied to Android assets: {android_model_path}")
                
                return True
                
            except Exception as e2:
                logger.error(f"Alternative conversion also failed: {e2}")
                return False
            
    except ImportError as e:
        logger.error(f"Missing dependencies: {e}")
        logger.error("Please install: pip install onnx onnx-tf tensorflow")
        return False
    except Exception as e:
        logger.error(f"Error during conversion: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    success = convert_onnx_to_tflite()
    if success:
        print("\n✅ ONNX to TFLite conversion completed!")
    else:
        print("\n❌ Conversion failed!")
        sys.exit(1) 