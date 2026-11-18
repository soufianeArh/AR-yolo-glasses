import argparse
import glob
import json
import os
import re
import subprocess
import sys

import cv2

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def wsl_path(p):
    drive, rest = os.path.splitdrive(os.path.abspath(p))
    return f"/mnt/{drive[0].lower()}{rest.replace(os.sep, '/')}"


def wsl(*args):
    home = subprocess.run(["wsl", "-d", "Ubuntu", "--exec", "printenv", "HOME"], capture_output=True, text=True,
                          check=True).stdout.strip()
    cmd = ["wsl", "-d", "Ubuntu", "--cd", wsl_path(ROOT), "--exec", args[0].replace("~", home), *args[1:]]
    return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout


def export_if_needed(path):
    if path.endswith(".onnx"):
        return path
    from ultralytics import YOLO
    out = YOLO(path).export(format="onnx", imgsz=640, dynamic=False, simplify=True, opset=17)
    dst = os.path.splitext(path)[0] + ".onnx"
    if os.path.abspath(out) != os.path.abspath(dst):
        os.replace(out, dst)
    return os.path.relpath(dst, ROOT).replace(os.sep, "/")


def top_labels(model, conf, files):
    out = wsl("~/ar-build/worker-cpp/worker-cli", "--model", model, "--name", "plant", "--threads", "4",
              "--conf", str(conf), *files)
    res = {}
    for line in out.splitlines():
        if line.strip():
            j = json.loads(line)
            d = j.get("detections", [])
            res[j["file"]] = max(d, key=lambda x: x["confidence"])["label"] if d else "-"
    return res


# timed with the c++ cli so it matches the real worker
def speed(model, image):
    out = wsl("~/ar-build/worker-cpp/worker-bench", "--model", model, "--image", image, "--threads", "3",
              "--iters", "8", "--warmup", "2")
    row = [l for l in out.splitlines() if l.startswith("| 3 |")][0].split("|")
    return float(re.findall(r"[\d.]+", row[4])[0]), float(re.findall(r"[\d.]+", row[5])[0])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--new", required=True)
    ap.add_argument("--old", default="models/plant.onnx")
    ap.add_argument("--new-conf", type=float, default=0.25, help="YOLOv8 default confidence")
    ap.add_argument("--old-conf", type=float, default=0.40, help="soufiane-easy's plant floor")
    args = ap.parse_args()
    os.chdir(ROOT)

    manifest = json.load(open("testdata/plant/expected.json"))
    files = sorted(f for f, v in manifest.items() if not v["expected"].startswith("healthy corn"))

    os.makedirs("runs/eval-holds", exist_ok=True)
    holds = []
    for tl in sorted(glob.glob("testdata/plant/videos/*.timeline.json")):
        t = json.load(open(tl))
        cap = cv2.VideoCapture(t["video"])
        for k, h in enumerate(t["holds"]):
            cap.set(cv2.CAP_PROP_POS_FRAMES, int((h["hold_start_s"] + h["hold_end_s"]) / 2 * t["fps"]))
            ok, img = cap.read()
            p = f"runs/eval-holds/{os.path.basename(tl).split('.')[0]}-{k}.jpg"
            cv2.imwrite(p, img)
            holds.append((p, h["image"]))

    models = [("current (YOLOS, soufiane-easy)", args.old, args.old_conf),
              ("new (YOLOv8, trained)", export_if_needed(args.new), args.new_conf)]
    report = []
    for name, path, conf in models:
        print(f"\n== {name}: {path} (conf {conf})", flush=True)
        tops = top_labels(path, conf, files)
        correct = sum(tops[f] == manifest[f]["expected"] for f in files)
        hold_tops = top_labels(path, conf, [p for p, _ in holds] + sorted({o for _, o in holds}))
        stable = sum(hold_tops[p] == hold_tops[o] for p, o in holds)
        infer, p95 = speed(path, "testdata/plant/plantdoc/tomato-leaf-late-blight-2.jpg")
        r = {"model": name, "path": path, "accuracy": f"{correct}/{len(files)}",
             "accuracy_pct": round(100 * correct / len(files)), "stability": f"{stable}/{len(holds)}",
             "infer_ms_avg": infer, "infer_ms_p95": p95,
             "per_image": {os.path.basename(f): {"expected": manifest[f]["expected"], "top": tops[f]} for f in files}}
        report.append(r)
        print(f"   accuracy  {r['accuracy']} ({r['accuracy_pct']}%)   stability {r['stability']}   "
              f"speed {infer:.0f} ms avg / {p95:.0f} ms p95 (3 threads)", flush=True)

    print(f"\n{'':34} {'accuracy':>12} {'stability':>10} {'speed (3 threads)':>20}")
    for r in report:
        print(f"{r['model']:34} {r['accuracy']:>8} {r['accuracy_pct']:>2}% {r['stability']:>10} "
              f"{r['infer_ms_avg']:>11.0f} ms avg")
    os.makedirs("bench", exist_ok=True)
    json.dump(report, open("bench/plant_models.json", "w"), indent=1)
    print("\nwrote bench/plant_models.json")


if __name__ == "__main__":
    sys.exit(main())
