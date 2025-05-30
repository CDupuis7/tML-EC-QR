#!/usr/bin/env python3
"""
Convert the trained QR grid model to TFLite format
This script uses the actual trained model weights from the qr_grid_model training
"""

import os
import sys
import shutil
import logging
import numpy as np
import torch
import onnx
from pathlib import Path

# Add YOLOv5 to path
yolov5_path = Path(__file__).parent / "yolov5"
if yolov5_path.exists():
    sys.path.append(str(yolov5_path))

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

def convert_trained_model_to_tflite():
    """Convert the trained QR grid model to TFLite"""
    
    # Paths
    script_dir = Path(__file__).parent
    project_root = script_dir.parent.parent.parent.parent
    trained_model_path = project_root / "trained_models" / "qr_grid_model" / "weights" / "best.pt"
    output_onnx = script_dir / "qr_grid_trained.onnx"
    output_tflite = script_dir / "qr_grid_trained.tflite"
    android_assets = script_dir.parent / "app" / "src" / "main" / "assets"
    
    logger.info(f"Script directory: {script_dir}")
    logger.info(f"Project root: {project_root}")
    logger.info(f"Trained model path: {trained_model_path}")
    
    # Check if trained model exists
    if not trained_model_path.exists():
        logger.error(f"Trained model not found at: {trained_model_path}")
        return False
    
    logger.info(f"Found trained model: {trained_model_path}")
    
    try:
        # Load the trained PyTorch model
        logger.info("Loading trained PyTorch model...")
        device = torch.device('cpu')
        
        # Load with weights_only=False to handle YOLOv5 models
        model = torch.load(trained_model_path, map_location=device, weights_only=False)
        
        # Extract the model if it's wrapped in a dict
        if isinstance(model, dict):
            if 'model' in model:
                model = model['model']
            elif 'ema' in model:
                model = model['ema']
        
        # Set model to evaluation mode
        model.eval()
        model.float()
        
        logger.info(f"Model loaded successfully. Type: {type(model)}")
        
        # Create dummy input for export (640x640 as per training config)
        dummy_input = torch.randn(1, 3, 640, 640)
        
        # Export to ONNX
        logger.info("Exporting to ONNX...")
        torch.onnx.export(
            model,
            dummy_input,
            str(output_onnx),
            export_params=True,
            opset_version=11,
            do_constant_folding=True,
            input_names=['input'],
            output_names=['output'],
            dynamic_axes={
                'input': {0: 'batch_size'},
                'output': {0: 'batch_size'}
            }
        )
        
        logger.info(f"ONNX model exported to: {output_onnx}")
        
        # Verify ONNX model
        logger.info("Verifying ONNX model...")
        onnx_model = onnx.load(str(output_onnx))
        onnx.checker.check_model(onnx_model)
        logger.info("ONNX model verification passed")
        
        # Log model details
        for input_tensor in onnx_model.graph.input:
            shape = [dim.dim_value for dim in input_tensor.type.tensor_type.shape.dim]
            logger.info(f"Input '{input_tensor.name}': shape={shape}")
        
        for output_tensor in onnx_model.graph.output:
            shape = [dim.dim_value for dim in output_tensor.type.tensor_type.shape.dim]
            logger.info(f"Output '{output_tensor.name}': shape={shape}")
        
        # Convert ONNX to TFLite using onnx-tf
        logger.info("Converting ONNX to TensorFlow...")
        
        try:
            from onnx_tf.backend import prepare
            import tensorflow as tf
            
            # Convert to TensorFlow
            tf_rep = prepare(onnx_model)
            tf_model_path = script_dir / "tf_model"
            tf_rep.export_graph(str(tf_model_path))
            
            logger.info("TensorFlow model created")
            
            # Convert to TFLite
            logger.info("Converting to TFLite...")
            converter = tf.lite.TFLiteConverter.from_saved_model(str(tf_model_path))
            
            # Configure converter for YOLO model
            converter.target_spec.supported_ops = [
                tf.lite.OpsSet.TFLITE_BUILTINS,
                tf.lite.OpsSet.SELECT_TF_OPS
            ]
            converter.allow_custom_ops = True
            converter.target_spec.supported_types = [tf.float32]
            
            # Convert
            tflite_model = converter.convert()
            
            # Save TFLite model
            with open(output_tflite, 'wb') as f:
                f.write(tflite_model)
            
            logger.info(f"TFLite model saved to: {output_tflite}")
            
            # Test the TFLite model
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
            
            output_data = interpreter.get_tensor(output_details[0]['index'])
            logger.info(f"Test inference successful. Output shape: {output_data.shape}")
            
            # Copy to Android assets
            android_assets.mkdir(exist_ok=True)
            android_model_path = android_assets / "qr_yolov5_tiny.tflite"
            shutil.copy2(output_tflite, android_model_path)
            logger.info(f"Model copied to Android assets: {android_model_path}")
            
            # Clean up temporary files
            if tf_model_path.exists():
                shutil.rmtree(tf_model_path)
            
            logger.info("Conversion completed successfully!")
            return True
            
        except ImportError as e:
            logger.error(f"Missing dependencies: {e}")
            logger.error("Please install: pip install onnx-tf tensorflow")
            return False
            
    except Exception as e:
        logger.error(f"Error during conversion: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    success = convert_trained_model_to_tflite()
    if success:
        print("\n✅ Model conversion completed successfully!")
        print("The trained QR grid model has been converted to TFLite and copied to Android assets.")
    else:
        print("\n❌ Model conversion failed!")
        sys.exit(1) 