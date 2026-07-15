import os
import torch
from torch.utils.data import Dataset, DataLoader

try:
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig, get_peft_model
    from trl import SFTTrainer
except ImportError:
    print("Warning: HuggingFace transformers/peft/trl is not installed. To run this script, install them:")
    print("   pip install transformers peft trl torch accelerate")

# Synthetic dataset of S.E.N.S.E. neuro-symbolic reasoning examples
MOCK_TRAINING_DATA = [
    {
        "prompt": "## SYSTEM RULES\nloitering_alert: flag threat if person stays near vehicle > 30s\n## WORLD STATE\n{\"person_detected\": false, \"duration_sec\": 0}\n## OBSERVATIONS\n[person at <loc0100><loc0100>]",
        "response": '{\n  "threat_level": "AMBIGUOUS",\n  "rationale": "Person observed near vehicle, starting loitering timer. Need more duration context.",\n  "triggered_rules": [],\n  "world_state": { "person_detected": true, "duration_sec": 1 }\n}'
    },
    {
        "prompt": "## SYSTEM RULES\nloitering_alert: flag threat if person stays near vehicle > 30s\n## WORLD STATE\n{\"person_detected\": true, \"duration_sec\": 35}\n## OBSERVATIONS\n[person at <loc0100><loc0100>]\n[loud sound: scream]",
        "response": '{\n  "threat_level": "VERIFIED_THREAT",\n  "rationale": "Person has loitered near vehicle for over 35 seconds combined with a scream. Triggering forensic lock.",\n  "triggered_rules": ["loitering_alert"],\n  "world_state": { "person_detected": true, "duration_sec": 36, "alert_fired": true }\n}'
    }
]

class GemmaInstructionDataset(Dataset):
    def __init__(self, data, tokenizer, max_length=512):
        self.data = data
        self.tokenizer = tokenizer
        self.max_length = max_length

    def __len__(self):
        return len(self.data)

    def __getitem__(self, idx):
        item = self.data[idx]
        full_text = f"<start_of_turn>user\n{item['prompt']}<end_of_turn>\n<start_of_turn>model\n{item['response']}<end_of_turn>"
        
        inputs = self.tokenizer(
            full_text,
            max_length=self.max_length,
            padding="max_length",
            truncation=True,
            return_tensors="pt"
        )
        
        labels = inputs["input_ids"].clone()
        # Mask the prompt tokens to ignore them in loss calculation
        prompt_len = len(self.tokenizer.encode(f"<start_of_turn>user\n{item['prompt']}<end_of_turn>\n<start_of_turn>model\n"))
        labels[0, :prompt_len] = -100
        
        return {
            "input_ids": inputs["input_ids"].squeeze(0),
            "attention_mask": inputs["attention_mask"].squeeze(0),
            "labels": labels.squeeze(0)
        }

def run_gemma_finetune(output_dir="gemma_tuned_weights"):
    print("Preparing Gemma 3 1B fine-tuning pipeline...")
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Using execution device: {device}")

    model_id = "google/gemma-3-1b-it"

    try:
        print(f"Loading tokenizer and model: {model_id}")
        tokenizer = AutoTokenizer.from_pretrained(model_id)
        if tokenizer.pad_token is None:
            tokenizer.pad_token = tokenizer.eos_token

        model = AutoModelForCausalLM.from_pretrained(
            model_id,
            device_map="auto" if device.type == "cuda" else None,
            torch_dtype=torch.bfloat16 if torch.cuda.is_available() else torch.float32,
            load_in_8bit=torch.cuda.is_available()
        )

        lora_config = LoraConfig(
            r=8,
            lora_alpha=16,
            target_modules=["q_proj", "v_proj", "k_proj", "o_proj"],
            lora_dropout=0.05,
            bias="none",
            task_type="CAUSAL_LM"
        )
        model = get_peft_model(model, lora_config)
        model.print_trainable_parameters()

        dataset = GemmaInstructionDataset(MOCK_TRAINING_DATA, tokenizer)
        loader = DataLoader(dataset, batch_size=1, shuffle=True)

        optimizer = torch.optim.AdamW(model.parameters(), lr=1e-5)
        
        model.train()
        print("Starting S.E.N.S.E. ruleset reasoning fine-tuning...")
        
        for epoch in range(1):
            for step, batch in enumerate(loader):
                batch = {k: v.to(device) for k, v in batch.items()}
                
                optimizer.zero_grad()
                outputs = model(**batch)
                loss = outputs.loss
                loss.backward()
                optimizer.step()
                
                print(f"Step {step} | Loss: {loss.item():.4f}")

        model.save_pretrained(output_dir)
        print(f"Gemma 3 LoRA adapter saved to '{output_dir}'!")

    except Exception as e:
        print(f"Error during Gemma 3 fine-tuning: {e}")
        print("Please check your PyTorch and Hugging Face tokenizer setup.")

if __name__ == "__main__":
    run_gemma_finetune("gemma_tuned_weights")
