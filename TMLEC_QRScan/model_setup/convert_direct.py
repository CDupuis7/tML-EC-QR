import torch
import tensorflow as tf
import numpy as np
import sys
import os

# Add YOLOv5 to Python path
yolov5_path = os.path.join(os.path.dirname(__file__), 'yolov5')
sys.path.append(yolov5_path)

from models.yolo import DetectionModel
from models.common import *
torch.serialization.add_safe_globals(['models.yolo.DetectionModel'])

def convert_to_tflite():
    # Load PyTorch model
    model_path = os.path.join(yolov5_path, 'runs', 'train', 'exp', 'weights', 'best.pt')
    model = torch.load(model_path, map_location='cpu', weights_only=False)
    model = model['model'].float()
    model.eval()

    # Create sample input
    input_shape = (1, 3, 416, 416)
    dummy_input = torch.randn(input_shape)

    # Export to ONNX
    torch.onnx.export(model, 
                     dummy_input,
                     'model.onnx',
                     input_names=['input'],
                     output_names=['output'],
                     dynamic_axes={'input': {0: 'batch_size'},
                                 'output': {0: 'batch_size'}})

    # Convert ONNX to TensorFlow
    import onnx
    from onnx_tf.backend import prepare

    onnx_model = onnx.load('model.onnx')
    tf_rep = prepare(onnx_model)
    tf_rep.export_graph('tf_model')

    # Convert to TFLite
    converter = tf.lite.TFLiteConverter.from_saved_model('tf_model')
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float32]
    tflite_model = converter.convert()

    # Save TFLite model
    with open('qr_yolov5_tiny.tflite', 'wb') as f:
        f.write(tflite_model)

    print('Model converted successfully!')

if __name__ == '__main__':
    convert_to_tflite() 