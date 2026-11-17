"""
End-to-end test of the C++ worker-server over gRPC (Step 3).

Starts a FAKE Java scheduler (a Python WorkerControl server that records heartbeats),
starts worker-server, then checks:

  1. heartbeats arrive, with the right worker id / address / models / threads
  2. Detect over gRPC returns exactly what worker-cli returns, on every test image
  3. bad bytes        -> FrameResult.status = BAD_FRAME (gRPC OK), worker keeps serving
  4. unknown model    -> BAD_FRAME with a clear error
  5. overload         -> extra calls rejected with RESOURCE_EXHAUSTED, the rest succeed
  6. tiny deadlines   -> DEADLINE_EXCEEDED, queued frames are skipped (not computed)
  7. scheduler down   -> worker keeps serving; heartbeats resume when it comes back
  8. clean shutdown   -> final counters printed

Usage (from the AR folder):
  tools/.venv/Scripts/python tools/test_worker_server.py
"""
import concurrent.futures as cf
import glob
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time

import grpc

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GEN = os.path.join(ROOT, "tools", "_gen")
MODEL = os.path.join(ROOT, "models", "yolov8n.onnx")
BIN = os.path.join(ROOT, "worker-cpp", "build", "Release")
SERVER = os.path.join(BIN, "worker-server.exe" if os.name == "nt" else "worker-server")
CLI = os.path.join(BIN, "worker-cli.exe" if os.name == "nt" else "worker-cli")

WORKER_PORT, SCHED_PORT = 50961, 50951


def generate_stubs():
    from grpc_tools import protoc
    os.makedirs(GEN, exist_ok=True)
    rc = protoc.main(["protoc", f"-I{os.path.join(ROOT, 'proto')}", f"--python_out={GEN}",
                      f"--grpc_python_out={GEN}", os.path.join(ROOT, "proto", "ar", "v1", "detection.proto")])
    if rc != 0:
        sys.exit("protoc failed")
    sys.path.insert(0, GEN)


generate_stubs()
from ar.v1 import detection_pb2 as pb  # noqa: E402
from ar.v1 import detection_pb2_grpc as pbg  # noqa: E402


# ------------------------------------------------------------------ fake Java scheduler

class FakeScheduler(pbg.WorkerControlServicer):
    def __init__(self):
        self.beats = []

    def Heartbeat(self, request, context):
        self.beats.append((time.time(), request))
        return pb.HeartbeatAck(accepted=True)


def start_scheduler(fake):
    server = grpc.server(cf.ThreadPoolExecutor(max_workers=4))
    pbg.add_WorkerControlServicer_to_server(fake, server)
    server.add_insecure_port(f"127.0.0.1:{SCHED_PORT}")
    server.start()
    return server


# ------------------------------------------------------------------ helpers

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))


def start_worker(log_lines):
    flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    proc = subprocess.Popen(
        [SERVER, "--model", MODEL, "--port", str(WORKER_PORT), "--worker-id", "worker-test",
         "--threads", "2", "--max-queue", "2", "--scheduler", f"127.0.0.1:{SCHED_PORT}"],
        stderr=subprocess.PIPE, stdout=subprocess.DEVNULL, text=True, creationflags=flags)
    ready = threading.Event()

    def pump():
        for line in proc.stderr:
            log_lines.append(line.rstrip())
            if "READY" in line:
                ready.set()

    threading.Thread(target=pump, daemon=True).start()
    if not ready.wait(60):
        proc.kill()
        sys.exit("worker-server did not become READY:\n" + "\n".join(log_lines))
    return proc


def frame(fid, data, models=("default",)):
    return pb.Frame(session_id="test", frame_id=fid, captured_at_ms=int(time.time() * 1000),
                    jpeg=data, models=list(models))


