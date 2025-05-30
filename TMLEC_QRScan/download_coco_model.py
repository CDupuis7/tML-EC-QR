#!/usr/bin/env python3
"""
Download pre-trained YOLOv5 COCO model for chest detection
"""

import os
import requests
from pathlib import Path

def download_file(url, filename):
    """Download a file from URL with progress bar"""
    print(f"Downloading {filename}...")
    
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

def main():
    # Define paths
    assets_dir = Path("app/src/main/assets")
    assets_dir.mkdir(parents=True, exist_ok=True)
    
    # Model URLs and filenames
    models = [
        {
            "url": "https://github.com/ultralytics/yolov5/releases/download/v7.0/yolov5s.tflite",
            "filename": assets_dir / "yolov5s_coco.tflite",
            "description": "YOLOv5s COCO model (14MB) - Good balance of speed and accuracy"
        },
        {
            "url": "https://github.com/ultralytics/yolov5/releases/download/v7.0/yolov5n.tflite", 
            "filename": assets_dir / "yolov5n_coco.tflite",
            "description": "YOLOv5n COCO model (4MB) - Fastest, lower accuracy"
        }
    ]
    
    print("🚀 YOLOv5 COCO Model Downloader")
    print("=" * 50)
    
    # Backup existing QR model
    qr_model = assets_dir / "qr_yolov5_tiny.tflite"
    if qr_model.exists():
        backup_path = assets_dir / "qr_yolov5_tiny.tflite.backup"
        if not backup_path.exists():
            qr_model.rename(backup_path)
            print(f"📦 Backed up QR model to {backup_path}")
    
    # Download models
    for model in models:
        print(f"\n📥 {model['description']}")
        try:
            download_file(model["url"], model["filename"])
        except Exception as e:
            print(f"❌ Failed to download {model['filename']}: {e}")
            continue
    
    print("\n" + "=" * 50)
    print("✅ Download complete!")
    print("\nNext steps:")
    print("1. Update YoloChestDetector.kt to use 'yolov5s_coco.tflite'")
    print("2. Modify the detection logic for person detection")
    print("3. Implement chest region cropping")
    print("4. Test the application")
    
    # List downloaded files
    print(f"\n📁 Files in {assets_dir}:")
    for file in assets_dir.glob("*.tflite"):
        size_mb = file.stat().st_size / (1024 * 1024)
        print(f"  - {file.name} ({size_mb:.1f} MB)")

if __name__ == "__main__":
    main() 