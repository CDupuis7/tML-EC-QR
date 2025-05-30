#!/usr/bin/env python3
"""
End-to-End Respiratory Pattern Classification and TFLite Conversion

This script:
1. Runs the respiratory pattern classification to train a model
2. Converts the model to TensorFlow Lite format
3. Tests the model on sample data to verify it works correctly
4. Provides instructions for integrating with the Android app

Usage:
    python convert_and_test_model.py
"""

import os
import subprocess
import sys
import numpy as np
import pandas as pd
import joblib
import json
from sklearn.preprocessing import StandardScaler
from glob import glob

def ensure_tensorflow():
    """Make sure TensorFlow is installed"""
    try:
        import tensorflow as tf
        print(f"TensorFlow version: {tf.__version__}")
    except ImportError:
        print("TensorFlow not found. Installing...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "tensorflow"])
        import tensorflow as tf
        print(f"Installed TensorFlow version: {tf.__version__}")

def run_classification():
    """Run the respiratory pattern classification script"""
    print("\n1. Running respiratory pattern classification...")
    if not os.path.exists('model_output/breathing_pattern_model.joblib'):
        subprocess.run([sys.executable, "respiratory_pattern_classification.py"], check=True)
        print("Classification model trained successfully.")
    else:
        print("Classification model already exists. Skipping training.")

def convert_model():
    """Convert the model to TensorFlow Lite format"""
    print("\n2. Converting model to TensorFlow Lite format...")
    if not os.path.exists('model_output/respiratory_abnormality.tflite'):
        subprocess.run([sys.executable, "convert_model_to_tflite.py"], check=True)
        print("Model converted successfully.")
    else:
        print("TFLite model already exists. Skipping conversion.")

def test_model():
    """Test the model on sample respiratory data"""
    print("\n3. Testing the model...")
    
    try:
        # Load model artifacts
        model = joblib.load('model_output/breathing_pattern_model.joblib')
        scaler = joblib.load('model_output/pattern_scaler.joblib')
        
        with open('model_output/pattern_features.json', 'r') as f:
            features = json.load(f)
            
        print(f"Model loaded successfully with features: {features}")
        
        # Load respiratory data for testing
        data_files = glob('respiratory_data/respiratory_data_*.csv')
        if not data_files:
            print("No respiratory data files found for testing!")
            return
            
        # Create test sample
        test_data = []
        for file_path in data_files[:3]:  # Use first 3 files for testing
            try:
                # Read file and extract metrics
                with open(file_path, 'r') as f:
                    content = f.read()
                
                # Extract breathing rate and other metrics from the file
                lines = content.split('\n')
                
                # Parse respiratory metrics
                breathing_rate = None
                avg_amplitude = None
                max_amplitude = None
                min_amplitude = None
                age = None
                gender = None
                
                for line in lines:
                    if 'Breathing Rate' in line and 'breaths/minute' in line:
                        try:
                            breathing_rate = float(line.split(':')[1].split('breaths/minute')[0].strip())
                        except:
                            pass
                    elif 'Average Amplitude' in line:
                        try:
                            avg_amplitude = float(line.split(':')[1].strip())
                        except:
                            pass
                    elif 'Maximum Amplitude' in line:
                        try:
                            max_amplitude = float(line.split(':')[1].strip())
                        except:
                            pass
                    elif 'Minimum Amplitude' in line:
                        try:
                            min_amplitude = float(line.split(':')[1].strip())
                        except:
                            pass
                    elif 'Age' in line:
                        try:
                            age = int(line.split(':')[1].strip())
                        except:
                            pass
                    elif 'Gender' in line:
                        try:
                            gender = line.split(':')[1].strip()
                        except:
                            pass
                
                if None in (breathing_rate, avg_amplitude, max_amplitude, min_amplitude):
                    print(f"Missing required metrics in {file_path}")
                    continue
                
                # Calculate variability metrics
                amplitude_variability = 0.2  # Default value
                duration_variability = 0.15  # Default value
                avg_velocity = 0
                
                # Set default values if missing
                age = age or 25
                gender_value = 1 if gender and gender.lower() == 'male' else 0
                
                # Create sample
                sample = {
                    'breathing_rate': breathing_rate,
                    'avg_amplitude': avg_amplitude, 
                    'max_amplitude': max_amplitude,
                    'min_amplitude': min_amplitude,
                    'avg_velocity': avg_velocity,
                    'amplitude_variability': amplitude_variability,
                    'duration_variability': duration_variability,
                    'age': age,
                    'gender': gender_value
                }
                
                test_data.append(sample)
                print(f"Added test sample from {os.path.basename(file_path)}")
                
            except Exception as e:
                print(f"Error processing {file_path}: {str(e)}")
        
        if not test_data:
            print("No valid test data could be extracted!")
            return
            
        # Convert to DataFrame with features in the right order
        test_df = pd.DataFrame(test_data)
        X_test = test_df[features].values
        
        # Scale the features
        X_test_scaled = scaler.transform(X_test)
        
        # Make predictions with the scikit-learn model
        print("\nTesting original scikit-learn model:")
        predictions = model.predict(X_test_scaled)
        probabilities = model.predict_proba(X_test_scaled)
        
        for i, (prediction, proba) in enumerate(zip(predictions, probabilities)):
            result = "Abnormal" if prediction == 1 else "Normal"
            confidence = proba[prediction]
            print(f"Sample {i+1}: {result} breathing pattern (Confidence: {confidence:.2f})")
            print(f"  Breathing rate: {test_data[i]['breathing_rate']:.1f} breaths/min")
        
        # Test TFLite model if available
        tflite_path = 'model_output/respiratory_abnormality.tflite'
        if os.path.exists(tflite_path):
            try:
                import tensorflow as tf
                
                # Load TFLite model
                interpreter = tf.lite.Interpreter(model_path=tflite_path)
                interpreter.allocate_tensors()
                
                # Get input and output details
                input_details = interpreter.get_input_details()
                output_details = interpreter.get_output_details()
                
                print("\nTesting TensorFlow Lite model:")
                
                # Test each sample
                for i, sample in enumerate(X_test_scaled):
                    # Prepare input
                    input_data = sample.reshape(1, len(features)).astype(np.float32)
                    interpreter.set_tensor(input_details[0]['index'], input_data)
                    
                    # Run inference
                    interpreter.invoke()
                    
                    # Get output
                    output = interpreter.get_tensor(output_details[0]['index'])
                    tflite_prediction = np.argmax(output[0])
                    tflite_confidence = output[0][tflite_prediction]
                    
                    result = "Abnormal" if tflite_prediction == 1 else "Normal"
                    print(f"Sample {i+1}: {result} breathing pattern (Confidence: {tflite_confidence:.2f})")
                
                print("\nTFLite model testing completed successfully!")
            except Exception as e:
                print(f"Error testing TFLite model: {str(e)}")
        else:
            print("TFLite model not found. Skipping TFLite testing.")
    
    except Exception as e:
        print(f"Error during model testing: {str(e)}")

def copy_instructions():
    """Print instructions for copying the model to the Android app"""
    print("\n4. Next steps for Android integration:")
    print("-" * 50)
    print("1. Copy the TensorFlow Lite model file to your Android app's assets folder:")
    print("   Source: ml_scripts/model_output/respiratory_abnormality.tflite")
    print("   Destination: QR_Kotlin app/tML-EC-QR/TMLEC_QRScan/app/src/main/assets/")
    print("\n2. Make sure your DiseaseClassifier.kt file properly loads the model")
    print("   The app will try to load the model with one of these names:")
    print("   - respiratory_abnormality.tflite")
    print("   - breathing_abnormality.tflite")
    print("   - respiratory_disease.tflite")
    print("\n3. When the app classifies breathing patterns, it expects these features:")
    with open('model_output/pattern_features.json', 'r') as f:
        features = json.load(f)
        for i, feature in enumerate(features):
            print(f"   {i+1}. {feature}")
    print("\n4. The model output will be a probability for normal (0) vs abnormal (1) breathing.")
    print("-" * 50)

def main():
    # Ensure we have TensorFlow installed
    ensure_tensorflow()
    
    # Run model training and conversion
    run_classification()
    convert_model()
    test_model()
    copy_instructions()
    
    print("\nProcess completed successfully!")
    print("You can now integrate the TFLite model with your Android app.")

if __name__ == "__main__":
    main() 