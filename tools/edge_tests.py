"""
Edge-case tests for the C++ worker (worker-cli).

Generates unusual inputs in a temporary folder and checks that the worker:
  - returns BAD_FRAME (never crashes) for bytes that are not an image
  - still matches Ultralytics for valid but unusual images
    (grayscale, transparency, 16-bit, tiny, very wide, very tall, huge)

Usage (from the AR folder):
  tools/.venv/Scripts/python tools/edge_tests.py
"""
import json
import os
import subprocess
import sys
import tempfile

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare import CLI, MODEL, ROOT, BOX_TOL_PX, CONF_TOL, pair, ultralytics_results  # noqa: E402

SOURCE = os.path.join(ROOT, "testdata", "land", "cow-attack-01.png")


def run_cli(paths):
    proc = subprocess.run([CLI, "--model", MODEL, *paths], capture_output=True, text=True)
    lines = [json.loads(l) for l in proc.stdout.splitlines() if l.strip()]
    return proc.returncode, {l["file"]: l for l in lines}, proc.stderr


def main():
    tmp = tempfile.mkdtemp(prefix="ar-edge-")
    img = cv2.imread(SOURCE, cv2.IMREAD_COLOR)
    jpeg = cv2.imencode(".jpg", img)[1].tobytes()

    bad = {
        "empty.jpg": b"",
        "garbage.jpg": os.urandom(5000),
        "text.jpg": b"this is not an image",
        "truncated-header.jpg": jpeg[:20],
    }
    valid = {
        "gray.png": cv2.cvtColor(img, cv2.COLOR_BGR2GRAY),
        "rgba.png": cv2.cvtColor(img, cv2.COLOR_BGR2BGRA),
        "16bit.png": (img.astype(np.uint16) * 257),
        "tiny-1x1.png": img[:1, :1],
        "small-17x9.png": cv2.resize(img, (17, 9)),
        "wide-3000x120.png": cv2.resize(img, (3000, 120)),
        "tall-120x3000.png": cv2.resize(img, (120, 3000)),
        "huge-4000x3000.jpg": cv2.resize(img, (4000, 3000)),
        "exact-640x640.png": cv2.resize(img, (640, 640)),
        "odd-641x479.png": cv2.resize(img, (641, 479)),
    }

    paths_bad = []
    for name, data in bad.items():
        p = os.path.join(tmp, name)
        with open(p, "wb") as f:
            f.write(data)
        paths_bad.append(p)
    paths_valid = []
    for name, mat in valid.items():
        p = os.path.join(tmp, name)
        cv2.imwrite(p, mat)
        paths_valid.append(p)

    ok_all = True

    # 1. Bad bytes: must be BAD_FRAME, the process must not crash, and it must keep going.
    code, out, err = run_cli(paths_bad + [SOURCE])
    print("bad input (expect BAD_FRAME, no crash):")
    for p in paths_bad:
        status = out.get(p, {}).get("status", "MISSING")
        ok = status == "BAD_FRAME"
        ok_all &= ok
        print(f"  {os.path.basename(p):28} {status:10} {'PASS' if ok else 'FAIL'}")
    ok = code == 0 and out.get(SOURCE, {}).get("status") == "OK"
    ok_all &= ok
    print(f"  {'(real image after them)':28} {out.get(SOURCE, {}).get('status', 'MISSING'):10} "
          f"{'PASS' if ok else 'FAIL'}  exit code {code}")
    if err.strip():
        print("  stderr:", err.strip()[:300])

    # 2. Valid but unusual images: must match Ultralytics.
    code, out, err = run_cli(paths_valid)
    ref = ultralytics_results(paths_valid, conf=0.4, iou=0.5)
    print("\nunusual valid images (expect same result as Ultralytics):")
    for p in paths_valid:
        w, h, a = ref[p]
        res = out.get(p, {})
        b = res.get("detections", [])
        pairs = pair(a, b, w, h) if res.get("status") == "OK" and len(a) == len(b) else None
        if pairs is None:
            ok_all = False
            print(f"  {os.path.basename(p):28} ultra={len(a)} c++={len(b)} status={res.get('status')}  FAIL")
            continue
        dconf = max((abs(x["confidence"] - y["confidence"]) for x, y, _ in pairs), default=0.0)
        dbox = max((d for _, _, d in pairs), default=0.0)
        ok = dconf <= CONF_TOL and dbox <= BOX_TOL_PX
        ok_all &= ok
        print(f"  {os.path.basename(p):28} {w}x{h:<6} dets={len(b):2} dconf={dconf:.5f} dbox={dbox:.3f}px  "
              f"{'PASS' if ok else 'FAIL'}")

    print("\nOVERALL:", "PASS" if ok_all else "FAIL")
    sys.exit(0 if ok_all else 1)


if __name__ == "__main__":
    main()
