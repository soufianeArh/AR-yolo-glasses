"""
Compare the C++ worker (worker-cli) against Ultralytics on the same images.

Both run the SAME yolov8n.onnx with the same thresholds, so any difference comes from
the C++ pre-/post-processing (letterbox, decode, NMS, box mapping) - which is what
this checks.

Pass criteria (Step 2 in ROADMAP.md), per image:
  - same number of detections, same labels
  - confidence within +-0.01
  - box corners within 2 px (in original image pixels)

Usage (from the AR folder):
  tools/.venv/Scripts/python tools/compare.py [--conf 0.4] [--iou 0.5]
"""
import argparse
import glob
import json
import os
import subprocess
import sys

import cv2
from ultralytics import YOLO

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL = os.path.join(ROOT, "models", "yolov8n.onnx")
CLI = os.path.join(ROOT, "worker-cpp", "build", "Release", "worker-cli.exe")
if not os.path.exists(CLI):  # Linux build
    CLI = os.path.join(ROOT, "worker-cpp", "build", "worker-cli")

CONF_TOL = 0.01
BOX_TOL_PX = 2.0


def ultralytics_results(images, conf, iou):
    model = YOLO(MODEL, task="detect")
    out = {}
    for path in images:
        r = model.predict(path, conf=conf, iou=iou, verbose=False)[0]
        h, w = r.orig_shape
        dets = []
        for box, c, p in zip(r.boxes.xyxy.tolist(), r.boxes.cls.tolist(), r.boxes.conf.tolist()):
            dets.append({"label": r.names[int(c)], "confidence": float(p),
                         "x1": box[0] / w, "y1": box[1] / h, "x2": box[2] / w, "y2": box[3] / h})
        out[path] = (w, h, dets)
    return out


def wsl_path(win_path):
    drive, rest = os.path.splitdrive(os.path.abspath(win_path))
    return f"/mnt/{drive[0].lower()}{rest.replace(os.sep, '/')}"


def cpp_results(images, conf, iou, wsl=False):
    if wsl:
        # Linux build inside WSL Ubuntu (see worker-cpp/CMakeLists.txt). Image paths are
        # relative to the AR folder, so they work unchanged on both sides.
        home = subprocess.run(["wsl", "-d", "Ubuntu", "--exec", "printenv", "HOME"],
                              capture_output=True, text=True, check=True).stdout.strip()
        cmd = ["wsl", "-d", "Ubuntu", "--cd", wsl_path(ROOT), "--exec", f"{home}/ar-build/worker-cpp/worker-cli",
               "--model", "models/yolov8n.onnx", "--conf", str(conf), "--iou", str(iou), *images]
    else:
        cmd = [CLI, "--model", MODEL, "--conf", str(conf), "--iou", str(iou), *images]
    lines = subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.splitlines()
    return {json.loads(line)["file"]: json.loads(line) for line in lines if line.strip()}


def pair(a, b, w, h):
    """Greedily pair detections with the same label by smallest box distance."""
    pairs, used = [], set()
    for da in sorted(a, key=lambda d: -d["confidence"]):
        best, best_dist = None, None
        for j, db in enumerate(b):
            if j in used or db["label"] != da["label"]:
                continue
            dist = max(abs(da[k] - db[k]) * (w if k[0] == "x" else h) for k in ("x1", "y1", "x2", "y2"))
            if best_dist is None or dist < best_dist:
                best, best_dist = j, dist
        if best is None:
            return None
        used.add(best)
        pairs.append((da, b[best], best_dist))
    return pairs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--conf", type=float, default=0.4)
    ap.add_argument("--iou", type=float, default=0.5)
    ap.add_argument("--wsl", action="store_true", help="test the Linux build inside WSL Ubuntu")
    args = ap.parse_args()

    images = sorted(glob.glob(os.path.join(ROOT, "testdata", "*", "*.png")) +
                    glob.glob(os.path.join(ROOT, "testdata", "*", "*.jpg")))
    images = [os.path.relpath(p, ROOT).replace("\\", "/") for p in images]
    os.chdir(ROOT)

    ref = ultralytics_results(images, args.conf, args.iou)
    got = cpp_results(images, args.conf, args.iou, wsl=args.wsl)
    print("C++ worker:", "Linux build in WSL" if args.wsl else "Windows build")

    all_ok, worst_conf, worst_box = True, 0.0, 0.0
    print(f"{'image':42} {'ultra':>5} {'c++':>5} {'max dconf':>10} {'max dbox px':>12}  result")
    for path in images:
        w, h, a = ref[path]
        b = got[path].get("detections", [])
        pairs = pair(a, b, w, h) if len(a) == len(b) else None
        if pairs is None:
            all_ok = False
            print(f"{path:42} {len(a):5} {len(b):5} {'-':>10} {'-':>12}  FAIL (labels/count differ)")
            continue
        dconf = max((abs(x["confidence"] - y["confidence"]) for x, y, _ in pairs), default=0.0)
        dbox = max((d for _, _, d in pairs), default=0.0)
        ok = dconf <= CONF_TOL and dbox <= BOX_TOL_PX
        all_ok &= ok
        worst_conf, worst_box = max(worst_conf, dconf), max(worst_box, dbox)
        print(f"{path:42} {len(a):5} {len(b):5} {dconf:10.5f} {dbox:12.3f}  {'PASS' if ok else 'FAIL'}")

    print(f"\nworst confidence diff: {worst_conf:.5f} (tolerance {CONF_TOL})")
    print(f"worst box diff:        {worst_box:.3f} px (tolerance {BOX_TOL_PX})")
    print("OVERALL:", "PASS" if all_ok else "FAIL")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
