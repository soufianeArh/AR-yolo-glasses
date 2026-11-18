"""
Step 7.3 check: the C++ plant detector (worker-cli, Linux build in WSL) vs soufiane-easy's
Python code path (transformers YolosImageProcessor + model + post_process_object_detection),
on every leaf image in testdata/plant/expected.json.

Two separate questions:
  1. CORRECTNESS of the C++ port: same detections as Python (labels, scores +-0.01,
     boxes +-0.01 of the image size). This is what must PASS.
  2. ACCURACY of the model itself: is the top detection the expected disease? This is a
     property of the trained model (not of our code) - reported, not pass/fail.

Usage (Windows, from the AR folder):
  tools/.venv/Scripts/python tools/compare_plant.py [--conf 0.4]
"""
import argparse
import json
import os
import subprocess
import sys

import torch
from PIL import Image
from transformers import AutoImageProcessor, AutoModelForObjectDetection

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCORE_TOL, BOX_TOL = 0.01, 0.01


def wsl_path(p):
    drive, rest = os.path.splitdrive(os.path.abspath(p))
    return f"/mnt/{drive[0].lower()}{rest.replace(os.sep, '/')}"


def python_results(files, conf):
    src = os.path.join(ROOT, "models", "plant-disease")
    proc = AutoImageProcessor.from_pretrained(src)
    model = AutoModelForObjectDetection.from_pretrained(src).eval()
    out = {}
    for f in files:
        img = Image.open(os.path.join(ROOT, f)).convert("RGB")
        with torch.no_grad():
            o = model(**proc(images=img, return_tensors="pt"))
        r = proc.post_process_object_detection(o, threshold=conf)[0]     # boxes stay relative 0..1
        dets = [{"label": model.config.id2label[int(l)], "confidence": float(s),
                 **dict(zip(("x1", "y1", "x2", "y2"), [min(max(v, 0.0), 1.0) for v in b.tolist()]))}
                for s, l, b in zip(r["scores"], r["labels"], r["boxes"])]
        out[f] = sorted(dets, key=lambda d: -d["confidence"])
    return out


def cpp_results(files, conf):
    home = subprocess.run(["wsl", "-d", "Ubuntu", "--exec", "printenv", "HOME"], capture_output=True, text=True,
                          check=True).stdout.strip()
    cmd = ["wsl", "-d", "Ubuntu", "--cd", wsl_path(ROOT), "--exec", f"{home}/ar-build/worker-cpp/worker-cli",
           "--model", "models/plant.onnx", "--name", "plant", "--threads", "4", "--conf", str(conf), *files]
    lines = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.splitlines()
    return {json.loads(l)["file"]: json.loads(l) for l in lines if l.strip()}


def same(a, b):
    """Pair detections by label and box; every one must match within tolerance."""
    if len(a) != len(b):
        return False, f"count {len(a)} vs {len(b)}"
    used, worst_s, worst_b = set(), 0.0, 0.0
    for x in a:
        best, bd = None, None
        for j, y in enumerate(b):
            if j in used or y["label"] != x["label"]:
                continue
            d = max(abs(x[k] - y[k]) for k in ("x1", "y1", "x2", "y2"))
            if bd is None or d < bd:
                best, bd = j, d
        if best is None:
            return False, f"no match for {x['label']}"
        used.add(best)
        worst_s = max(worst_s, abs(x["confidence"] - b[best]["confidence"]))
        worst_b = max(worst_b, bd)
    ok = worst_s <= SCORE_TOL and worst_b <= BOX_TOL
    return ok, f"dscore {worst_s:.4f} dbox {worst_b:.4f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--conf", type=float, default=0.4)
    args = ap.parse_args()
    manifest = json.load(open(os.path.join(ROOT, "testdata", "plant", "expected.json")))
    files = sorted(manifest)

    print(f"running Python reference on {len(files)} images ...", flush=True)
    ref = python_results(files, args.conf)
    print("running C++ worker-cli (WSL) ...", flush=True)
    got = cpp_results(files, args.conf)

    port_ok, n_correct, n_scored, rows = 0, 0, 0, []
    infer_ms = []
    for f in files:
        exp = manifest[f]["expected"]
        g = got.get(f, {})
        dets = g.get("detections", [])
        ok, detail = same(ref[f], dets) if g.get("status") == "OK" else (False, g.get("status", "missing"))
        port_ok += ok
        if g.get("timing_ms"):
            infer_ms.append(g["timing_ms"]["infer"])
        top = dets[0]["label"] if dets else "(nothing above threshold)"
        if not exp.startswith("healthy corn"):
            n_scored += 1
            n_correct += top == exp
        rows.append((f, ok, detail, exp, top, dets[0]["confidence"] if dets else None))

    print(f"\n{'image':52} {'C++ == Python':14} {'expected':28} top detection")
    for f, ok, detail, exp, top, conf in rows:
        mark = "✓" if top == exp else " "
        c = f" {conf:.2f}" if conf is not None else ""
        print(f"{os.path.basename(f)[:50]:52} {('PASS ' if ok else 'FAIL ') + detail:14.14} {exp[:27]:28} {mark} {top}{c}")

    print(f"\n1. C++ PORT CORRECTNESS: {port_ok}/{len(files)} images identical to Python "
          f"(scores +-{SCORE_TOL}, boxes +-{BOX_TOL})  ->  {'PASS' if port_ok == len(files) else 'FAIL'}")
    print(f"2. MODEL ACCURACY (top detection == expected disease): {n_correct}/{n_scored} "
          f"({100 * n_correct / max(1, n_scored):.0f}%) - a property of the trained model, not of the C++ code")
    if infer_ms:
        s = sorted(infer_ms)
        print(f"   C++ inference time (4 threads, includes first run of each new input size): "
              f"median {s[len(s) // 2]:.0f} ms, max {s[-1]:.0f} ms")
    sys.exit(0 if port_ok == len(files) else 1)


if __name__ == "__main__":
    main()
