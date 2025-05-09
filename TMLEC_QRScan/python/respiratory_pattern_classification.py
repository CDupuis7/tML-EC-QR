
"""
Respiratory Pattern Classification (Normal vs Abnormal)

This script uses the BIDMC dataset to train a model that can classify 
respiratory patterns as normal or abnormal based on metrics collected 
from the QR code-based tracking system.

The script:
1. Processes the BIDMC dataset to extract respiratory pattern features
2. Builds a classification model to detect normal/abnormal breathing
3. Evaluates the model on the collected QR code respiratory data
4. Visualizes the results and provides breathing pattern analysis
"""

import os
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
from sklearn.model_selection import train_test_split, GridSearchCV
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import confusion_matrix, classification_report, accuracy_score
import joblib
import json
from glob import glob

def load_bidmc_data(base_path="bidmc-ppg-and-respiration-dataset-1.0.0/bidmc_csv"):
    """Load respiratory data from BIDMC dataset and extract pattern features."""
    all_subjects = []
    subject_ids = range(1, 54)  # BIDMC has 53 subjects
    
    for subject_id in subject_ids:
        try:
            # Load subject info
            with open(f"{base_path}/bidmc_{subject_id:02d}_Fix.txt", 'r') as f:
                info = f.readlines()
                age_line = info[5].split(': ')[1].strip()
                try:
                    age = int(age_line)
                except ValueError:
                    # Handle special age values like '90+' or 'NaN'
                    age = 90 if '90+' in age_line else 50  # Default to 50 for unknown ages
                    
                gender = info[6].split(': ')[1].strip()
                location = info[7].split(': ')[1].strip()
            
            # Load respiratory signal
            signals_df = pd.read_csv(f"{base_path}/bidmc_{subject_id:02d}_Signals.csv", skipinitialspace=True)
            resp_signal = signals_df['RESP'].values
            
            # Load numerics (includes clinical respiratory rate)
            numerics_df = pd.read_csv(f"{base_path}/bidmc_{subject_id:02d}_Numerics.csv", skipinitialspace=True)
            resp_rate = numerics_df['RESP'].mean()  # Clinical respiratory rate
            
            # Load breath annotations
            breaths_df = pd.read_csv(f"{base_path}/bidmc_{subject_id:02d}_Breaths.csv", 
                                   names=['breath_start', 'breath_end'], 
                                   skiprows=1,
                                   skipinitialspace=True)
            
            # Calculate respiratory metrics
            breath_durations = []
            breath_amplitudes = []
            breath_velocities = []
            
            for start, end in zip(breaths_df['breath_start'], breaths_df['breath_end']):
                if isinstance(start, (int, np.integer)) and isinstance(end, (int, np.integer)) and start < end:
                    breath_segment = resp_signal[start:end]
                    if len(breath_segment) > 0:
                        # Duration (in seconds, sampling rate is 125 Hz)
                        duration = (end - start) / 125
                        breath_durations.append(duration)
                        
                        # Amplitude
                        amplitude = np.max(breath_segment) - np.min(breath_segment)
                        breath_amplitudes.append(amplitude)
                        
                        # Velocity (rate of change)
                        if len(breath_segment) > 1:
                            velocity = np.max(np.abs(np.diff(breath_segment)))
                            breath_velocities.append(velocity)
            
            if breath_durations and breath_amplitudes and breath_velocities:
                # Calculate overall metrics
                total_duration = sum(breath_durations)
                breathing_rate = 60 / np.mean(breath_durations)  # breaths per minute
                avg_amplitude = np.mean(breath_amplitudes)
                max_amplitude = np.max(breath_amplitudes)
                min_amplitude = np.min(breath_amplitudes)
                avg_velocity = np.mean(breath_velocities)
                
                # Calculate variability metrics
                amplitude_variability = np.std(breath_amplitudes) / avg_amplitude if avg_amplitude > 0 else 0
                duration_variability = np.std(breath_durations) / np.mean(breath_durations) if np.mean(breath_durations) > 0 else 0
                
                # Determine if breathing is normal or abnormal
                # Based on clinical guidelines:
                # 1. Normal adult breathing rate is 12-20 breaths per minute
                # 2. High variability in amplitude or duration indicates abnormal patterns
                # 3. Location 'micu' (medical ICU) indicates potentially abnormal patients
                # Adjust criteria to ensure more balanced classes
                is_abnormal = (
                    (breathing_rate < 10) or  # Slightly more strict lower bound
                    (breathing_rate > 24) or  # Slightly more relaxed upper bound
                    (amplitude_variability > 0.4) or  # More strict threshold for abnormal variability
                    (duration_variability > 0.4)  # More strict threshold for abnormal variability
                    # Removed location-based classification to get more normal examples
                )
                
                subject_data = {
                    'subject_id': subject_id,
                    'age': age,
                    'gender': 1 if gender == 'M' else 0,
                    'breathing_rate': breathing_rate,
                    'avg_amplitude': avg_amplitude,
                    'max_amplitude': max_amplitude,
                    'min_amplitude': min_amplitude,
                    'avg_velocity': avg_velocity,
                    'amplitude_variability': amplitude_variability,
                    'duration_variability': duration_variability,
                    'abnormal': 1 if is_abnormal else 0
                }
                
                all_subjects.append(subject_data)
                print(f"Processed subject {subject_id}: {'Abnormal' if is_abnormal else 'Normal'} breathing pattern")
                
        except Exception as e:
            print(f"Error processing subject {subject_id}: {str(e)}")
    
    # Check class balance
    if all_subjects:
        abnormal_count = sum(s['abnormal'] for s in all_subjects)
        total = len(all_subjects)
        print(f"\nClass distribution: {abnormal_count}/{total} ({abnormal_count/total*100:.1f}%) abnormal patterns")
        
    return pd.DataFrame(all_subjects)

