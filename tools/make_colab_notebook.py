"""
Step 7.6: build the Google Colab notebook that trains our own plant-disease YOLOv8.

  python tools/make_colab_notebook.py   ->   notebooks/train_plant_yolov8.ipynb

The converter cell is copied from tools/plantdoc_to_yolo.py at build time, so the notebook and
this PC always convert the dataset the same way.

On Colab: File > Upload notebook > pick the .ipynb; Runtime > Change runtime type > T4 GPU;
Runtime > Run all. At the end it downloads plant_yolov8_results.zip (best.pt + metrics).
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "notebooks", "train_plant_yolov8.ipynb")


def md(text):
    return {"cell_type": "markdown", "metadata": {}, "source": text.strip("\n").splitlines(keepends=True)}


def code(text):
    return {"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [],
            "source": text.strip("\n").splitlines(keepends=True)}


def main():
    converter = open(os.path.join(ROOT, "tools", "plantdoc_to_yolo.py"), encoding="utf-8").read()
    cells = [
        md("""
# Train a plant-disease YOLOv8 (AR glasses project, Step 7.6)

Trains YOLOv8 on **PlantDoc** (29 classes: corn, tomato, potato, apple, grape... leaves and diseases)
so the AR backend can run it on its fast YOLOv8 C++ path (~150-400 ms instead of ~2 s).

**Before running:** `Runtime > Change runtime type > T4 GPU`, then `Runtime > Run all`.
Takes about 45-60 min. Keep this tab open. At the end, `plant_yolov8_results.zip` is downloaded:
put `best.pt` from it into the project's `models/` folder.

Training uses PlantDoc **TRAIN** only; PlantDoc **TEST** is the validation set.
"""),
        code("""
# 1. Check that a GPU is attached (should show a Tesla T4)
!nvidia-smi
"""),
        code("""
# 2. Install Ultralytics (YOLOv8)
!pip -q install ultralytics
import ultralytics; ultralytics.checks()
"""),
        code("""
# 3. Download PlantDoc (~1 GB)
!git clone -q --depth 1 https://github.com/pratikkayal/PlantDoc-Object-Detection-Dataset plantdoc
!ls plantdoc
"""),
        code("%%writefile plantdoc_to_yolo.py\n" + converter),
        code("""
# 4. Convert to YOLO format (safe file names, images shrunk to <= 1280 px)
!python plantdoc_to_yolo.py --src plantdoc --out plantdoc-yolo
"""),
        md("""
## Training settings
`MODEL`: `yolov8s.pt` (more accurate, ~2-3x slower on the laptop) or `yolov8n.pt` (fastest).
`yolov8s` is the default: a "look and hold" check can afford ~300-400 ms, accuracy matters more.
"""),
        code("""
# 5. Train
from ultralytics import YOLO
MODEL = "yolov8s.pt"
EPOCHS = 100
model = YOLO(MODEL)
results = model.train(data="plantdoc-yolo/data.yaml", epochs=EPOCHS, imgsz=640, batch=16, patience=30,
                      project="/content/runs", name="plant-yolov8", exist_ok=True, plots=True, seed=0)
print("best weights:", model.trainer.best)
"""),
        code("""
# 6. Validate the best weights on PlantDoc TEST (per-class numbers)
best = YOLO(model.trainer.best)
m = best.val(data="plantdoc-yolo/data.yaml", imgsz=640, split="val", plots=True)
print(f"mAP50 = {m.box.map50:.3f}   mAP50-95 = {m.box.map:.3f}")
for i, name in best.names.items():
    if i < len(m.box.ap50):
        print(f"  {name:38} AP50 {m.box.ap50[i]:.3f}")
"""),
        code("""
# 7. Download the results (best.pt + training curves + confusion matrix)
import os, shutil, glob
from google.colab import files
out = "/content/plant_yolov8_results"
os.makedirs(out, exist_ok=True)
shutil.copy(model.trainer.best, os.path.join(out, "best.pt"))
for f in glob.glob("/content/runs/plant-yolov8/*.png") + glob.glob("/content/runs/plant-yolov8/*.csv"):
    shutil.copy(f, out)
shutil.make_archive("/content/plant_yolov8_results", "zip", out)
files.download("/content/plant_yolov8_results.zip")
"""),
    ]
    nb = {"cells": cells, "metadata": {"accelerator": "GPU", "colab": {"provenance": []},
                                       "kernelspec": {"name": "python3", "display_name": "Python 3"},
                                       "language_info": {"name": "python"}},
          "nbformat": 4, "nbformat_minor": 0}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(nb, open(OUT, "w", encoding="utf-8"), indent=1)
    print("wrote", OUT, f"({len(cells)} cells)")


if __name__ == "__main__":
    main()
