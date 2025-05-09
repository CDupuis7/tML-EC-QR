#!/usr/bin/env python3
"""
Test BIDMC-trained Model on Android App Respiratory Data

This script tests the model trained on BIDMC dataset against the
respiratory data collected from the Android QR code tracking app.

The script:
1. Loads the trained model and scaler
2. Processes the Android app respiratory data
3. Predicts breathing patterns and evaluates performance
4. Generates visualizations comparing model predictions with app labels
"""

import os
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
from sklearn.metrics import confusion_matrix, classification_report, accuracy_score
import joblib
import json
from glob import glob

def load_model_and_scaler(model_dir='model_output'):
    """Load the trained model, scaler, and feature names."""
    model = joblib.load(f"{model_dir}/breathing_model.joblib")
    scaler = joblib.load(f"{model_dir}/scaler.joblib")
    
    with open(f"{model_dir}/feature_names.json", 'r') as f:
        feature_names = json.load(f)
        
    return model, scaler, feature_names

def load_respiratory_data(data_dir='respiratory_data'):
    """Load all respiratory data files from the app."""
    all_data = []
    files = glob(f"{data_dir}/respiratory_data_*.csv")
    
    for file_path in files:
        try:
            # Read the file content
            with open(file_path, 'r') as f:
                content = f.read()
            
            # Extract patient info and breathing metrics from the file content
            patient_id = None
            age = None
            gender = None
            health_status = None
            total_duration = None
            breathing_rate = None
            avg_amplitude = None
            max_amplitude = None
            min_amplitude = None
            total_breaths = None
            
            # Parse the header information
            lines = content.split('\n')
            for i, line in enumerate(lines):
                if 'ID:' in line:
                    patient_id = line.split('ID:')[1].strip()
                elif 'Age:' in line:
                    age = int(line.split('Age:')[1].strip())
                elif 'Gender:' in line:
                    gender = line.split('Gender:')[1].strip()
                elif 'Health Status:' in line:
                    health_status = line.split('Health Status:')[1].strip()
                elif 'Total Duration:' in line:
                    total_duration = float(line.split('Total Duration:')[1].split('seconds')[0].strip())
                elif 'Breathing Rate:' in line:
                    breathing_rate = float(line.split('Breathing Rate:')[1].split('breaths/minute')[0].strip())
                elif 'Average Amplitude:' in line:
                    avg_amplitude = float(line.split('Average Amplitude:')[1].strip())
                elif 'Maximum Amplitude:' in line:
                    max_amplitude = float(line.split('Maximum Amplitude:')[1].strip())
                elif 'Minimum Amplitude:' in line:
                    min_amplitude = float(line.split('Minimum Amplitude:')[1].strip())
                elif 'Total Breaths:' in line:
                    total_breaths = int(line.split('Total Breaths:')[1].strip())
                
                # Find where the CSV data starts (after header section)
                if 'timestamp' in line:
                    header_index = i
                    break
            
            # Load the actual data using pandas
            df = pd.read_csv(file_path, skiprows=header_index)
            
            # Add file metadata to dataframe
            df['file_path'] = os.path.basename(file_path)
            df['patient_id'] = patient_id if patient_id else '0'
            df['age'] = age if age else 0
            df['gender'] = gender if gender else 'Unknown'
            df['health_status'] = health_status if health_status else 'Unknown'
            df['total_duration'] = total_duration if total_duration else 0
            df['breathing_rate'] = breathing_rate if breathing_rate else 0
            df['avg_amplitude'] = avg_amplitude if avg_amplitude else 0
            df['max_amplitude'] = max_amplitude if max_amplitude else 0
            df['min_amplitude'] = min_amplitude if min_amplitude else 0
            df['total_breaths'] = total_breaths if total_breaths else 0
            
            all_data.append(df)
            print(f"Loaded {file_path}: {len(df)} rows")
            
        except Exception as e:
            print(f"Error loading {file_path}: {str(e)}")
    
    if all_data:
        return pd.concat(all_data, ignore_index=True)
    else:
        raise ValueError("No data could be loaded from the respiratory data files")

