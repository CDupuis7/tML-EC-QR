#!/usr/bin/env python
# Script to export model weights from respiratory_pattern_classification.py to JSON format for Android app

import os
import json
import numpy as np
import joblib
from sklearn.linear_model import LogisticRegression
import sys

def export_model_weights(model_path, output_path, thresholds=None):
    """
    Export model weights from a trained scikit-learn logistic regression model to JSON format
    
    Args:
        model_path: Path to the saved model file (.joblib or .pkl)
        output_path: Path where the JSON file should be saved
        thresholds: Dictionary of threshold values to include in the output
    """
    try:
        print(f"Loading model from {model_path}")
        model = joblib.load(model_path)
        
        if not isinstance(model, LogisticRegression):
            print(f"Error: Expected LogisticRegression model, got {type(model)}")
            return False
        
        # Extract model parameters
        if model.coef_.shape[0] == 1:
            # Binary classification
            feature_weights = model.coef_[0].tolist()
            bias = model.intercept_[0]
        else:
            # Multi-class classification - use weights for the "abnormal" class (assuming class 1)
            feature_weights = model.coef_[1].tolist()
            bias = model.intercept_[1]
        
        # Feature names for the respiratory dataset
        feature_names = ["breathing_rate", "irregularity_index", "amplitude_variation", "avg_velocity"]
        
        if len(feature_weights) != len(feature_names):
            print(f"Warning: Number of weights ({len(feature_weights)}) doesn't match number of feature names ({len(feature_names)})")
            
        # Create weight dictionary
        weights = dict(zip(feature_names, feature_weights))
        weights["bias"] = float(bias)  # Add bias term
        
        # Define default thresholds if not provided
        if thresholds is None:
            thresholds = {
                "BRADYPNEA_THRESHOLD": 10.0,
                "TACHYPNEA_THRESHOLD": 24.0,
                "IRREGULARITY_THRESHOLD": 0.4,
                "AMPLITUDE_VARIATION_THRESHOLD": 40.0,
                "VELOCITY_THRESHOLD": 8.0
            }
        
        # Create output dictionary
        output = {
            "model_type": "logistic_regression",
            "weights": weights,
            "thresholds": thresholds,
            "feature_names": feature_names
        }
        
        # Convert to JSON and save
        with open(output_path, 'w') as f:
            json.dump(output, f, indent=2)
            
        print(f"Successfully exported model weights to {output_path}")
        print(f"Weights: {weights}")
        print(f"Thresholds: {thresholds}")
        
        return True
        
    except Exception as e:
        print(f"Error exporting model weights: {str(e)}")
        return False

if __name__ == "__main__":
    # Default paths
    default_model_path = "../model/respiratory_classifier.joblib"
    default_output_path = "../app/src/main/assets/python_model_weights.json"
    
    # Allow command line arguments to override defaults
    model_path = sys.argv[1] if len(sys.argv) > 1 else default_model_path
    output_path = sys.argv[2] if len(sys.argv) > 2 else default_output_path
    
    # Ensure the output directory exists
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    
    # Set custom thresholds if needed
    thresholds = {
        "BRADYPNEA_THRESHOLD": 10.0,
        "TACHYPNEA_THRESHOLD": 24.0,
        "IRREGULARITY_THRESHOLD": 0.4,
        "AMPLITUDE_VARIATION_THRESHOLD": 40.0,
        "VELOCITY_THRESHOLD": 8.0
    }
    
    # Export the model
    success = export_model_weights(model_path, output_path, thresholds)
    
    if success:
        print("✅ Model weights exported successfully!")
        print(f"  Model source: {model_path}")
        print(f"  Exported to: {output_path}")
        print("Copy this file to the app's assets folder to use with the Android app.")
    else:
        print("❌ Failed to export model weights.")
        sys.exit(1) 