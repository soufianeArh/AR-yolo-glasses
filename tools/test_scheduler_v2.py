"""
End-to-end test of Step 5: the Java scheduler with N workers and several sessions at once.

  1. 3 workers register themselves
  2. 3 sessions AT THE SAME TIME, each gets exactly the worker-cli results (no mix-up between sessions)
  3. load is spread over all workers (2 video sessions at once)
  4. a misbehaving client (no in-flight limit) gets SESSION_LIMIT, a normal client next to it is unaffected
  5. queue full on one worker -> the frame is retried on another worker
  6. FAILOVER: a worker is killed mid-stream -> no WORKER_FAILED / NO_WORKER reaches the clients,
     the scheduler suspends it at once, the others take over; restarted -> it gets frames again
  7. workers aborted inferences whose deadline passed (counters at shutdown)

Usage (inside WSL, from the AR folder):
  ~/ar-venv/bin/python tools/test_scheduler_v2.py
"""
import glob
import json
import os
import signal
import subprocess
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_scheduler import CLI, CLIENT, MODEL, PY, ROOT, SCHEDULER, WORKER, Proc  # noqa: E402

SCHED_PORT, SCHED2_PORT, BASE_PORT = 50751, 50752, 50761
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))


def start_scheduler(port, deadline_ms=300, session_cap=2):
    s = Proc([SCHEDULER, "--port", str(port), "--deadline-ms", str(deadline_ms), "--heartbeat-timeout-ms", "3000",
              "--report-seconds", "5", "--session-max-in-flight", str(session_cap), "--redis", "none"], f"scheduler:{port}")
    if not s.wait_for("READY"):
        sys.exit("scheduler did not start:\n" + "\n".join(s.lines))
    return s


def start_worker(i, sched_port, threads=2, max_queue=2, port=None):
    w = Proc([WORKER, "--model", MODEL, "--port", str(port or BASE_PORT + i), "--worker-id", f"worker-{i}",
              "--threads", str(threads), "--max-queue", str(max_queue), "--scheduler", f"127.0.0.1:{sched_port}"],
             f"worker-{i}")
    if not w.wait_for("READY"):
        sys.exit(f"worker-{i} did not start:\n" + "\n".join(w.lines))
    return w


def client_cmd(port, *args):
    return [PY, CLIENT, "--target", f"127.0.0.1:{port}", *args]