def prepare_features_for_model(df, feature_names):
    """Prepare features from app data to match the model's expected format."""
    # Convert breathing_phase to binary (0 for exhaling, 1 for inhaling)
    df['phase'] = df['breathing_phase'].apply(lambda x: 1 if x == 'inhaling' else 0)
    
    # Convert gender to binary (1 for Male, 0 for Female)
    df['gender_binary'] = df['gender'].apply(lambda x: 1 if x == 'Male' else 0)
    
    # Location is not in the app data, so we'll set it to 0 (assuming non-micu)
    df['location'] = 0
    
    # Extract features in the same order expected by the model
    features = pd.DataFrame()
    
    for feature in feature_names:
        if feature == 'amplitude':
            features[feature] = df['amplitude']
        elif feature == 'max_velocity':
            features[feature] = df['velocity'].abs()
        elif feature == 'duration':
            # Use a default duration since app data tracks individual data points
            # We could calculate this from timestamp differences
            features[feature] = df.groupby('patient_id')['total_duration'].transform('mean') / df.groupby('patient_id')['total_breaths'].transform('mean')
        elif feature == 'phase':
            features[feature] = df['phase']
        elif feature == 'age':
            features[feature] = df['age']
        elif feature == 'gender':
            features[feature] = df['gender_binary']
        elif feature == 'location':
            features[feature] = df['location']
    
    return features

def evaluate_model(model, scaler, X, y_true):
    """Evaluate model performance on app data."""
    # Scale features
    X_scaled = scaler.transform(X)
    
    # Make predictions
    y_pred = model.predict(X_scaled)
    
    # Calculate metrics
    accuracy = accuracy_score(y_true, y_pred)
    report = classification_report(y_true, y_pred)
    cm = confusion_matrix(y_true, y_pred)
    
    # Print results
    print(f"Model Accuracy on App Data: {accuracy:.4f}")
    print("\nClassification Report:")
    print(report)
    
    # Plot confusion matrix
    plt.figure(figsize=(10, 8))
    sns.heatmap(cm, annot=True, fmt='d', cmap='Blues', 
                xticklabels=['Exhaling', 'Inhaling'],
                yticklabels=['Exhaling', 'Inhaling'])
    plt.xlabel('Predicted')
    plt.ylabel('Actual')
    plt.title('Confusion Matrix')
    plt.savefig('confusion_matrix_app_data.png')
    
    return accuracy, report, cm

def plot_breathing_phase_comparison(df, y_pred):
    """Plot actual vs predicted breathing phases over time."""
    # Add predictions to dataframe
    df['predicted_phase'] = y_pred
    
    # Convert phase labels for better readability
    df['actual_phase_label'] = df['breathing_phase'].replace({
        'inhaling': 'Inhaling', 
        'exhaling': 'Exhaling',
        'pause': 'Pause'
    })
    df['predicted_phase_label'] = df['predicted_phase'].apply(
        lambda x: 'Inhaling' if x == 1 else 'Exhaling'
    )
    
    # Select a subset of data for the first patient
    first_patient_id = df['patient_id'].iloc[0]
    sample_data = df[df['patient_id'] == first_patient_id].iloc[:300]
    
    # Plot
    plt.figure(figsize=(15, 8))
    
    plt.subplot(2, 1, 1)
    sns.lineplot(x='timestamp', y='amplitude', data=sample_data)
    plt.title('Breathing Amplitude Over Time')
    plt.xlabel('Timestamp')
    plt.ylabel('Amplitude')
    
    plt.subplot(2, 1, 2)
    sns.scatterplot(x='timestamp', y='actual_phase_label', data=sample_data, 
                   label='Actual', alpha=0.7, s=50, marker='o')
    sns.scatterplot(x='timestamp', y='predicted_phase_label', data=sample_data, 
                   label='Predicted', alpha=0.7, s=50, marker='x')
    plt.title('Actual vs Predicted Breathing Phases')
    plt.xlabel('Timestamp')
    plt.ylabel('Breathing Phase')
    plt.legend()
    plt.tight_layout()
    
    plt.savefig('breathing_phase_comparison.png')

def main():
    """Main function to test the model on app data."""
    # Load model and scaler
    print("Loading model and scaler...")
    model, scaler, feature_names = load_model_and_scaler()
    
    # Load app respiratory data
    print("Loading respiratory data from app...")
    app_data = load_respiratory_data()
    
    # Prepare features
    print("Preparing features for model...")
    X = prepare_features_for_model(app_data, feature_names)
    
    # Get true labels (0 for exhaling, 1 for inhaling)
    y_true = app_data['breathing_phase'].apply(lambda x: 1 if x == 'inhaling' else 0).values
    
    # Evaluate model
    print("Evaluating model on app data...")
    accuracy, report, cm = evaluate_model(model, scaler, X, y_true)
    
    # Plot comparison
    print("Generating visualizations...")
    plot_breathing_phase_comparison(app_data, model.predict(scaler.transform(X)))
    
    print("\nDone! Results saved to confusion_matrix_app_data.png and breathing_phase_comparison.png")

if __name__ == "__main__":
    main() 