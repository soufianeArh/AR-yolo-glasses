import argparse
import glob
import json
import os
import signal
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOME = os.path.expanduser("~")
SCHEDULER = os.path.join(ROOT, "scheduler-java", "build", "install", "scheduler-java", "bin", "scheduler-java")
BIN = os.environ.get("AR_BIN") or os.path.join(HOME, "ar-build", "worker-cpp")
WORKER = os.path.join(BIN, "worker-server")
CLI = os.path.join(BIN, "worker-cli")
MODEL = os.path.join(ROOT, "models", "yolov8n.onnx")
PY = sys.executable
CLIENT = os.path.join(ROOT, "tools", "fake_client.py")

SCHED_PORT, WORKER_PORT = 50851, 50861
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))


class Proc:
    def __init__(self, cmd, name):
        self.name, self.lines, self.cond = name, [], threading.Condition()
        self.p = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
                                  start_new_session=True)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.p.stderr:
            with self.cond:
                self.lines.append(line.rstrip())
                self.cond.notify_all()

    def wait_for(self, text, timeout=60, start=0):
        deadline = time.time() + timeout
        with self.cond:
            while True:
                if any(text in l for l in self.lines[start:]):
                    return True
                left = deadline - time.time()
                if left <= 0 or self.p.poll() is not None:
                    return False
                self.cond.wait(left)

    def stop(self, sig=signal.SIGTERM, timeout=15):
        if self.p.poll() is None:
            os.killpg(self.p.pid, sig)
            try:
                self.p.wait(timeout)
            except subprocess.TimeoutExpired:
                os.killpg(self.p.pid, signal.SIGKILL)
                self.p.wait()
        time.sleep(0.3)
        return self.p.returncode


# real c++ workers and not mocks
def start_worker():
    w = Proc([WORKER, "--model", MODEL, "--port", str(WORKER_PORT), "--worker-id", "worker-1",
              "--threads", "4", "--max-queue", "2", "--scheduler", f"127.0.0.1:{SCHED_PORT}"], "worker")
    if not w.wait_for("READY"):
        sys.exit("worker-server did not start:\n" + "\n".join(w.lines))
    return w


def client(*args):
    out = subprocess.run([PY, CLIENT, "--target", f"127.0.0.1:{SCHED_PORT}", *args],
                         capture_output=True, text=True, timeout=300)
    if out.returncode != 0:
        print(out.stderr[-2000:])
    return out.stdout


