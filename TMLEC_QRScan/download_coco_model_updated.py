#!/usr/bin/env python3
"""
Download pre-trained YOLO COCO models for chest detection
Updated with working download links
"""

import os
import requests
from pathlib import Path
import urllib.request

def download_file_urllib(url, filename):
    """Download a file using urllib with progress"""
    print(f"Downloading {filename} from {url}...")
    
    def progress_hook(block_num, block_size, total_size):
        downloaded = block_num * block_size
        if total_size > 0:
            percent = min(100, (downloaded / total_size) * 100)
            print(f"\rProgress: {percent:.1f}% ({downloaded}/{total_size} bytes)", end='')
    
    try:
        urllib.request.urlretrieve(url, filename, progress_hook)
        print(f"\n✅ Downloaded {filename} successfully!")
        return True
    except Exception as e:
        print(f"\n❌ Failed to download {filename}: {e}")
        return False

def download_file_requests(url, filename):
    """Download a file using requests with progress"""
    print(f"Downloading {filename} from {url}...")
    
    try:
        response = requests.get(url, stream=True)
        response.raise_for_status()
        
        total_size = int(response.headers.get('content-length', 0))
        downloaded = 0
        
        with open(filename, 'wb') as f:
            for chunk in response.iter_content(chunk_size=8192):
                if chunk:
                    f.write(chunk)
                    downloaded += len(chunk)
                    if total_size > 0:
                        percent = (downloaded / total_size) * 100
                        print(f"\rProgress: {percent:.1f}% ({downloaded}/{total_size} bytes)", end='')
        
        print(f"\n✅ Downloaded {filename} successfully!")
        return True
    except Exception as e:
        print(f"\n❌ Failed to download {filename}: {e}")
        return False

def main():
    # Define paths
    assets_dir = Path("app/src/main/assets")
    assets_dir.mkdir(parents=True, exist_ok=True)
    
    print("🚀 YOLO COCO Model Downloader (Updated)")
    print("=" * 50)
    
    # Backup existing QR model
    qr_model = assets_dir / "qr_yolov5_tiny.tflite"
    if qr_model.exists():
        backup_path = assets_dir / "qr_yolov5_tiny.tflite.backup"
        if not backup_path.exists():
            qr_model.rename(backup_path)
            print(f"📦 Backed up QR model to {backup_path}")
    
    # Model options with working links
    models = [
        {
            "name": "YOLOv8s TFLite",
            "url": "https://drive.google.com/uc?export=download&id=1htBxF8LlAiZEZgu3bwpn0hk--7TRaTld",
            "filename": assets_dir / "yolov8s_coco.tflite",
            "description": "YOLOv8s COCO model (45MB) - Good balance of speed and accuracy",
            "size": "45MB"
        },
        {
            "name": "YOLOv5s PyTorch (for conversion)",
            "url": "https://github.com/ultralytics/yolov5/releases/download/v7.0/yolov5s.pt",
            "filename": assets_dir / "yolov5s.pt",
            "description": "YOLOv5s PyTorch model (14MB) - Can be converted to TFLite",
            "size": "14MB"
        }
    ]
    
    # Alternative: Create a simple COCO person detection model
    print("\n🔧 Creating COCO person detection configuration...")
    
    # Create a model configuration file
    model_config = {
        "model_name": "yolo_person_detector",
        "input_size": 640,
        "classes": ["person"],
        "class_id": 0,
        "confidence_threshold": 0.5,
        "iou_threshold": 0.4,
        "chest_region_ratio": {
            "top_offset": 0.15,  # Start chest region 15% down from top of person bbox
            "height_ratio": 0.4  # Chest region is 40% of person height
        }
    }
    
    import json
    config_file = assets_dir / "person_detection_config.json"
    with open(config_file, 'w') as f:
        json.dump(model_config, f, indent=2)
    print(f"✅ Created configuration file: {config_file}")
    
    # Try to download models
    success_count = 0
    for model in models:
        print(f"\n📥 {model['description']}")
        print(f"   Size: {model['size']}")
        
        # Try requests first, then urllib
        if download_file_requests(model["url"], model["filename"]):
            success_count += 1
        elif download_file_urllib(model["url"], model["filename"]):
            success_count += 1
    
    print("\n" + "=" * 50)
    print(f"✅ Download complete! ({success_count}/{len(models)} successful)")
    
    if success_count == 0:
        print("\n⚠️  No models downloaded successfully.")
        print("📋 Manual download instructions:")
        print("1. Go to: https://github.com/ultralytics/yolov5/releases/tag/v7.0")
        print("2. Download yolov5s.pt (14MB)")
        print("3. Place it in app/src/main/assets/")
        print("4. Convert to TFLite using: python export.py --weights yolov5s.pt --include tflite")
        print("\nAlternatively:")
        print("1. Go to: https://drive.google.com/file/d/1htBxF8LlAiZEZgu3bwpn0hk--7TRaTld/view")
        print("2. Download YOLOv8s TFLite model (45MB)")
        print("3. Rename to yolov8s_coco.tflite and place in assets/")
    
    print("\n📋 Next steps:")
    print("1. Update YoloChestDetector.kt to use the downloaded model")
    print("2. Modify detection logic for person detection (class ID 0)")
    print("3. Implement chest region cropping using the configuration")
    print("4. Test the application with person detection")
    
    # List all files in assets
    print(f"\n📁 Files in {assets_dir}:")
    for file in assets_dir.glob("*"):
        if file.is_file():
            size_mb = file.stat().st_size / (1024 * 1024)
            print(f"  - {file.name} ({size_mb:.1f} MB)")

if __name__ == "__main__":
    main() 