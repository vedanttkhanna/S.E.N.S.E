import os
import sys

# MediaPipe converter command CLI:
# python -m mediapipe.tasks.genai.converter.converter_main \
#    --model_type PALIGEMMA \
#    --input_ckpt_dir <pytorch_checkpoint_dir> \
#    --output_tflite_file paligemma_3b_mix_224_int4.task \
#    --backend gpu \
#    --quantization_key int4

def export_paligemma_to_task(ckpt_dir="paligemma_tuned_weights", output_file="paligemma_3b_mix_224_int4.task"):
    print("Preparing PaliGemma 3B conversion to MediaPipe .task bundle...")
    print(f"Input Checkpoint Directory: {ckpt_dir}")
    print(f"Output Target File: {output_file}")
    
    try:
        import mediapipe as mp
        from mediapipe.tasks.python.genai import converter
        
        # Configure model parameters
        config = converter.ConversionConfig(
            model_type="PALIGEMMA",
            checkpoint_dir=ckpt_dir,
            output_tflite_file=output_file,
            quantization_key="int4",
            backend="gpu"
        )
        
        print("Running MediaPipe GenAI converter. This compiles PyTorch weights to GPU LiteRT...")
        converter.convert_vlm(config)
        print(f"Successfully converted and exported: {output_file}")
        
        # Copy to assets folder if path is present
        target_path = "../app/src/main/assets/models/" + output_file
        if os.path.exists("../app/src/main/assets/models/"):
            import shutil
            shutil.copy(output_file, target_path)
            print(f"Staged {output_file} directly to Android assets: {target_path}")
            
    except ImportError:
        print("\nWarning: mediapipe is not installed or the converter extension is missing.")
        print("To run the export pipeline, please install MediaPipe with genai packages:")
        print("   pip install mediapipe")
        print("\nAlternatively, you can run the following CLI conversion command:")
        print(f"   python -m mediapipe.tasks.genai.converter.converter_main \\")
        print(f"       --model_type PALIGEMMA \\")
        print(f"       --input_ckpt_dir {ckpt_dir} \\")
        print(f"       --output_tflite_file {output_file} \\")
        print(f"       --backend gpu \\")
        print(f"       --quantization_key int4")
    except Exception as e:
        print(f"Conversion failed: {e}")

if __name__ == "__main__":
    export_paligemma_to_task("paligemma_tuned_weights", "paligemma_3b_mix_224_int4.task")