def video(seconds, fps, in_flight):
    return json.loads(client("video", "--seconds", str(seconds), "--fps", str(fps),
                             "--max-in-flight", str(in_flight), "--json"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=20)
    args = ap.parse_args()
    os.chdir(ROOT)
    images = sorted(glob.glob("testdata/*/*.png") + glob.glob("testdata/*/*.jpg"))

    sched = Proc([SCHEDULER, "--port", str(SCHED_PORT), "--deadline-ms", "300",
                  "--heartbeat-timeout-ms", "3000", "--report-seconds", "5", "--redis", "none"], "scheduler")
    if not sched.wait_for("READY"):
        sys.exit("scheduler did not start:\n" + "\n".join(sched.lines))
    print("scheduler started:", sched.lines[-1].split("] ")[-1], "\n")

    print("1. no worker registered yet")
    out = [json.loads(l) for l in client("images", images[0], images[1]).splitlines() if l.strip()]
    check("frames answered NO_WORKER", len(out) == 2 and all(o["status"] == "NO_WORKER" for o in out),
          out[0].get("error", "") if out else "no answer")

    print("\n2. worker starts and registers through its heartbeat")
    mark = len(sched.lines)
    worker = start_worker()
    check("scheduler logged the registration", sched.wait_for("worker registered: worker-1", 10, mark),
          next((l.split("] ")[-1] for l in sched.lines[mark:] if "registered" in l), ""))

    print("\n3. results through the scheduler == worker-cli, on every test image")
    expected = {}
    for line in subprocess.run([CLI, "--model", MODEL, *images], capture_output=True, text=True,
                               check=True).stdout.splitlines():
        if line.strip():
            j = json.loads(line)
            expected[j["file"]] = j["detections"]
    got = [json.loads(l) for l in client("images", *images).splitlines() if l.strip()]
    resent = 0
    for k, g in enumerate(got):
        tries = 0
        while g["status"] == "DROPPED_DEADLINE" and tries < 3:
            g = json.loads(client("images", g["file"]).splitlines()[0])
            tries += 1
            resent += 1
        got[k] = g
    if resent:
        print(f"     {resent} frame(s) missed the deadline and were resent")
    same, n_dets, overhead = True, 0, []
    for g in got:
        exp = expected[g["file"]]
        d = g.get("detections", [])
        ok = g["status"] == "OK" and len(d) == len(exp) and all(
            a["label"] == b["label"] and abs(a["confidence"] - b["confidence"]) < 1e-5
            and all(abs(a[k] - b[k]) < 1e-5 for k in ("x1", "y1", "x2", "y2")) for a, b in zip(d, exp))
        same &= ok and g["worker_id"] == "worker-1"
        n_dets += len(d)
        if g["status"] == "OK":
            overhead.append(g["backend_ms"] - g["infer_ms"])
        if not ok:
            print(f"     mismatch: {g['file']} status={g['status']} {g.get('error', '')}")
    check(f"{len(got)} images, {n_dets} detections identical", same and len(got) == len(images),
          f"scheduler overhead (backend_ms - infer_ms): {min(overhead)}-{max(overhead)} ms" if overhead else "")

    runs = {}
    for step, in_flight in ((4, 1), (5, 2)):
        print(f"\n{step}. video for {args.seconds:.0f} s, camera 8 fps, max {in_flight} frame(s) in flight")
        s = video(args.seconds, 8, in_flight)
        runs[in_flight] = s
        st = s["statuses"]
        bad = {k: v for k, v in st.items() if k not in ("OK", "DROPPED_DEADLINE")}
        print(f"     sent {s['sent']}/{s['frames_captured']} (client dropped {s['dropped_on_client']}), "
              f"answered {s['answered']}, statuses {st}")
        print(f"     OK throughput {s['ok_fps']} fps | client latency p50/p95/p99 = "
              f"{s['client_latency_ms']['p50']}/{s['client_latency_ms']['p95']}/{s['client_latency_ms']['p99']} ms"
              f" | backend p50/p95 = {s['backend_ms']['p50']}/{s['backend_ms']['p95']} ms | labels {s['top_labels']}")
        check("every sent frame answered", s["answered"] == s["sent"])
        if in_flight == 1:
            check("no errors other than OK / DROPPED_DEADLINE", not bad, str(bad) if bad else "")
            check("almost all OK (>= 95%)", st.get("OK", 0) >= 0.95 * max(1, s["answered"]),
                  f"{st.get('OK', 0)}/{s['answered']}")
        else:
            allowed = {"OK", "DROPPED_DEADLINE", "NO_WORKER"}
            check("only expected statuses (OK / DROPPED_DEADLINE / queue full)", set(st) <= allowed, str(st))
            ok_pct = 100 * st.get("OK", 0) / max(1, s["answered"])
            print(f"     measured: {ok_pct:.0f}% OK with ONE worker and 2 frames in flight (deadline 300 ms)")

    print("\n6. worker killed (no clean exit, like a crash)")
    mark = len(sched.lines)
    worker.stop(signal.SIGKILL)
    out = [json.loads(l) for l in client("images", images[0]).splitlines() if l.strip()]
    check("frame right after the crash is answered (not hung)",
          len(out) == 1 and out[0]["status"] in ("WORKER_FAILED", "NO_WORKER"),
          f"{out[0]['status']}: {out[0].get('error', '')}" if out else "no answer")
    check("scheduler stops using the worker (suspended or unhealthy)",
          sched.wait_for("suspended", 6, mark) or sched.wait_for("unhealthy", 6, mark))
    out = [json.loads(l) for l in client("images", images[0]).splitlines() if l.strip()]
    check("then answers NO_WORKER", len(out) == 1 and out[0]["status"] == "NO_WORKER")
    check("scheduler still running", sched.p.poll() is None)

    print("\n7. worker restarted")
    mark = len(sched.lines)
    worker = start_worker()
    check("scheduler sees it again", sched.wait_for("is back", 10, mark))
    out = [json.loads(l) for l in client("images", "testdata/land/cow-attack-01.png").splitlines() if l.strip()]
    check("frames OK again", len(out) == 1 and out[0]["status"] == "OK",
          (f"{len(out[0]['detections'])} cows" if out[0]["status"] == "OK"
           else f"{out[0]['status']}: {out[0].get('error', '')}") if out else "no answer")

    print("\n8. clean shutdown")
    worker.stop(signal.SIGINT)
    code = sched.stop(signal.SIGTERM)
    final = next((l for l in reversed(sched.lines) if "stopped." in l), "")
    check("scheduler stopped cleanly with totals", bool(final), final.split("] ")[-1])

    print("\nscheduler log (throughput reports and events):")
    for line in sched.lines:
        print("   ", line)
    print("\nOVERALL:", "PASS" if all(results) else "FAIL", f"({sum(results)}/{len(results)})")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