def train_abnormal_breathing_model(bidmc_data, output_dir='model_output'):
    """Train a model to classify normal vs abnormal breathing patterns."""
    # Prepare features and target
    features = ['age', 'gender', 'breathing_rate', 'avg_amplitude', 'max_amplitude', 
                'min_amplitude', 'avg_velocity', 'amplitude_variability', 'duration_variability']
    
    X = bidmc_data[features]
    y = bidmc_data['abnormal']
    
    # Split the data
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.3, random_state=42)
    
    # Scale the features
    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_test_scaled = scaler.transform(X_test)
    
    # Train a RandomForest classifier with hyperparameter tuning
    param_grid = {
        'n_estimators': [50, 100, 200],
        'max_depth': [None, 10, 20],
        'min_samples_split': [2, 5, 10]
    }
    
    grid_search = GridSearchCV(
        RandomForestClassifier(random_state=42),
        param_grid,
        cv=5,
        scoring='f1',
        n_jobs=-1
    )
    
    grid_search.fit(X_train_scaled, y_train)
    best_model = grid_search.best_estimator_
    
    # Evaluate the model
    train_accuracy = best_model.score(X_train_scaled, y_train)
    test_accuracy = best_model.score(X_test_scaled, y_test)
    print(f"Best parameters: {grid_search.best_params_}")
    print(f"Training accuracy: {train_accuracy:.4f}")
    print(f"Testing accuracy: {test_accuracy:.4f}")
    
    # Feature importance
    feature_importance = pd.DataFrame({
        'feature': features,
        'importance': best_model.feature_importances_
    }).sort_values('importance', ascending=False)
    
    print("\nFeature Importance:")
    print(feature_importance)
    
    # Save the model
    os.makedirs(output_dir, exist_ok=True)
    joblib.dump(best_model, f"{output_dir}/breathing_pattern_model.joblib")
    joblib.dump(scaler, f"{output_dir}/pattern_scaler.joblib")
    
    # Save feature names
    with open(f"{output_dir}/pattern_features.json", 'w') as f:
        json.dump(features, f)
    
    return best_model, scaler, features

