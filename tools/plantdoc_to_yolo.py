import argparse
import collections
import csv
import os
import sys

from PIL import Image


def read_labels(path):
    rows = collections.defaultdict(list)
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows[r["filename"]].append(r)
    return rows


# some plantdoc names are not valid on windows so files get renamed
def convert(src, out, split_in, split_out, csv_name, classes, max_side, stats, mapping):
    labels = read_labels(os.path.join(src, csv_name))
    os.makedirs(os.path.join(out, "images", split_out), exist_ok=True)
    os.makedirs(os.path.join(out, "labels", split_out), exist_ok=True)
    idx = 0
    for fname in sorted(labels):
        p = os.path.join(src, split_in, fname)
        if not os.path.exists(p):
            stats[f"{split_out}: image file missing"] += 1
            continue
        try:
            im = Image.open(p)
            im.load()
            im = im.convert("RGB")
        except Exception:
            stats[f"{split_out}: image unreadable"] += 1
            continue
        lines = []
        for r in labels[fname]:
            w, h = float(r["width"]), float(r["height"])
            if w <= 0 or h <= 0:
                w, h = float(im.width), float(im.height)
            x1, y1 = max(0.0, float(r["xmin"]) / w), max(0.0, float(r["ymin"]) / h)
            x2, y2 = min(1.0, float(r["xmax"]) / w), min(1.0, float(r["ymax"]) / h)
            if x2 <= x1 or y2 <= y1:
                stats[f"{split_out}: empty box skipped"] += 1
                continue
            lines.append(f"{classes.index(r['class'])} {(x1 + x2) / 2:.6f} {(y1 + y2) / 2:.6f} "
                         f"{x2 - x1:.6f} {y2 - y1:.6f}")
        if not lines:
            stats[f"{split_out}: no usable box"] += 1
            continue
        idx += 1
        name = f"{split_out}_{idx:05d}"
        s = max_side / max(im.width, im.height)
        if s < 1:
            im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
        im.save(os.path.join(out, "images", split_out, name + ".jpg"), quality=92)
        with open(os.path.join(out, "labels", split_out, name + ".txt"), "w") as f:
            f.write("\n".join(lines) + "\n")
        mapping.append((f"{split_out}/{name}.jpg", f"{split_in}/{fname}"))
        stats[f"{split_out}: images"] += 1
        stats[f"{split_out}: boxes"] += len(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="PlantDoc clone (with TRAIN/, TEST/, *_labels.csv)")
    ap.add_argument("--out", required=True, help="output folder for the YOLO dataset")
    ap.add_argument("--max-side", type=int, default=1280, help="shrink images so the longest side is <= this")
    args = ap.parse_args()

    classes = sorted({r["class"] for name in ("train_labels.csv", "test_labels.csv")
                      for rows in read_labels(os.path.join(args.src, name)).values() for r in rows})
    stats, mapping = collections.Counter(), []
    convert(args.src, args.out, "TRAIN", "train", "train_labels.csv", classes, args.max_side, stats, mapping)
    convert(args.src, args.out, "TEST", "val", "test_labels.csv", classes, args.max_side, stats, mapping)

    out_abs = os.path.abspath(args.out)
    with open(os.path.join(args.out, "data.yaml"), "w", encoding="utf-8") as f:
        f.write(f"path: {out_abs}\ntrain: images/train\nval: images/val\n")
        f.write(f"nc: {len(classes)}\nnames:\n")
        for i, c in enumerate(classes):
            f.write(f"  {i}: \"{c}\"\n")
    with open(os.path.join(args.out, "mapping.csv"), "w", newline="", encoding="utf-8") as f:
        csv.writer(f).writerows([("yolo_image", "plantdoc_image"), *mapping])

    print(f"{len(classes)} classes: {classes}")
    for k in sorted(stats):
        print(f"  {k}: {stats[k]}")
    print(f"wrote {out_abs}/data.yaml")
    if stats["train: images"] < 2000 or stats["val: images"] < 200:
        sys.exit("too few images converted - check the source folder")


if __name__ == "__main__":
    main()
