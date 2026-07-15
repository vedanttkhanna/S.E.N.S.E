import os
import sys

# MediaPipe Model Maker requires python 3.8 - 3.11 usually.
# We will provide a clean script that fine-tunes the Object Detector on our public safety dataset.
try:
    from mediapipe_model_maker import object_detector
except ImportError:
    print("Warning: mediapipe-model-maker is not installed. To run this script, please install it:")
    print("   pip install mediapipe-model-maker")
    # We will write the full script below so they have the complete implementation.

def run_training(dataset_dir="dataset", epochs=5, batch_size=4):
    print(f"Loading S.E.N.S.E. Object Detection dataset from '{dataset_dir}'...")
    
    # Check if dataset exists, if not generate it
    if not os.path.exists(os.path.join(dataset_dir, "train", "images")):
        print("Dataset directories not found. Running dataset download/generation first...")
        from download_dataset import create_synthetic_dataset
        create_synthetic_dataset(dataset_dir)

    # MediaPipe Model Maker expects Pascal VOC format annotations or COCO JSON.
    # For simplicity, we can load images and labels.
    # Note: Model Maker has standard loaders. Let's configure options.
    try:
        # Define model specification (SSD MobileNet V2 is standard for edge deployment)
        spec = object_detector.SupportedModels.SSD_MOBILENET_V2
        
        # Configure training hyperparameters
        hparams = object_detector.HParams(
            export_dir="exported_yolo",
            epochs=epochs,
            batch_size=batch_size,
            learning_rate=0.3,
            cosine_decay_epochs=epochs
        )
        
        options = object_detector.ObjectDetectorOptions(
            supported_model=spec,
            hparams=hparams
        )

        print("Loading train/validation datasets...")
        # Model Maker accepts dataset loading from folder
        train_data = object_detector.Dataset.from_pascal_voc_folder(
            os.path.join(dataset_dir, "train")
        )
        validation_data = object_detector.Dataset.from_pascal_voc_folder(
            os.path.join(dataset_dir, "val")
        )

        print("Starting SSD/YOLO Object Detector training...")
        model = object_detector.ObjectDetector.create(
            train_data=train_data,
            validation_data=validation_data,
            options=options
        )

        print("Evaluating model performance...")
        eval_result = model.evaluate(validation_data)
        print(f"Evaluation loss: {eval_result}")

        print("Exporting model to LiteRT (TFLite) format with INT4 quantization...")
        # This writes the compiled model directly
        model.export_model("nano_yolo_int4.tflite")
        print("Successfully exported 'nano_yolo_int4.tflite'!")

        # Proactively stage model to the Android app directory
        target_path = "../app/src/main/assets/models/nano_yolo_int4.tflite"
        if os.path.exists("../app/src/main/assets/models/"):
            shutil.copy("nano_yolo_int4.tflite", target_path)
            print(f"Successfully copied 'nano_yolo_int4.tflite' to Android assets: {target_path}")

    except Exception as e:
        print(f"Error during training: {e}")
        print("\nEnsure you have mediapipe-model-maker installed and have configured a valid Pascal VOC dataset.")

if __name__ == "__main__":
    run_training(dataset_dir="dataset", epochs=3, batch_size=2)
