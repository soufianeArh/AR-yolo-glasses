"""
Fake Android app: streams frames to the Java scheduler exactly like the real Android app will.

  - one gRPC two-way stream (FrameStream.Stream) per session
  - frames resized to ~640 px wide and JPEG-encoded on the client
  - at most --max-in-flight frames waiting for an answer; when full, the newest camera frame
    is DROPPED on the client (newest frame wins), never queued
  - latency measured end to end on the client: from "captured" to result received

Two modes:
  video   play a video (looped) at --fps and report throughput / latency
  images  send each image once and print one JSON line per result (used by the tests)

Usage (inside WSL, from the AR folder):
  ~/ar-venv/bin/python tools/fake_client.py video --video testdata/land/loop.mp4 --fps 8 --seconds 20
  ~/ar-venv/bin/python tools/fake_client.py images testdata/land/*.png
"""
import argparse
import glob
import json
import os
import queue
import sys
import threading
import time
from collections import Counter

import cv2
import grpc

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GEN = os.path.join(ROOT, "tools", "_gen")


def load_stubs():
    from grpc_tools import protoc
    os.makedirs(GEN, exist_ok=True)
    rc = protoc.main(["protoc", f"-I{os.path.join(ROOT, 'proto')}", f"--python_out={GEN}",
                      f"--grpc_python_out={GEN}", os.path.join(ROOT, "proto", "ar", "v1", "detection.proto")])
    if rc != 0:
        sys.exit("protoc failed")
    sys.path.insert(0, GEN)
    from ar.v1 import detection_pb2, detection_pb2_grpc
    return detection_pb2, detection_pb2_grpc


pb, pbg = load_stubs()
STATUS = {v: k for k, v in pb.ResultStatus.items()}


def now_ms():
    return int(time.time() * 1000)


def encode(img, width, quality):
    """What the Android app does before sending: shrink to ~640 wide, JPEG."""
    if img.shape[1] > width:
        h = round(img.shape[0] * width / img.shape[1])
        img = cv2.resize(img, (width, h), interpolation=cv2.INTER_AREA)
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not ok:
        raise RuntimeError("JPEG encode failed")
    return buf.tobytes()


