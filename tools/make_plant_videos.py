import argparse
import json
import os

import cv2
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "testdata", "plant", "videos")
W, H, FPS = 1280, 720, 24
MOVE_S, HOLD_S = 1.2, 2.0

VIDEOS = {
    "corn-look-and-hold": ["corn-leaf-blight-1", "corn-rust-leaf-1", "corn-gray-leaf-spot-1",
                           "corn-leaf-blight-2", "corn-rust-leaf-2"],
    "tomato-look-and-hold": ["tomato-leaf-late-blight-1", "tomato-early-blight-leaf-1",
                             "tomato-septoria-leaf-spot-1", "tomato-leaf-mosaic-virus-1", "tomato-leaf-1"],
    "potato-look-and-hold": ["potato-leaf-late-blight-1", "potato-leaf-early-blight-1",
                             "potato-leaf-late-blight-2", "potato-leaf-early-blight-2"],
}


def tile(img):
    canvas = np.full((H, W, 3), (20, 45, 20), np.uint8)
    s = min(W / img.shape[1], H / img.shape[0])
    nw, nh = int(img.shape[1] * s), int(img.shape[0] * s)
    r = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC)
    y, x = (H - nh) // 2, (W - nw) // 2
    canvas[y:y + nh, x:x + nw] = r
    return canvas


# smooth start and stop like a real head turn
def ease(t):
    return t * t * (3 - 2 * t)


def main():
    global HOLD_S
    ap = argparse.ArgumentParser()
    ap.add_argument("--hold", type=float, default=HOLD_S, help="seconds the view stays on each leaf")
    ap.add_argument("--suffix", default="", help="added to the file names, e.g. -6s")
    args = ap.parse_args()
    HOLD_S = args.hold
    manifest = json.load(open(os.path.join(ROOT, "testdata", "plant", "expected.json")))
    by_stem = {os.path.splitext(os.path.basename(k))[0]: (k, v["expected"]) for k, v in manifest.items()}
    os.makedirs(OUT, exist_ok=True)

    for base, stems in VIDEOS.items():
        name = base + args.suffix
        tiles, expected = [], []
        for st in stems:
            path, exp = by_stem[st]
            tiles.append(tile(cv2.imread(os.path.join(ROOT, path))))
            expected.append((path, exp))
        strip = np.concatenate(tiles, axis=1)
        writer = cv2.VideoWriter(os.path.join(OUT, name + ".mp4"), cv2.VideoWriter_fourcc(*"mp4v"), FPS, (W, H))
        timeline, t = [], 0.0

        def emit(x, blur):
            nonlocal t
            xi = int(min(max(round(x), 0), strip.shape[1] - W))
            frame = np.ascontiguousarray(strip[:, xi:xi + W])
            if blur > 1:
                k = np.zeros((1, blur), np.float32)
                k[:] = 1.0 / blur
                frame = cv2.filter2D(frame, -1, k)
            writer.write(frame)
            t += 1.0 / FPS

        x_prev = 0.0
        for i, (path, exp) in enumerate(expected):
            x_target = i * W
            n_move = int(MOVE_S * FPS) if i > 0 else int(0.5 * FPS)
            for f in range(n_move):
                a = ease((f + 1) / n_move)
                x = x_prev + (x_target - x_prev) * a
                speed = abs((x_target - x_prev) * (ease((f + 1) / n_move) - ease(f / n_move)))
                emit(x, int(min(31, speed / 8)))
            start = t
            for f in range(int(HOLD_S * FPS)):
                emit(x_target + 2 * np.sin(f / 5), 0)
            timeline.append({"hold_start_s": round(start, 2), "hold_end_s": round(t, 2),
                             "image": path, "expected": exp})
            x_prev = x_target
        writer.release()
        json.dump({"video": f"testdata/plant/videos/{name}.mp4", "fps": FPS, "size": [W, H],
                   "duration_s": round(t, 2), "holds": timeline},
                  open(os.path.join(OUT, name + ".timeline.json"), "w"), indent=1)
        print(f"{name}.mp4: {t:.1f} s, {len(timeline)} holds: " + ", ".join(h["expected"] for h in timeline))


if __name__ == "__main__":
    main()