def load_qr_respiratory_data(data_dir='respiratory_data'):
    """Load respiratory data collected from the QR code app."""
    all_data = []
    files = glob(f"{data_dir}/respiratory_data_*.csv")
    
    for file_path in files:
        try:
            # Read the file content
            with open(file_path, 'r') as f:
                content = f.read()
            
            # Extract patient info and breathing metrics
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
            
            # Load the actual data
            df = pd.read_csv(file_path, skiprows=header_index)
            
            # Calculate additional metrics
            amplitudes = df['amplitude'].values
            velocities = df['velocity'].abs().values
            
            amplitude_variability = np.std(amplitudes) / avg_amplitude if avg_amplitude > 0 else 0
            
            # Group by breathing phase to calculate duration variability
            phase_groups = df.groupby((df['breathing_phase'] != df['breathing_phase'].shift()).cumsum())
            phase_durations = []
            
            for _, group in phase_groups:
                if len(group) > 1:
                    start_time = group['timestamp'].iloc[0]
                    end_time = group['timestamp'].iloc[-1]
                    phase_durations.append(end_time - start_time)
            
            duration_variability = np.std(phase_durations) / np.mean(phase_durations) if phase_durations and np.mean(phase_durations) > 0 else 0
            
            # Create summary data
            summary_data = {
                'patient_id': patient_id if patient_id else '0',
                'age': age if age else 0,
                'gender': 1 if gender == 'Male' else 0,
                'health_status': health_status if health_status else 'Unknown',
                'breathing_rate': breathing_rate if breathing_rate else 0,
                'avg_amplitude': avg_amplitude if avg_amplitude else 0,
                'max_amplitude': max_amplitude if max_amplitude else 0,
                'min_amplitude': min_amplitude if min_amplitude else 0,
                'avg_velocity': np.mean(velocities),
                'amplitude_variability': amplitude_variability,
                'duration_variability': duration_variability,
                'file_path': os.path.basename(file_path)
            }
            
            all_data.append(summary_data)
            print(f"Processed {file_path}: Breathing rate {breathing_rate:.2f}")
            
        except Exception as e:
            print(f"Error loading {file_path}: {str(e)}")
    
    if all_data:
        return pd.DataFrame(all_data)
    else:
        raise ValueError("No data could be loaded from the respiratory data files")

