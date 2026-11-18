"""
Step 7.2: export soufiane-easy's plant-disease model (YOLOS-small, PlantDoc) to ONNX.

  tools/.venv/Scripts/python tools/export_plant_onnx.py

Input:  models/plant-disease/   (config.json, preprocessor_config.json, model.safetensors)
Output: models/plant.onnx

The model takes images of DIFFERENT sizes (short side 512, long side <= 864, multiple of 16),
so the export keeps height and width dynamic. It is then checked against PyTorch at every size
of the test images, because an export can silently freeze the size it was traced with.

Everything the C++ detector needs is written into the ONNX metadata, so it reads it from the
file instead of hard-coding it:
  names        {0: 'leaves', 1: 'Apple Scab Leaf', ...}  (same format as Ultralytics exports)
  model_type   yolos
  resize       shortest_edge=512 longest_edge=864 divisor=16
  image_mean / image_std
"""
import glob
import json
import os
import sys

import numpy as np
import onnx
import onnxruntime as ort
import torch
from PIL import Image
from transformers import AutoImageProcessor, AutoModelForObjectDetection

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bicubic_matmul import matmul_bicubic  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "models", "plant-disease")
OUT = os.path.join(ROOT, "models", "plant.onnx")


class Wrapper(torch.nn.Module):
    """Plain tensors in and out: pixel_values -> (logits, pred_boxes)."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, pixel_values):
        out = self.model(pixel_values=pixel_values)
        return out.logits, out.pred_boxes


def main():
    processor = AutoImageProcessor.from_pretrained(SRC)
    model = AutoModelForObjectDetection.from_pretrained(SRC).eval()
    wrapped = Wrapper(model).eval()
    cfg = json.load(open(os.path.join(SRC, "preprocessor_config.json")))

    images = sorted(glob.glob(os.path.join(ROOT, "testdata", "plant", "*.png")))
    inputs = [processor(images=Image.open(f).convert("RGB"), return_tensors="pt")["pixel_values"] for f in images]

    example = inputs[0]
    h = torch.export.Dim("height", min=256, max=1024)
    w = torch.export.Dim("width", min=256, max=1024)
    print(f"exporting with example input {tuple(example.shape)} and dynamic height/width ...")
    # The position-embedding resize as two MatMuls instead of a bicubic "Resize" node, which
    # ONNX Runtime runs extremely slowly (3.6 s of ~5 s measured). Same math: see
    # tools/bicubic_matmul.py. The checks below compare with the ORIGINAL, unpatched model.
    with matmul_bicubic():
        torch.onnx.export(wrapped, (example,), OUT, input_names=["pixel_values"],
                          output_names=["logits", "pred_boxes"],
                          dynamic_shapes={"pixel_values": {2: h, 3: w}}, dynamo=True, opset_version=18,
                          external_data=False)

    m = onnx.load(OUT)
    meta = {
        "names": str({int(k): v for k, v in model.config.id2label.items()}),
        "model_type": "yolos",
        "resize": f"shortest_edge={cfg['size']} longest_edge={cfg['max_size']} divisor=16",
        "image_mean": ",".join(str(x) for x in cfg["image_mean"]),
        "image_std": ",".join(str(x) for x in cfg["image_std"]),
        "source": "soufiane-easy models/plant-disease (nickmuchi/yolos-small-plant-disease-detection)",
    }
    del m.metadata_props[:]
    for k, v in meta.items():
        p = m.metadata_props.add()
        p.key, p.value = k, v
    onnx.save(m, OUT)
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB)")

    # ------------------------------------------------------------ check every input size
    sess = ort.InferenceSession(OUT, providers=["CPUExecutionProvider"])
    print("input:", [(i.name, i.shape) for i in sess.get_inputs()],
          "outputs:", [(o.name, o.shape) for o in sess.get_outputs()])
    worst_logit = worst_box = 0.0
    for f, x in zip(images, inputs):
        with torch.no_grad():
            ref_logits, ref_boxes = wrapped(x)
        logits, boxes = sess.run(None, {"pixel_values": x.numpy()})
        dl = float(np.abs(logits - ref_logits.numpy()).max())
        db = float(np.abs(boxes - ref_boxes.numpy()).max())
        worst_logit, worst_box = max(worst_logit, dl), max(worst_box, db)
        print(f"  {os.path.basename(f):28} input {tuple(x.shape[2:])}: max |logit diff| {dl:.2e}, max |box diff| {db:.2e}")
    ok = worst_logit < 1e-3 and worst_box < 1e-4
    print("ONNX == PyTorch at every size:", "PASS" if ok else "FAIL",
          f"(worst logit diff {worst_logit:.2e}, worst box diff {worst_box:.2e})")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