def percentile(sorted_vals, p):
    if not sorted_vals:
        return None
    idx = max(0, min(len(sorted_vals) - 1, -(-p * len(sorted_vals) // 100) - 1))
    return sorted_vals[idx]


class Stream:
    """One session: a request queue feeding the gRPC stream, and a reader for the results."""

    def __init__(self, target, token, max_in_flight):
        self.channel = grpc.insecure_channel(target, options=[("grpc.max_receive_message_length", 8 << 20),
                                                              ("grpc.max_send_message_length", 8 << 20)])
        self.stub = pbg.FrameStreamStub(self.channel)
        self.requests = queue.Queue()
        self.slots = threading.Semaphore(max_in_flight)
        self.sent_at = {}           # frame_id -> captured_at_ms
        self.results = []           # (FrameResult, latency_ms)
        self.lock = threading.Lock()
        self.error = None
        metadata = [("authorization", token)] if token else None
        self.responses = self.stub.Stream(self._iter(), metadata=metadata)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _iter(self):
        while True:
            item = self.requests.get()
            if item is None:
                return
            yield item

    def _read(self):
        try:
            for r in self.responses:
                t = now_ms()
                with self.lock:
                    captured = self.sent_at.pop(r.frame_id, None)
                    self.results.append((r, None if captured is None else t - captured))
                self.slots.release()
        except grpc.RpcError as e:
            self.error = e

    def try_send(self, frame_id, jpeg, blocking=False):
        """Send if a slot is free. Returns False when the client must drop this frame."""
        if not self.slots.acquire(blocking=blocking):
            return False
        captured = now_ms()
        with self.lock:
            self.sent_at[frame_id] = captured
        self.requests.put(pb.Frame(frame_id=frame_id, captured_at_ms=captured, jpeg=jpeg, models=["default"]))
        return True

    def close(self, timeout=10):
        self.requests.put(None)        # half-close: no more frames
        self.reader.join(timeout)
        self.channel.close()


# ------------------------------------------------------------------ modes

def run_images(args):
    paths = []
    for pattern in args.paths:
        paths.extend(sorted(glob.glob(pattern)) or [pattern])
    s = Stream(args.target, args.token, max_in_flight=1)
    for i, path in enumerate(paths):
        with open(path, "rb") as f:
            s.try_send(i, f.read(), blocking=True)
    s.close()
    by_id = {r.frame_id: (r, lat) for r, lat in s.results}
    for i, path in enumerate(paths):
        r, lat = by_id.get(i, (None, None))
        if r is None:
            print(json.dumps({"file": path, "status": "NO_ANSWER"}))
            continue
        print(json.dumps({
            "file": path, "status": STATUS[r.status], "error": r.error, "worker_id": r.worker_id,
            "infer_ms": r.infer_ms, "backend_ms": r.backend_ms, "client_ms": lat,
            "detections": [{"label": d.label, "confidence": d.confidence, "x1": d.x1, "y1": d.y1,
                            "x2": d.x2, "y2": d.y2, "model": d.model} for d in r.detections]}))


def run_video(args):
    # Pre-encode the video at the client frame rate, so encoding cost is not in the measurement.
    cap = cv2.VideoCapture(args.video)
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 25
    frames, t = [], 0.0
    idx = 0
    while True:
        ok, img = cap.read()
        if not ok:
            break
        # keep the source frames that fall on our --fps clock
        while t <= idx / src_fps:
            frames.append(encode(img, args.width, args.quality))
            t += 1.0 / args.fps
        idx += 1
    if not frames:
        sys.exit(f"could not read {args.video}")
    avg_kb = sum(len(f) for f in frames) / len(frames) / 1024

    # --burst: a buggy client with no in-flight limit, to test the scheduler's per-session cap.
    s = Stream(args.target, args.token, 1_000_000 if args.burst else args.max_in_flight)
    period = 1.0 / args.fps
    start = time.perf_counter()
    n = dropped_client = 0
    fid = 0
    while time.perf_counter() - start < args.seconds:
        for _ in range(args.burst or 1):
            if not s.try_send(fid, frames[n % len(frames)]):
                dropped_client += 1
            fid += 1
        n += 1
        next_tick = start + n * period
        time.sleep(max(0.0, next_tick - time.perf_counter()))
    s.close()
    elapsed = time.perf_counter() - start

    statuses = Counter(STATUS[r.status] for r, _ in s.results)
    ok_lat = sorted(lat for r, lat in s.results if r.status == pb.OK and lat is not None)
    backend = sorted(r.backend_ms for r, _ in s.results if r.status == pb.OK)
    labels = Counter(d.label for r, _ in s.results for d in r.detections)
    summary = {
        "seconds": round(elapsed, 1), "camera_fps": args.fps, "max_in_flight": args.max_in_flight,
        "frames_captured": fid, "dropped_on_client": dropped_client, "sent": fid - dropped_client,
        "answered": len(s.results), "statuses": dict(statuses),
        "ok_fps": round(statuses.get("OK", 0) / elapsed, 2), "avg_jpeg_kb": round(avg_kb, 1),
        "client_latency_ms": {"p50": percentile(ok_lat, 50), "p95": percentile(ok_lat, 95),
                              "p99": percentile(ok_lat, 99)},
        "backend_ms": {"p50": percentile(backend, 50), "p95": percentile(backend, 95),
                       "p99": percentile(backend, 99)},
        "top_labels": dict(labels.most_common(5)),
        "ok_by_worker": dict(Counter(r.worker_id for r, _ in s.results if r.status == pb.OK)),
    }
    if args.raw:
        summary["ok_latencies_ms"] = ok_lat
    print(json.dumps(summary) if args.json else json.dumps(summary, indent=2))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", default="127.0.0.1:50051", help="Java scheduler address")
    ap.add_argument("--token", default="fake-device-1", help="device token (authorization header)")
    sub = ap.add_subparsers(dest="mode", required=True)

    v = sub.add_parser("video")
    v.add_argument("--video", default=os.path.join(ROOT, "testdata", "land", "loop.mp4"))
    v.add_argument("--fps", type=float, default=8, help="camera frame rate")
    v.add_argument("--seconds", type=float, default=20)
    v.add_argument("--max-in-flight", type=int, default=2)
    v.add_argument("--width", type=int, default=640)
    v.add_argument("--quality", type=int, default=80)
    v.add_argument("--json", action="store_true", help="one-line JSON summary")
    v.add_argument("--raw", action="store_true", help="include every OK latency (for benchmarks)")
    v.add_argument("--burst", type=int, default=0,
                   help="misbehave: ignore --max-in-flight and send this many frames per tick")

    i = sub.add_parser("images")
    i.add_argument("paths", nargs="+")

    args = ap.parse_args()
    run_video(args) if args.mode == "video" else run_images(args)


if __name__ == "__main__":
    main()
