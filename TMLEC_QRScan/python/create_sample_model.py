#!/usr/bin/env python
# Script to create a sample logistic regression model for testing export functionality

import os
import numpy as np
from sklearn.linear_model import LogisticRegression
import joblib

def create_sample_model(output_path):
    """
    Create a sample logistic regression model for respiratory pattern classification
    and save it to the specified path.
    
    Args:
        output_path: Path where the model should be saved (.joblib format)
    """
    print("Creating sample logistic regression model...")
    
    # Create synthetic training data
    np.random.seed(42)  # For reproducibility
    
    # Feature names: breathing_rate, irregularity_index, amplitude_variation, avg_velocity
    X = np.random.rand(100, 4)
    
    # Normalize features to reasonable ranges
    X[:, 0] = X[:, 0] * 30 + 5  # breathing_rate: 5-35 breaths/min
    X[:, 1] = X[:, 1] * 0.8     # irregularity_index: 0-0.8
    X[:, 2] = X[:, 2] * 80      # amplitude_variation: 0-80
    X[:, 3] = X[:, 3] * 15      # avg_velocity: 0-15
    
    # Create labels based on rules similar to the Kotlin implementation
    y = np.zeros(100, dtype=int)
    
    for i in range(100):
        breathing_rate = X[i, 0]
        irregularity = X[i, 1]
        amplitude_var = X[i, 2]
        velocity = X[i, 3]
        
        # Count abnormal factors
        abnormal_factors = 0
        
        # Bradypnea (too slow)
        if breathing_rate < 10:
            abnormal_factors += 1
            
        # Tachypnea (too fast)
        if breathing_rate > 24:
            abnormal_factors += 1
            
        # High irregularity
        if irregularity > 0.4:
            abnormal_factors += 1
            
        # High amplitude variation
        if amplitude_var > 40:
            abnormal_factors += 1
            
        # High velocity
        if velocity > 8:
            abnormal_factors += 1
            
        # If 2 or more abnormal factors, classify as abnormal
        if abnormal_factors >= 2:
            y[i] = 1
    
    print(f"Generated {len(X)} samples with {sum(y)} abnormal patterns")
    
    # Create and train logistic regression model
    model = LogisticRegression(random_state=42)
    model.fit(X, y)
    
    # Print model coefficients
    print("Model coefficients:")
    feature_names = ["breathing_rate", "irregularity_index", "amplitude_variation", "avg_velocity"]
    for i, name in enumerate(feature_names):
        print(f"  {name}: {model.coef_[0][i]:.6f}")
    print(f"  bias: {model.intercept_[0]:.6f}")
    
    # Ensure output directory exists
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    
    # Save the model
    joblib.dump(model, output_path)
    print(f"Model saved to {output_path}")
    
    return True

if __name__ == "__main__":
    # Define the output path
    model_dir = "../model"
    os.makedirs(model_dir, exist_ok=True)
    output_path = os.path.join(model_dir, "respiratory_classifier.joblib")
    
    # Create the model
    success = create_sample_model(output_path)
    
    if success:
        print("✅ Sample model created successfully!")
        print(f"  Saved to: {output_path}")
        print("Now you can run export_model_weights.py to export it to JSON format.")
    else:
        print("❌ Failed to create sample model.") 