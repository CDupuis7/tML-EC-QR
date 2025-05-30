import os
import qrcode
import numpy as np
from PIL import Image
import random

def generate_qr_code(data, size=416):
    qr = qrcode.QRCode(version=1, box_size=10, border=2)
    qr.add_data(data)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    img = img.resize((size, size))
    return img

def create_dataset(num_samples=100, train_ratio=0.8):
    base_path = os.path.dirname(os.path.abspath(__file__))
    train_img_path = os.path.join(base_path, 'images', 'train')
    val_img_path = os.path.join(base_path, 'images', 'val')
    train_label_path = os.path.join(base_path, 'labels', 'train')
    val_label_path = os.path.join(base_path, 'labels', 'val')

    # Create sample QR codes
    for i in range(num_samples):
        # Generate random data for QR code
        data = f"Sample QR Code {i}"
        img = generate_qr_code(data)
        
        # Add random rotation and position
        angle = random.uniform(-30, 30)
        img = img.rotate(angle, expand=True)
        
        # Convert to numpy array
        img_np = np.array(img)
        
        # Calculate bounding box (normalized coordinates)
        rows = np.any(img_np < 128, axis=1)
        cols = np.any(img_np < 128, axis=0)
        y1, y2 = np.where(rows)[0][[0, -1]]
        x1, x2 = np.where(cols)[0][[0, -1]]
        
        # Normalize coordinates
        h, w = img_np.shape
        x_center = (x1 + x2) / (2.0 * w)
        y_center = (y1 + y2) / (2.0 * h)
        width = (x2 - x1) / w
        height = (y2 - y1) / h
        
        # Save image and label
        if i < num_samples * train_ratio:
            img_path = os.path.join(train_img_path, f'qr_{i}.jpg')
            label_path = os.path.join(train_label_path, f'qr_{i}.txt')
        else:
            img_path = os.path.join(val_img_path, f'qr_{i}.jpg')
            label_path = os.path.join(val_label_path, f'qr_{i}.txt')
            
        img.save(img_path)
        
        # Save label (class_id x_center y_center width height)
        with open(label_path, 'w') as f:
            f.write(f'0 {x_center:.6f} {y_center:.6f} {width:.6f} {height:.6f}\n')

if __name__ == '__main__':
    create_dataset() 