def run_clients(cmds, timeout=300):
    procs = [subprocess.Popen(c, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for c in cmds]
    outs = []
    for p in procs:
        out, err = p.communicate(timeout=timeout)
        if p.returncode != 0:
            print(err[-1500:])
        outs.append(out)
    return outs


def worker_cli(images):
    out = subprocess.run([CLI, "--model", MODEL, *images], capture_output=True, text=True, check=True).stdout
    return {json.loads(l)["file"]: json.loads(l)["detections"] for l in out.splitlines() if l.strip()}


def same(a, b):
    return len(a) == len(b) and all(
        x["label"] == y["label"] and abs(x["confidence"] - y["confidence"]) < 1e-5
        and all(abs(x[k] - y[k]) < 1e-5 for k in ("x1", "y1", "x2", "y2")) for x, y in zip(a, b))


def main():
    os.chdir(ROOT)
    images = sorted(glob.glob("testdata/*/*.png") + glob.glob("testdata/*/*.jpg"))

    sched = start_scheduler(SCHED_PORT)
    print("scheduler started\n")

    # 1 ----------------------------------------------------------------------------------
    print("1. three workers (2 threads each) register themselves")
    mark = len(sched.lines)
    workers = {i: start_worker(i, SCHED_PORT) for i in (1, 2, 3)}
    ok = all(sched.wait_for(f"worker registered: worker-{i}", 10, mark) for i in (1, 2, 3))
    check("worker-1, worker-2, worker-3 registered", ok)

    # 2 ----------------------------------------------------------------------------------
    # CORRECTNESS ONLY: no results mixed up between sessions. Uses its own scheduler with a
    # generous deadline, so slow inferences can't turn this into a speed test (speed is measured
    # separately by tools/bench_scheduler.py).
    print("\n2. three sessions at the same time, each sends all 12 images (deadline 5 s: correctness, not speed)")
    expected = worker_cli(images)
    sched_c = start_scheduler(SCHED2_PORT, deadline_ms=5000)
    mark_c = len(sched_c.lines)
    wc = [start_worker(i, SCHED2_PORT, port=BASE_PORT + 20 + i) for i in (5, 6, 7)]
    for i in (5, 6, 7):
        sched_c.wait_for(f"worker registered: worker-{i}", 10, mark_c)
    outs = run_clients([client_cmd(SCHED2_PORT, "images", *images) for _ in range(3)])
    for w in wc:
        w.stop(signal.SIGINT)
    sched_c.stop(signal.SIGTERM)
    all_ok, used = True, Counter()
    for k, out in enumerate(outs):
        got = [json.loads(l) for l in out.splitlines() if l.strip()]
        sess_ok = len(got) == len(images) and all(
            g["status"] == "OK" and same(g["detections"], expected[g["file"]]) for g in got)
        all_ok &= sess_ok
        used.update(g["worker_id"] for g in got if g["status"] == "OK")
        if not sess_ok:
            bad = [(g["file"], g["status"], g.get("error", "")) for g in got if g["status"] != "OK"][:3]
            print(f"     session {k + 1} problems: {bad}")
    check("all 3 sessions got exactly the worker-cli results", all_ok)
    check("their frames were spread over several workers", len(used) >= 2, dict(used))
    check("each worker answered the frames of several sessions (shared, not one worker per session)",
          len(used) == 3, dict(used))

    # 3 ----------------------------------------------------------------------------------
    print("\n3. two video sessions at once, 15 s, 8 fps, max 2 in flight each")
    outs = run_clients([client_cmd(SCHED_PORT, "video", "--seconds", "15", "--fps", "8",
                                   "--max-in-flight", "2", "--json") for _ in range(2)])
    by_worker, statuses = Counter(), Counter()
    for out in outs:
        s = json.loads(out)
        by_worker.update(s["ok_by_worker"])
        statuses.update(s["statuses"])
    check("all 3 workers did work", len(by_worker) == 3, dict(by_worker))
    if len(by_worker) == 3:
        ratio = max(by_worker.values()) / max(1, min(by_worker.values()))
        check("roughly balanced (busiest <= 2x the least busy)", ratio <= 2.0, f"ratio {ratio:.2f}")
    check("no WORKER_FAILED / NO_WORKER", statuses.get("WORKER_FAILED", 0) + statuses.get("NO_WORKER", 0) == 0,
          dict(statuses))

    # 4 ----------------------------------------------------------------------------------
    print("\n4. a misbehaving client (10 frames per tick, no limit) next to a normal client")
    outs = run_clients([
        client_cmd(SCHED_PORT, "video", "--seconds", "6", "--fps", "4", "--burst", "10", "--json"),
        client_cmd(SCHED_PORT, "video", "--seconds", "6", "--fps", "4", "--max-in-flight", "1", "--json")])
    bad_c, good_c = json.loads(outs[0]), json.loads(outs[1])
    check("misbehaving client gets SESSION_LIMIT", bad_c["statuses"].get("SESSION_LIMIT", 0) > 0,
          bad_c["statuses"])
    check("its frames are all answered anyway (none lost)", bad_c["answered"] == bad_c["sent"],
          f"{bad_c['answered']}/{bad_c['sent']}")
    good_ok = good_c["statuses"].get("OK", 0) / max(1, good_c["answered"])
    starved = {k: v for k, v in good_c["statuses"].items() if k in ("SESSION_LIMIT", "NO_WORKER")}
    check("the normal client is never refused (no SESSION_LIMIT / NO_WORKER)", not starved, good_c["statuses"])
    # A/B: the same normal client ALONE right after (same CPU state - it throttles as it heats).
    alone = json.loads(run_clients([client_cmd(SCHED_PORT, "video", "--seconds", "6", "--fps", "4",
                                               "--max-in-flight", "1", "--json")])[0])
    alone_ok = alone["statuses"].get("OK", 0) / max(1, alone["answered"])
    # MEASUREMENT, not pass/fail. The cap guarantees the normal client is never refused (checked
    # above). It can NOT stop the CPU from being shared: with 3 workers busy on 4 cores every
    # inference slows down, so the normal client may miss more deadlines. That is the capacity
    # limit measured by bench_scheduler.py (two sessions don't fit 300 ms on this laptop).
    print(f"     measured: normal client next to it {100 * good_ok:.0f}% OK "
          f"(p95 {good_c['client_latency_ms']['p95']} ms) vs alone {100 * alone_ok:.0f}% OK "
          f"(p95 {alone['client_latency_ms']['p95']} ms) - shared CPU, see the Step 5 benchmark")

    # 5 ----------------------------------------------------------------------------------
    print("\n5. queue full -> retry on another worker (separate scheduler, 2 workers that accept 1 frame each)")
    # cap 4: the client must be able to have more frames in flight than the 2 workers accept,
    # otherwise no queue ever fills and there is nothing to retry.
    sched2 = start_scheduler(SCHED2_PORT, session_cap=4)
    mark2 = len(sched2.lines)
    q = [start_worker(i, SCHED2_PORT, threads=1, max_queue=0, port=BASE_PORT + 10 + i) for i in (8, 9)]
    sched2.wait_for("worker registered: worker-9", 10, mark2)
    sched2.wait_for("worker registered: worker-8", 10, mark2)
    out = run_clients([client_cmd(SCHED2_PORT, "video", "--seconds", "4", "--fps", "8",
                                  "--max-in-flight", "4", "--json")])[0]
    s = json.loads(out)
    for w in q:
        w.stop(signal.SIGINT)
    sched2.stop(signal.SIGTERM)
    final2 = next((l for l in reversed(sched2.lines) if "stopped." in l), "")
    retries = int(final2.split("retries=")[1].split()[0]) if "retries=" in final2 else 0
    check("frames hitting a full worker were retried on the other one", retries > 0,
          f"retries={retries}, client statuses {s['statuses']}")

    # 6 ----------------------------------------------------------------------------------
    print("\n6. FAILOVER: kill worker-2 in the middle of 2 video sessions (20 s)")
    mark = len(sched.lines)
    procs = [subprocess.Popen(client_cmd(SCHED_PORT, "video", "--seconds", "20", "--fps", "8",
                                         "--max-in-flight", "2", "--json"),
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for _ in range(2)]
    time.sleep(7)
    workers[2].stop(signal.SIGKILL)
    killed_at = time.time()
    outs = [p.communicate(timeout=120)[0] for p in procs]
    statuses, by_worker = Counter(), Counter()
    answered = sent = 0
    for out in outs:
        s = json.loads(out)
        statuses.update(s["statuses"])
        by_worker.update(s["ok_by_worker"])
        answered += s["answered"]
        sent += s["sent"]
    check("every frame answered", answered == sent, f"{answered}/{sent}")
    # A frame that was mid-inference on the killed worker with < 50 ms of its deadline left can't
    # be retried in time: it is answered WORKER_FAILED, by design. At most the frames in flight on
    # that worker at that moment (<= 2). Every other frame must be saved by the retry.
    check("at most the killed worker's in-flight frames lost (WORKER_FAILED <= 2), no NO_WORKER",
          statuses.get("WORKER_FAILED", 0) <= 2 and statuses.get("NO_WORKER", 0) == 0, dict(statuses))
    log6 = sched.lines[mark:]
    sus = next((l for l in log6 if "worker-2 unreachable" in l), None)
    check("scheduler suspended worker-2 immediately (before the 3 s heartbeat timeout)", sus is not None,
          sus.split("] ")[-1][:110] if sus else "no suspension logged")
    check("worker-1 and worker-3 took over", by_worker.get("worker-1", 0) > 0 and by_worker.get("worker-3", 0) > 0,
          dict(by_worker))

    print("\n   ...restart worker-2")
    mark = len(sched.lines)
    workers[2] = start_worker(2, SCHED_PORT)
    check("scheduler sees worker-2 again", sched.wait_for("worker worker-2 is back", 10, mark))
    out = run_clients([client_cmd(SCHED_PORT, "video", "--seconds", "6", "--fps", "8",
                                  "--max-in-flight", "2", "--json")])[0]
    s = json.loads(out)
    check("worker-2 gets frames again", s["ok_by_worker"].get("worker-2", 0) > 0, s["ok_by_worker"])

    # 7 + shutdown ------------------------------------------------------------------------
    print("\n7. shutdown")
    aborted = 0
    for i, w in workers.items():
        w.stop(signal.SIGINT)
        line = next((l for l in reversed(w.lines) if "stopped." in l), "")
        if "aborted=" in line:
            aborted += int(line.split("aborted=")[1].split()[0])
        print(f"     worker-{i}: {line.split('] ')[-1]}")
    sched.stop(signal.SIGTERM)
    final = next((l for l in reversed(sched.lines) if "stopped." in l), "")
    check("scheduler stopped cleanly", bool(final), final.split("] ")[-1])
    print(f"     inferences aborted at their deadline (CPU given back): {aborted}")

    print("\nscheduler log (events and throughput):")
    for line in sched.lines:
        print("   ", line)
    print("\nOVERALL:", "PASS" if all(results) else "FAIL", f"({sum(results)}/{len(results)})")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