def analyze_breathing_patterns(model, scaler, features, qr_data):
    """Analyze breathing patterns in the QR code data using the trained model."""
    # Prepare features
    X = qr_data[features]
    
    # Scale the features
    X_scaled = scaler.transform(X)
    
    # Predict abnormal breathing
    qr_data['predicted_abnormal'] = model.predict(X_scaled)
    
    # Try to get probabilities, but handle case where model only has one class
    try:
        probs = model.predict_proba(X_scaled)
        # Check if we have two classes
        if probs.shape[1] > 1:
            qr_data['abnormal_probability'] = probs[:, 1]
        else:
            # If only one class, use a fixed probability based on prediction
            qr_data['abnormal_probability'] = qr_data['predicted_abnormal'].apply(lambda x: 0.9 if x == 1 else 0.1)
    except (IndexError, AttributeError):
        # Handle case where predict_proba fails
        qr_data['abnormal_probability'] = qr_data['predicted_abnormal'].apply(lambda x: 0.9 if x == 1 else 0.1)
    
    # Print results
    print("\nBreathing Pattern Analysis Results:")
    print("-" * 50)
    for _, row in qr_data.iterrows():
        pattern = "ABNORMAL" if row['predicted_abnormal'] == 1 else "NORMAL"
        confidence = row['abnormal_probability'] * 100
        print(f"Patient {row['patient_id']} ({row['file_path']}): {pattern} breathing pattern (Confidence: {confidence:.1f}%)")
        
        # Print key metrics with clinical interpretation
        breathing_rate = row['breathing_rate']
        rate_status = "NORMAL" if 12 <= breathing_rate <= 20 else "ABNORMAL"
        
        print(f"  Breathing Rate: {breathing_rate:.2f} breaths/min ({rate_status})")
        print(f"  Amplitude: {row['avg_amplitude']:.2f} (Variability: {row['amplitude_variability']:.2f})")
        print(f"  Duration Variability: {row['duration_variability']:.2f}")
        
        # Clinical interpretation
        if breathing_rate < 12:
            print(f"  → Bradypnea detected (slow breathing)")
        elif breathing_rate > 20:
            print(f"  → Tachypnea detected (rapid breathing)")
            
        if row['amplitude_variability'] > 0.3:
            print(f"  → High amplitude variability (irregular breathing depth)")
            
        if row['duration_variability'] > 0.3:
            print(f"  → High timing variability (irregular breathing rhythm)")
            
        print("-" * 50)
    
    # Plot summary
    plt.figure(figsize=(10, 6))
    
    # Plot breathing rate vs amplitude colored by predicted pattern
    scatter = plt.scatter(
        qr_data['breathing_rate'], 
        qr_data['avg_amplitude'],
        c=qr_data['abnormal_probability'],
        cmap='coolwarm',
        alpha=0.7,
        s=100
    )
    
    plt.xlabel('Breathing Rate (breaths/min)')
    plt.ylabel('Average Amplitude')
    plt.title('Breathing Pattern Classification')
    cbar = plt.colorbar(scatter)
    cbar.set_label('Abnormal Probability')
    
    # Add annotations for each point
    for i, row in qr_data.iterrows():
        plt.annotate(
            f"Patient {row['patient_id']}",
            (row['breathing_rate'], row['avg_amplitude']),
            xytext=(5, 5),
            textcoords='offset points'
        )
    
    # Add reference lines for normal breathing rate range (12-20 breaths/min)
    plt.axvline(x=12, color='green', linestyle='--', alpha=0.5, label='Normal Range')
    plt.axvline(x=20, color='green', linestyle='--', alpha=0.5)
    
    plt.legend()
    plt.tight_layout()
    plt.savefig('breathing_pattern_analysis.png')
    
    # Add a secondary plot showing the variability
    plt.figure(figsize=(10, 6))
    plt.scatter(
        qr_data['amplitude_variability'],
        qr_data['duration_variability'],
        c=qr_data['predicted_abnormal'],
        cmap='coolwarm',
        alpha=0.7,
        s=100
    )
    
    plt.xlabel('Amplitude Variability')
    plt.ylabel('Duration Variability')
    plt.title('Breathing Variability Analysis')
    
    # Add reference lines for variability thresholds
    plt.axvline(x=0.3, color='orange', linestyle='--', alpha=0.5, label='Variability Threshold')
    plt.axhline(y=0.3, color='orange', linestyle='--', alpha=0.5)
    
    # Add annotations for each point
    for i, row in qr_data.iterrows():
        plt.annotate(
            f"Patient {row['patient_id']}",
            (row['amplitude_variability'], row['duration_variability']),
            xytext=(5, 5),
            textcoords='offset points'
        )
    
    plt.legend()
    plt.tight_layout()
    plt.savefig('breathing_variability_analysis.png')
    
    return qr_data

def main():
    """Main execution function."""
    # Check if the model exists already
    model_path = "model_output/breathing_pattern_model.joblib"
    scaler_path = "model_output/pattern_scaler.joblib"
    features_path = "model_output/pattern_features.json"
    
    if os.path.exists(model_path) and os.path.exists(scaler_path) and os.path.exists(features_path):
        print("Loading existing breathing pattern model...")
        model = joblib.load(model_path)
        scaler = joblib.load(scaler_path)
        with open(features_path, 'r') as f:
            features = json.load(f)
    else:
        print("Training new breathing pattern model using BIDMC dataset...")
        bidmc_data = load_bidmc_data()
        model, scaler, features = train_abnormal_breathing_model(bidmc_data)
    
    # Load the QR code respiratory data
    print("\nLoading QR code respiratory data...")
    qr_data = load_qr_respiratory_data()
    
    # Analyze breathing patterns
    print("\nAnalyzing breathing patterns...")
    results = analyze_breathing_patterns(model, scaler, features, qr_data)
    
    # Save the detailed results
    results.to_csv("breathing_pattern_results.csv", index=False)
    print("\nDone! Results saved to:")
    print("- breathing_pattern_results.csv (detailed metrics)")
    print("- breathing_pattern_analysis.png (breathing rate vs amplitude)")
    print("- breathing_variability_analysis.png (variability analysis)")

if __name__ == "__main__":
    main() 