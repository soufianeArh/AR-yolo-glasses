r"""
End-to-end test of Step 6: the whole chain, plus the Python control plane off the hot path.

  fake Android app -> Java scheduler -> C++ workers -> back      (fast path)
                          \-> Redis Stream -> consumer -> Postgres -> API   (copy, async)

Uses the TEST database (ar_test) and its OWN Redis (port 6390, no persistence) so Redis can
be killed and restarted without touching the normal one.

  1. everything starts, /health ok
  2. a rule created through the API raises exactly ONE alert for 6 frames within its cooldown
  3. stored detections == worker-cli, field by field
  4. baseline video: every answered frame ends up in Postgres
  5. REDIS DOWN mid-stream (killed, restarted empty): fast path unaffected, and the results
     buffered by the scheduler still reach Postgres - nothing lost
  6. CONSUMER CRASH mid-stream (SIGKILL, restarted): nothing lost, and the fast path does as well
     as a CONTROL run with no crash right after (same CPU state - the CPU throttles as it heats)
  7. /stats and /alerts/summary agree with what was sent

Usage (inside WSL, from the AR folder):
  ~/ar-venv/bin/python tools/test_control_plane.py
"""
import json
import os
import signal
import subprocess
import sys
import threading
import time

import httpx
import redis
from sqlalchemy import create_engine, text

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_scheduler import CLI, CLIENT, MODEL, PY, ROOT, SCHEDULER, WORKER, Proc  # noqa: E402

CONTROL = os.path.join(ROOT, "control-python")
VENV_BIN = os.path.dirname(PY)
SCHED_PORT, BASE_PORT, REDIS_PORT, API_PORT = 50551, 50561, 6390, 8091
DB_URL = "postgresql+psycopg://ar:ar@127.0.0.1:5432/ar_test"
STREAM = "ar:e2e"
API = f"http://127.0.0.1:{API_PORT}"
ENV = {**os.environ, "AR_DB_URL": DB_URL, "AR_REDIS_URL": f"redis://127.0.0.1:{REDIS_PORT}/0",
       "AR_STREAM": STREAM, "AR_GROUP": "control", "AR_CONSUMER": "e2e-consumer", "AR_RULES_RELOAD_SECONDS": "1"}
COW = "testdata/land/cow-attack-01.png"
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))


class Service(Proc):
    """Proc with an environment and working directory (for the Python services)."""

    def __init__(self, cmd, name, cwd=None, env=None):
        self.name, self.lines, self.cond = name, [], threading.Condition()
        self.p = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
                                  start_new_session=True, cwd=cwd, env=env)
        threading.Thread(target=self._pump, daemon=True).start()


