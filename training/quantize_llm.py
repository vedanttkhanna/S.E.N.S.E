import os
import sys

# MediaPipe LLM GenAI converter command CLI:
# python -m mediapipe.tasks.genai.converter.converter_main \
#    --model_type GEMMA_2B \
#    --input_ckpt_dir <gemma3_pytorch_dir> \
#    --output_tflite_file gemma3_1b_int4.task \
#    --backend gpu \
#    --quantization_key int4

def quantize_llm(model_type, ckpt_dir, output_file):
    print(f"Preparing LLM ({model_type}) conversion and quantization (INT4) to MediaPipe .task bundle...")
    print(f"Input Checkpoint Directory: {ckpt_dir}")
    print(f"Output Target File: {output_file}")
    
    try:
        import mediapipe as mp
        from mediapipe.tasks.python.genai import converter
        
        # Configure conversion options
        config = converter.ConversionConfig(
            model_type=model_type,
            checkpoint_dir=ckpt_dir,
            output_tflite_file=output_file,
            quantization_key="int4",
            backend="gpu"
        )
        
        print("Running MediaPipe GenAI converter. This will compile, quantize to INT4, and bundle the model...")
        converter.convert_vlm(config) if "PALIGEMMA" in model_type else converter.convert_llm(config)
        print(f"Successfully converted and exported: {output_file}")
        
        # Staging the output file to S.E.N.S.E. assets
        target_path = "../app/src/main/assets/models/" + output_file
        if os.path.exists("../app/src/main/assets/models/"):
            import shutil
            shutil.copy(output_file, target_path)
            print(f"Staged {output_file} directly to Android assets: {target_path}")
            
    except ImportError:
        print("\nWarning: mediapipe is not installed or the converter extension is missing.")
        print("To run the quantization pipeline, please install MediaPipe:")
        print("   pip install mediapipe")
        print("\nAlternatively, you can run the following CLI conversion command:")
        print(f"   python -m mediapipe.tasks.genai.converter.converter_main \\")
        print(f"       --model_type {model_type} \\")
        print(f"       --input_ckpt_dir {ckpt_dir} \\")
        print(f"       --output_tflite_file {output_file} \\")
        print(f"       --backend gpu \\")
        print(f"       --quantization_key int4")
    except Exception as e:
        print(f"Quantization failed: {e}")

if __name__ == "__main__":
    # Example 1: Gemma 3 1B
    quantize_llm(
        model_type="GEMMA_2B", # GEMMA_2B handles Gemma/Gemma2 2B or Gemma 3 1B architecture
        ckpt_dir="gemma_tuned_weights",
        output_file="gemma3_1b_int4.task"
    )
    
    # Example 2: ShieldGemma 2B
    # quantize_llm(
    #     model_type="GEMMA_2B",
    #     ckpt_dir="shieldgemma_pytorch_weights",
    #     output_file="shieldgemma_2b_int4.task"
    # )
