import torch
import sys
import os

# Add YOLOv5 to Python path
yolov5_path = os.path.join(os.path.dirname(__file__), 'yolov5')
sys.path.append(yolov5_path)

from models.yolo import DetectionModel
from models.common import *
torch.serialization.add_safe_globals(['models.yolo.DetectionModel'])

def convert_to_onnx():
    # Load PyTorch model
    model_path = os.path.join(yolov5_path, 'runs', 'train', 'exp', 'weights', 'best.pt')
    model = torch.load(model_path, map_location='cpu', weights_only=False)
    model = model['model'].float()
    model.eval()

    # Create sample input
    input_shape = (1, 3, 416, 416)
    dummy_input = torch.randn(input_shape)

    # Export to ONNX
    onnx_path = 'qr_yolov5_tiny.onnx'
    torch.onnx.export(model, 
                     dummy_input,
                     onnx_path,
                     input_names=['input'],
                     output_names=['output'],
                     dynamic_axes={'input': {0: 'batch_size'},
                                 'output': {0: 'batch_size'}},
                     opset_version=12)

    print('Model converted to ONNX successfully!')

if __name__ == '__main__':
    convert_to_onnx() 