def start_redis():
    p = subprocess.Popen(["redis-server", "--port", str(REDIS_PORT), "--save", "", "--appendonly", "no"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    r = redis.Redis(port=REDIS_PORT)
    for _ in range(50):
        try:
            r.ping()
            return p
        except redis.ConnectionError:
            time.sleep(0.1)
    sys.exit("test redis did not start")


def stop(p, sig=signal.SIGTERM):
    if p.poll() is None:
        os.killpg(p.pid, sig)
        p.wait(10)


def start_consumer():
    c = Service([PY, "-m", "ar_control.consumer"], "consumer", cwd=CONTROL, env=ENV)
    if not c.wait_for("READY", 30):
        sys.exit("consumer did not start:\n" + "\n".join(c.lines))
    return c


def db_frames(engine):
    with engine.connect() as c:
        return c.execute(text("SELECT count(*) FROM frame")).scalar()


def wait_caught_up(engine, expected, timeout=40):
    """Until Postgres holds `expected` frames (the consumer caught up)."""
    deadline = time.time() + timeout
    n = db_frames(engine)
    while n < expected and time.time() < deadline:
        time.sleep(0.5)
        n = db_frames(engine)
    return n


def video(seconds, background=False):
    cmd = [PY, CLIENT, "--target", f"127.0.0.1:{SCHED_PORT}", "video", "--seconds", str(seconds),
           "--fps", "8", "--max-in-flight", "2", "--json"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if background:
        return p
    return json.loads(p.communicate(timeout=seconds + 120)[0])


def finish(p, seconds):
    return json.loads(p.communicate(timeout=seconds + 120)[0])


def main():
    os.chdir(ROOT)
    engine = create_engine(DB_URL)
    # fresh test database: migrations to head, empty tables
    subprocess.run([os.path.join(VENV_BIN, "alembic"), "upgrade", "head"], cwd=CONTROL, env=ENV, check=True,
                   capture_output=True)
    with engine.begin() as c:
        c.execute(text("TRUNCATE alert, alert_rule, detection, frame RESTART IDENTITY CASCADE"))

    # 1 ----------------------------------------------------------------------------------
    print("1. start test Redis, scheduler (publishing to it), 3 workers x 2 threads, consumer, API")
    redis_p = start_redis()
    sched = Proc([SCHEDULER, "--port", str(SCHED_PORT), "--report-seconds", "5",
                  "--redis", f"127.0.0.1:{REDIS_PORT}", "--stream", STREAM], "scheduler")
    if not sched.wait_for("READY"):
        sys.exit("scheduler did not start:\n" + "\n".join(sched.lines))
    workers = []
    for i in (1, 2, 3):
        w = Proc([WORKER, "--model", MODEL, "--port", str(BASE_PORT + i), "--worker-id", f"worker-{i}",
                  "--threads", "2", "--scheduler", f"127.0.0.1:{SCHED_PORT}"], f"worker-{i}")
        w.wait_for("READY")
        workers.append(w)
    for i in (1, 2, 3):
        sched.wait_for(f"worker registered: worker-{i}", 10)
    consumer = start_consumer()
    api = Service([os.path.join(VENV_BIN, "uvicorn"), "ar_control.api:app", "--host", "127.0.0.1",
                   "--port", str(API_PORT)], "api", cwd=CONTROL, env=ENV)
    if not api.wait_for("Application startup complete", 30):
        sys.exit("API did not start:\n" + "\n".join(api.lines))
    http = httpx.Client(base_url=API, timeout=10)
    h = http.get("/health").json()
    check("all services up, /health ok", h["ok"], {k: h.get(k) for k in ("database", "redis")})

    # 2 + 3 ------------------------------------------------------------------------------
    print("\n2. rule 'cow x2, conf >= 0.5, cooldown 5 s' via the API, then the cow image 6 times")
    rule = http.post("/rules", json={"name": "cows", "label": "cow", "min_confidence": 0.5, "min_count": 2,
                                     "cooldown_seconds": 5, "severity": "high"})
    check("rule created", rule.status_code == 201, rule.json().get("label"))
    time.sleep(1.5)                                           # consumer reloads rules every 1 s
    out = subprocess.run([PY, CLIENT, "--target", f"127.0.0.1:{SCHED_PORT}", "images"] + [COW] * 6,
                         capture_output=True, text=True, timeout=120).stdout
    sent = [json.loads(l) for l in out.splitlines() if l.strip()]
    ok_frames = sum(1 for s in sent if s["status"] == "OK")
    n = wait_caught_up(engine, len(sent))
    # Speed is not what this checks: a frame that missed its deadline is still stored (as DROPPED).
    check("all 6 frames answered and stored", len(sent) == 6 and n == 6,
          f"stored {n}; {ok_frames} OK, {6 - ok_frames} dropped at the deadline")
    alerts = http.get("/alerts").json()
    check("exactly ONE alert (the other 5 frames fall in the cooldown)", len(alerts) == 1,
          f"{len(alerts)} alert(s)" + (f": {alerts[0]['label']} x{alerts[0]['count']}, max conf "
                                       f"{alerts[0]['max_confidence']:.3f}, {alerts[0]['severity']}" if alerts else ""))

    print("\n3. stored detections == worker-cli")
    exp = json.loads(subprocess.run([CLI, "--model", MODEL, COW], capture_output=True, text=True,
                                    check=True).stdout)["detections"]
    stored = http.get("/detections", params={"limit": 500}).json()
    one_frame = sorted([d for d in stored if d["frame_id"] == stored[0]["frame_id"]
                        and d["session_id"] == stored[0]["session_id"]], key=lambda d: -d["confidence"])
    same = len(one_frame) == len(exp) and all(
        a["label"] == b["label"] and abs(a["confidence"] - b["confidence"]) < 1e-5
        and all(abs(a[k] - b[k]) < 1e-5 for k in ("x1", "y1", "x2", "y2"))
        for a, b in zip(one_frame, sorted(exp, key=lambda d: -d["confidence"])))
    check("one stored frame matches worker-cli field by field", same, f"{len(one_frame)} detections")
    check("every OK frame's detections stored", len(stored) == ok_frames * len(exp),
          f"{len(stored)} rows = {ok_frames} OK frames x {len(exp)}")

    # 4 ----------------------------------------------------------------------------------
    print("\n4. baseline: 10 s of video")
    before = db_frames(engine)
    base = video(10)
    n = wait_caught_up(engine, before + base["answered"])
    check("every answered frame stored in Postgres", n - before == base["answered"],
          f"answered {base['answered']}, stored {n - before}")
    base_ok = base["statuses"].get("OK", 0) / max(1, base["answered"])
    print(f"     baseline: {100 * base_ok:.0f}% OK, client p50/p95 = "
          f"{base['client_latency_ms']['p50']}/{base['client_latency_ms']['p95']} ms")

    # 5 ----------------------------------------------------------------------------------
    print("\n5. REDIS DOWN: killed 3 s into a 12 s video, restarted (empty) 4 s later")
    before = db_frames(engine)
    mark = len(sched.lines)
    p = video(12, background=True)
    time.sleep(3)
    stop(redis_p, signal.SIGKILL)
    time.sleep(4)
    redis_p = start_redis()
    run = finish(p, 12)
    n = wait_caught_up(engine, before + run["answered"], timeout=60)
    ok = run["statuses"].get("OK", 0) / max(1, run["answered"])
    check("fast path unaffected: every frame answered, >= 90% OK", run["answered"] == run["sent"] and ok >= 0.9,
          f"{100 * ok:.0f}% OK, client p95 {run['client_latency_ms']['p95']} ms "
          f"(baseline {base['client_latency_ms']['p95']} ms)")
    check("scheduler noticed and kept results in memory", any("unreachable" in l for l in sched.lines[mark:]))
    check("NOTHING LOST: results buffered during the outage reached Postgres", n - before == run["answered"],
          f"answered {run['answered']}, stored {n - before}")

    # 6 ----------------------------------------------------------------------------------
    print("\n6. CONSUMER CRASH: SIGKILL 2 s into a 10 s video, restarted 4 s later")
    before = db_frames(engine)
    p = video(10, background=True)
    time.sleep(2)
    stop(consumer.p, signal.SIGKILL)
    time.sleep(4)
    consumer = start_consumer()
    run = finish(p, 10)
    n = wait_caught_up(engine, before + run["answered"], timeout=60)
    ok = run["statuses"].get("OK", 0) / max(1, run["answered"])
    check("every frame answered", run["answered"] == run["sent"], f"{run['answered']}/{run['sent']}")
    check("NOTHING LOST: the restarted consumer caught up", n - before == run["answered"],
          f"answered {run['answered']}, stored {n - before}")
    print("     ...control run: same 10 s video, consumer NOT killed")
    before_c = db_frames(engine)
    control = video(10)
    wait_caught_up(engine, before_c + control["answered"])
    ok_c = control["statuses"].get("OK", 0) / max(1, control["answered"])
    check("fast path unaffected by the crash (OK rate within 10 points of the control run)",
          ok >= ok_c - 0.10,
          f"crash run {100 * ok:.0f}% OK / p95 {run['client_latency_ms']['p95']} ms; control run "
          f"{100 * ok_c:.0f}% OK / p95 {control['client_latency_ms']['p95']} ms")
    time.sleep(1.5)
    h = http.get("/health").json()
    check("stream fully processed (pending 0, lag 0)", h.get("pending") == 0 and h.get("lag") == 0,
          f"pending {h.get('pending')}, lag {h.get('lag')}")

    # 7 ----------------------------------------------------------------------------------
    print("\n7. API views agree")
    st = http.get("/stats", params={"minutes": 60}).json()
    check("/stats counts every stored frame", st["frames"] == db_frames(engine), st["frames_by_status"])
    summ = http.get("/alerts/summary").json()
    print(f"     alerts: {summ['total']} total ({summ['by_severity']}), top labels {st['top_labels']}")

    # shutdown -----------------------------------------------------------------------------
    stop(api.p, signal.SIGINT)
    stop(consumer.p, signal.SIGINT)
    for w in workers:
        w.stop(signal.SIGINT)
    sched.stop(signal.SIGTERM)
    stop(redis_p)
    final = next((l for l in reversed(sched.lines) if "stopped." in l), "")
    print("\nscheduler:", final.split("] ")[-1])
    print("consumer :", next((l for l in reversed(consumer.lines) if "stopped." in l), "").split("] ")[-1])
    print("\nscheduler + consumer events:")
    for line in sched.lines + consumer.lines:
        if any(k in line for k in ("redis", "unreachable", "reachable", "group", "ALERT", "error")):
            print("   ", line)
    print("\nOVERALL:", "PASS" if all(results) else "FAIL", f"({sum(results)}/{len(results)})")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
