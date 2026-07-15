import os
import torch
from torch.utils.data import Dataset, DataLoader
from PIL import Image

try:
    from transformers import PaliGemmaForConditionalGeneration, PaliGemmaProcessor
    from peft import LoraConfig, get_peft_model
except ImportError:
    print("Warning: HuggingFace transformers/peft/accelerate is not installed. To run this script, install them:")
    print("   pip install transformers peft accelerate datasets torch torchvision")

class PaliGemmaSurveillanceDataset(Dataset):
    """
    Custom Dataset to map extracted video frames and bounding boxes
    to the PaliGemma prompt structure.
    """
    def __init__(self, image_dir, labels_dir, processor, max_length=512):
        self.image_dir = image_dir
        self.labels_dir = labels_dir
        self.processor = processor
        self.max_length = max_length
        self.filenames = [f.split('.')[0] for f in os.listdir(image_dir) if f.endswith('.jpg')]

    def __len__(self):
        return len(self.filenames)

    def __getitem__(self, idx):
        name = self.filenames[idx]
        image_path = os.path.join(self.image_dir, f"{name}.jpg")
        label_path = os.path.join(self.labels_dir, f"{name}.txt")

        image = Image.open(image_path).convert("RGB")
        
        # Grounding prompt structure
        prompt = "detect person ; bag ; backpack ; vehicle ; weapon\n"
        
        # Read YOLO annotations and convert back to PaliGemma <loc> format
        target_text = ""
        if os.path.exists(label_path):
            with open(label_path, "r") as f:
                for line in f:
                    parts = line.strip().split()
                    if len(parts) >= 5:
                        cls_id, x_c, y_c, w, h = map(float, parts)
                        # Re-calculate absolute normalized corners [0, 1000]
                        ymin = int((y_c - h / 2.0) * 1000)
                        xmin = int((x_c - w / 2.0) * 1000)
                        ymax = int((y_c + h / 2.0) * 1000)
                        xmax = int((x_c + w / 2.0) * 1000)
                        
                        # Clip boundaries
                        ymin, xmin, ymax, xmax = [max(0, min(1000, val)) for val in [ymin, xmin, ymax, xmax]]
                        
                        label_name = ["person", "backpack", "vehicle"][int(cls_id)] if int(cls_id) < 3 else "object"
                        target_text += f"<loc{ymin:04d}><loc{xmin:04d}><loc{ymax:04d}><loc{xmax:04d}> {label_name} "
        
        # Package prompt and image
        inputs = self.processor(
            text=prompt, 
            images=image, 
            suffix=target_text.strip(),
            return_tensors="pt", 
            padding="max_length", 
            max_length=self.max_length
        )
        
        # Remove batch dimension from processor output
        return {k: v.squeeze(0) for k, v in inputs.items()}

def run_paligemma_finetune(dataset_dir="dataset", output_dir="paligemma_tuned_weights"):
    print("Preparing PaliGemma 3B training pipeline...")
    
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Using execution device: {device}")
    if device.type == "cpu":
        print("WARNING: Training on CPU is extremely slow. A CUDA GPU is highly recommended.")

    model_id = "google/paligemma-3b-pt-224"
    
    try:
        print(f"Loading PaliGemma processor and model: {model_id}")
        processor = PaliGemmaProcessor.from_pretrained(model_id)
        
        # Load model (use 8-bit quantization if on GPU to save memory)
        model = PaliGemmaForConditionalGeneration.from_pretrained(
            model_id,
            device_map="auto" if device.type == "cuda" else None,
            torch_dtype=torch.bfloat16 if torch.cuda.is_available() else torch.float32,
            load_in_8bit=torch.cuda.is_available()
        )

        # Apply QLoRA config to reduce memory consumption
        lora_config = LoraConfig(
            r=8,
            lora_alpha=32,
            target_modules=["q_proj", "o_proj", "k_proj", "v_proj", "gate_proj", "up_proj", "down_proj"],
            lora_dropout=0.05,
            bias="none",
            task_type="CAUSAL_LM"
        )
        model = get_peft_model(model, lora_config)
        model.print_trainable_parameters()

        # Load datasets
        train_dataset = PaliGemmaSurveillanceDataset(
            image_dir=os.path.join(dataset_dir, "train", "images"),
            labels_dir=os.path.join(dataset_dir, "train", "labels"),
            processor=processor
        )
        
        train_loader = DataLoader(train_dataset, batch_size=2, shuffle=True)

        # Training optimizer
        optimizer = torch.optim.AdamW(model.parameters(), lr=2e-5)
        
        model.train()
        print("Starting PaliGemma fine-tuning epoch...")
        
        for epoch in range(1): # Run 1 epoch for demo
            for step, batch in enumerate(train_loader):
                # Move tensors to device
                batch = {k: v.to(device) for k, v in batch.items()}
                
                optimizer.zero_grad()
                outputs = model(**batch)
                loss = outputs.loss
                loss.backward()
                optimizer.step()
                
                if step % 5 == 0:
                    print(f"Epoch {epoch} | Step {step} | Loss: {loss.item():.4f}")

        # Save fine-tuned LoRA adapters
        model.save_pretrained(output_dir)
        print(f"LoRA weights successfully saved to '{output_dir}'!")
        
    except Exception as e:
        print(f"Error during PaliGemma fine-tuning configuration: {e}")
        print("Please check your PyTorch and Hugging Face environment variables.")

if __name__ == "__main__":
    # Test execution
    if os.path.exists("dataset"):
        run_paligemma_finetune("dataset", "paligemma_tuned_weights")
    else:
        print("Please run download_dataset.py first to establish dataset images.")