def main():
    os.chdir(ROOT)
    images = sorted(glob.glob("testdata/*/*.png") + glob.glob("testdata/*/*.jpg"))
    images = [p.replace("\\", "/") for p in images]

    fake = FakeScheduler()
    sched = start_scheduler(fake)
    log = []
    proc = start_worker(log)
    stub = pbg.InferenceWorkerStub(grpc.insecure_channel(f"127.0.0.1:{WORKER_PORT}"))
    print(f"worker-server started: {log[0] if log else ''}\n")

    # 1. heartbeats -------------------------------------------------------------
    print("1. heartbeats")
    time.sleep(2.5)
    hb = fake.beats[-1][1] if fake.beats else None
    check("heartbeats received", len(fake.beats) >= 2, f"{len(fake.beats)} in 2.5 s")
    if hb:
        check("heartbeat fields", hb.worker_id == "worker-test" and hb.address == f"127.0.0.1:{WORKER_PORT}"
              and list(hb.models) == ["default"] and hb.threads == 2,
              f"id={hb.worker_id} addr={hb.address} models={list(hb.models)} threads={hb.threads}")

    # 2. same results as worker-cli ---------------------------------------------
    print("\n2. gRPC Detect == worker-cli, on every test image")
    cli = subprocess.run([CLI, "--model", MODEL, *images], capture_output=True, text=True, check=True).stdout
    expected = {json.loads(l)["file"]: json.loads(l) for l in cli.splitlines() if l.strip()}
    all_same, total_dets, infer = True, 0, []
    for i, path in enumerate(images):
        with open(path, "rb") as f:
            res = stub.Detect(frame(i, f.read()), timeout=10)
        exp = expected[path]["detections"]
        got = list(res.detections)
        same = res.status == pb.OK and len(got) == len(exp) and all(
            g.label == e["label"] and abs(g.confidence - e["confidence"]) < 1e-5
            and all(abs(getattr(g, k) - e[k]) < 1e-5 for k in ("x1", "y1", "x2", "y2"))
            for g, e in zip(got, exp))
        all_same &= same and res.frame_id == i and res.worker_id == "worker-test"
        total_dets += len(got)
        infer.append(res.infer_ms)
        if not same:
            print(f"     mismatch on {path}: {len(got)} vs {len(exp)}")
    check(f"{len(images)} images, {total_dets} detections identical", all_same,
          f"infer_ms {min(infer)}-{max(infer)}")

    # 3. bad bytes ----------------------------------------------------------------
    print("\n3. bad bytes")
    res = stub.Detect(frame(100, b"not an image at all"), timeout=10)
    check("garbage -> BAD_FRAME", res.status == pb.BAD_FRAME, res.error)
    res = stub.Detect(frame(101, b""), timeout=10)
    check("empty -> BAD_FRAME", res.status == pb.BAD_FRAME, res.error)
    with open(images[0], "rb") as f:
        res = stub.Detect(frame(102, f.read()), timeout=10)
    check("still serving after bad frames", res.status == pb.OK)

    # 4. unknown model -------------------------------------------------------------
    print("\n4. unknown model")
    res = stub.Detect(frame(200, b"x", models=["fire"]), timeout=10)
    check("models=['fire'] -> BAD_FRAME", res.status == pb.BAD_FRAME and "fire" in res.error, res.error)

    # 5. overload --------------------------------------------------------------------
    print("\n5. overload: 10 frames at once (1 running + 2 queued allowed)")
    with open("testdata/land/cow-attack-01.png", "rb") as f:
        cow = f.read()
    futures = [stub.Detect.future(frame(300 + i, cow), timeout=30) for i in range(10)]
    ok = rejected = other = 0
    for fu in futures:
        try:
            ok += fu.result().status == pb.OK
        except grpc.RpcError as e:
            if e.code() == grpc.StatusCode.RESOURCE_EXHAUSTED:
                rejected += 1
            else:
                other += 1
    check("some rejected with RESOURCE_EXHAUSTED, the rest OK", rejected >= 1 and ok >= 1 and other == 0,
          f"ok={ok} rejected={rejected} other={other}")

    # 6. deadlines ---------------------------------------------------------------------
    print("\n6. tiny deadlines (60 ms, inference takes longer)")
    futures = [stub.Detect.future(frame(400 + i, cow), timeout=0.06) for i in range(3)]
    codes = []
    for fu in futures:
        try:
            fu.result()
            codes.append("OK")
        except grpc.RpcError as e:
            codes.append(e.code().name)
    check("all 3 -> DEADLINE_EXCEEDED", codes.count("DEADLINE_EXCEEDED") == 3, ", ".join(codes))
    time.sleep(1.0)
    res = stub.Detect(frame(410, cow), timeout=10)
    check("normal frame works right after", res.status == pb.OK, f"{len(res.detections)} cows")

    # 7. scheduler down, then back -------------------------------------------------------
    print("\n7. scheduler goes down, then comes back")
    sched.stop(0).wait()
    time.sleep(2.5)
    res = stub.Detect(frame(500, cow), timeout=10)
    check("worker keeps serving with no scheduler", res.status == pb.OK)
    check("worker logged that the scheduler is unreachable", any("unreachable" in l for l in log))
    n_before = len(fake.beats)
    sched = start_scheduler(fake)
    time.sleep(3.0)
    check("heartbeats resume when the scheduler is back", len(fake.beats) > n_before,
          f"{len(fake.beats) - n_before} new heartbeats")

    # 8. clean shutdown ---------------------------------------------------------------
    print("\n8. clean shutdown")
    if os.name == "nt":
        proc.send_signal(signal.CTRL_BREAK_EVENT)
    else:
        proc.send_signal(signal.SIGINT)
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
    time.sleep(0.3)
    final = next((l for l in reversed(log) if "stopped." in l), "")
    check("stopped cleanly with final counters", proc.returncode == 0 and bool(final), final.split("] ")[-1])
    m = re.search(r"skipped_deadline=(\d+)", final)
    check("queued frames past their deadline were skipped, not computed",
          bool(m) and int(m.group(1)) >= 1, f"skipped_deadline={m.group(1) if m else '?'}")
    sched.stop(0)

    print("\nworker log:")
    for line in log:
        print("   ", line)
    print("\nOVERALL:", "PASS" if all(results) else "FAIL", f"({sum(results)}/{len(results)})")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
