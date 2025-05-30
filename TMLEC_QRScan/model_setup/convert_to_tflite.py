import torch
import torch.nn as nn
import tensorflow as tf
import numpy as np
import os

def convert_torchscript_to_tflite():
    # Load TorchScript model
    model_path = 'runs/train/exp/weights/best.torchscript'
    torch_model = torch.jit.load(model_path)
    torch_model.eval()
    
    # Create a wrapper class for TF conversion
    class ModelWrapper(nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model
            
        def forward(self, x):
            # Ensure input is in the correct format
            x = x / 255.0  # Normalize
            output = self.model(x)
            return output
    
    wrapped_model = ModelWrapper(torch_model)
    
    # Create sample input
    sample_input = torch.randn(1, 3, 416, 416)
    
    # Export to ONNX first
    onnx_path = 'runs/train/exp/weights/best.onnx'
    torch.onnx.export(wrapped_model, sample_input, onnx_path,
                     input_names=['input'],
                     output_names=['output'],
                     dynamic_axes={'input': {0: 'batch_size'},
                                 'output': {0: 'batch_size'}})
    
    # Convert ONNX to TFLite
    import tf2onnx
    import onnx
    
    # Load ONNX model
    onnx_model = onnx.load(onnx_path)
    
    # Convert to TF
    tf_rep = tf2onnx.convert.from_onnx(onnx_model)
    tf_model = tf_rep[0]
    
    # Convert to TFLite
    converter = tf.lite.TFLiteConverter.from_saved_model(tf_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float32]
    tflite_model = converter.convert()
    
    # Save TFLite model
    tflite_path = 'runs/train/exp/weights/best.tflite'
    with open(tflite_path, 'wb') as f:
        f.write(tflite_model)
    
    print(f'Model converted and saved to {tflite_path}')
    
    # Copy to Android assets folder
    android_assets_dir = '../app/src/main/assets'
    os.makedirs(android_assets_dir, exist_ok=True)
    android_model_path = os.path.join(android_assets_dir, 'qr_yolov5_tiny.tflite')
    import shutil
    shutil.copy2(tflite_path, android_model_path)
    print(f'Model copied to Android assets: {android_model_path}')

if __name__ == '__main__':
    convert_torchscript_to_tflite() 