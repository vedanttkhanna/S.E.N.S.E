import os
import urllib.request
import zipfile
import shutil
import numpy as np
from PIL import Image, ImageDraw

DATASET_URL = "https://storage.googleapis.com/download.tensorflow.org/example_images/flower_photos.tgz" # Placeholder public dataset or a direct security video frame dataset link

def create_synthetic_dataset(output_dir="dataset", num_images=20):
    print("Generating synthetic video frame dataset for offline test/demo...")
    os.makedirs(os.path.join(output_dir, "train", "images"), exist_ok=True)
    os.makedirs(os.path.join(output_dir, "train", "labels"), exist_ok=True)
    os.makedirs(os.path.join(output_dir, "val", "images"), exist_ok=True)
    os.makedirs(os.path.join(output_dir, "val", "labels"), exist_ok=True)

    # Class mappings: 0=person, 1=backpack, 2=vehicle
    classes = ["person", "backpack", "vehicle"]

    for i in range(num_images):
        # Create an image
        img = Image.new("RGB", (640, 480), color=(np.random.randint(50, 150), np.random.randint(50, 150), np.random.randint(50, 150)))
        draw = ImageDraw.Draw(img)

        # Draw a mock object (rectangle)
        box_type = np.random.randint(0, 3)
        x1, y1 = np.random.randint(50, 200), np.random.randint(50, 200)
        x2, y2 = np.random.randint(300, 500), np.random.randint(300, 450)
        draw.rectangle([x1, y1, x2, y2], fill=(200, 100, 100))
        draw.text((x1 + 10, y1 + 10), classes[box_type], fill=(255, 255, 255))

        # Convert to YOLO format (class_id, x_center, y_center, width, height) normalized [0, 1]
        dw = 1.0 / 640
        dh = 1.0 / 480
        x_center = (x1 + x2) / 2.0 * dw
        y_center = (y1 + y2) / 2.0 * dh
        w = (x2 - x1) * dw
        h = (y2 - y1) * dh

        split = "train" if i < int(num_images * 0.8) else "val"
        
        # Save image
        img.save(os.path.join(output_dir, split, "images", f"frame_{i:04d}.jpg"))
        
        # Save YOLO annotation label
        with open(os.path.join(output_dir, split, "labels", f"frame_{i:04d}.txt"), "w") as f:
            f.write(f"{box_type} {x_center:.6f} {y_center:.6f} {w:.6f} {h:.6f}\n")

    # Save classes.txt
    with open(os.path.join(output_dir, "classes.txt"), "w") as f:
        for c in classes:
            f.write(f"{c}\n")

    print(f"Synthetic dataset created successfully inside '{output_dir}/'!")

def download_and_extract(url, output_dir="dataset"):
    print(f"Attempting to download surveillance dataset from {url}...")
    try:
        os.makedirs(output_dir, exist_ok=True)
        zip_path = os.path.join(output_dir, "dataset.zip")
        
        # Setup user-agent header
        req = urllib.request.Request(
            url, 
            headers={'User-Agent': 'Mozilla/5.0'}
        )
        with urllib.request.urlopen(req) as response, open(zip_path, 'wb') as out_file:
            shutil.copyfileobj(response, out_file)
            
        print("Download complete. Extracting dataset files...")
        with zipfile.ZipFile(zip_path, 'r') as zip_ref:
            zip_ref.extractall(output_dir)
            
        os.remove(zip_path)
        print("Extraction complete.")
    except Exception as e:
        print(f"Could not download remote dataset: {e}")
        create_synthetic_dataset(output_dir)

if __name__ == "__main__":
    # For demo purposes, we will default to generating the synthetic dataset first
    create_synthetic_dataset("dataset")
