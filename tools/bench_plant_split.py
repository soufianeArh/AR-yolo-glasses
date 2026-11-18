import glob
import json
import os
import signal
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_scheduler import CLIENT, PY, ROOT, SCHEDULER, WORKER, Proc  # noqa: E402
import fake_client  # noqa: E402

SPLITS = [
    (2, 1, 3),
    (2, 2, 2),
    (3, 2, 2),
]
SCHED_PORT, BASE = 50351, 50361
SECONDS = 15


def pct(v, p):
    if not v:
        return None
    s = sorted(v)
    return s[max(0, min(len(s) - 1, -(-p * len(s) // 100) - 1))]


# coco frames at 8 fps while plant inspections run next to it
def coco_stream():
    out = subprocess.run([PY, CLIENT, "--target", f"127.0.0.1:{SCHED_PORT}", "video", "--seconds", str(SECONDS),
                          "--fps", "8", "--max-in-flight", "2", "--json", "--raw"], capture_output=True, text=True,
                         timeout=SECONDS + 120).stdout
    s = json.loads(out)
    ok = s["statuses"].get("OK", 0)
    return {"ok_pct": round(100 * ok / max(1, s["answered"]), 1), "ok_fps": s["ok_fps"],
            "p95": s["client_latency_ms"]["p95"]}


def plant_inspections(stop, frames, out):
    s = fake_client.Stream(f"127.0.0.1:{SCHED_PORT}", "bench-glasses", max_in_flight=1, model="plant")
    i = 0
    while not stop.is_set():
        t = time.time()
        s.try_send(i, frames[i % len(frames)], blocking=True)
        i += 1
        time.sleep(max(0.0, 2.5 - (time.time() - t)))
    s.close(timeout=30)
    for r, lat in s.results:
        out.append((fake_client.STATUS[r.status], lat))


def main():
    os.chdir(ROOT)
    frames = []
    import cv2
    for tl in sorted(glob.glob("testdata/plant/videos/*.timeline.json")):
        t = json.load(open(tl))
        cap = cv2.VideoCapture(t["video"])
        for h in t["holds"]:
            cap.set(cv2.CAP_PROP_POS_FRAMES, int((h["hold_start_s"] + h["hold_end_s"]) / 2 * t["fps"]))
            ok, img = cap.read()
            frames.append(fake_client.encode(img, 640, 80))

    rows = []
    for cw, ct, pt in SPLITS:
        sched = Proc([SCHEDULER, "--port", str(SCHED_PORT), "--report-seconds", "0", "--redis", "none",
                      "--model-deadline", "plant=6000"], "scheduler")
        sched.wait_for("READY")
        procs = [Proc([WORKER, "--model", "models/plant.onnx", "--name", "plant", "--port", str(BASE),
                       "--worker-id", "plant-1", "--threads", str(pt), "--scheduler", f"127.0.0.1:{SCHED_PORT}"],
                      "plant")]
        for i in range(cw):
            procs.append(Proc([WORKER, "--model", "models/yolov8n.onnx", "--port", str(BASE + 1 + i),
                               "--worker-id", f"coco-{i + 1}", "--threads", str(ct), "--scheduler",
                               f"127.0.0.1:{SCHED_PORT}"], f"coco-{i + 1}"))
        for p in procs:
            p.wait_for("READY", 60)
        sched.wait_for("worker registered: plant-1", 10)
        time.sleep(2)

        alone = coco_stream()
        stop, plant_out = threading.Event(), []
        th = threading.Thread(target=plant_inspections, args=(stop, frames, plant_out))
        th.start()
        both = coco_stream()
        stop.set()
        th.join()
        lat = [l for st, l in plant_out if st == "OK" and l is not None]
        row = {"split": f"COCO {cw}x{ct} + plant 1x{pt}", "threads": cw * ct + pt,
               "coco_alone": alone, "coco_with_plant": both,
               "plant_n": len(plant_out), "plant_p50": pct(lat, 50), "plant_p95": pct(lat, 95),
               "plant_within_2500": round(100 * sum(1 for l in lat if l <= 2500) / max(1, len(plant_out)), 1),
               "plant_within_3500": round(100 * sum(1 for l in lat if l <= 3500) / max(1, len(plant_out)), 1)}
        rows.append(row)
        print(f"{row['split']:28} ({row['threads']} threads) | COCO alone {alone['ok_pct']}% OK p95 {alone['p95']} ms "
              f"| COCO + plant {both['ok_pct']}% OK p95 {both['p95']} ms | plant p50 {row['plant_p50']} "
              f"p95 {row['plant_p95']} ms, within 2.5 s {row['plant_within_2500']}%, within 3.5 s "
              f"{row['plant_within_3500']}% (n={row['plant_n']})", flush=True)
        for p in procs:
            p.stop(signal.SIGINT)
        sched.stop(signal.SIGTERM)
        time.sleep(5)

    os.makedirs("bench", exist_ok=True)
    json.dump(rows, open("bench/plant_split.json", "w"), indent=1)
    print("\nwrote bench/plant_split.json")


if __name__ == "__main__":
    main()
