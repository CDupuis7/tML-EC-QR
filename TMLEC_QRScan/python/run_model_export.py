#!/usr/bin/env python
# Runner script to create a sample model and export its weights to JSON

import os
import subprocess
import sys

def run_script(script_path):
    """Run a Python script and return its success status."""
    print(f"\n{'='*50}")
    print(f"Running: {script_path}")
    print(f"{'='*50}\n")
    
    try:
        result = subprocess.run([sys.executable, script_path], check=True)
        return result.returncode == 0
    except subprocess.CalledProcessError as e:
        print(f"Error running {script_path}: {e}")
        return False

def main():
    # Get directory of this script
    current_dir = os.path.dirname(os.path.abspath(__file__))
    
    # Define paths to scripts
    create_model_script = os.path.join(current_dir, "create_sample_model.py")
    export_weights_script = os.path.join(current_dir, "export_model_weights.py")
    
    # Step 1: Create the sample model
    print("Step 1: Creating sample model...")
    if not run_script(create_model_script):
        print("❌ Failed to create sample model. Aborting.")
        return False
    
    # Step 2: Export model weights to JSON
    print("\nStep 2: Exporting model weights to JSON...")
    if not run_script(export_weights_script):
        print("❌ Failed to export model weights. Aborting.")
        return False
    
    # Success!
    print("\n✅ Model creation and export completed successfully!")
    print("The JSON file with model weights should now be available at:")
    print("  ../app/src/main/assets/python_model_weights.json")
    print("\nYou can now use this file in your Kotlin application.")
    
    return True

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1) 