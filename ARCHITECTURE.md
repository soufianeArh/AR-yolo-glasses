# AR Glasses Detection: Architecture (Stream-Only, v1)

Status: **Steps 0–6 built and tested**: shared contract, C++ inference workers, Java
scheduler with N workers (routing, retries, failover, per-session cap), benchmark, and the
Python control plane (Redis Stream → Postgres, rule engine, REST API).
The Android app is not started yet.
Progress and results per step: see `ROADMAP.md`.

This is the architecture for the first project idea, **Stream-Only** (see `README-AR.md`).
The upgrade, **Stream + Local Model**, reuses this same backend and adds on-device inference later.

This project is a **separate codebase** from `soufiane-easy` (MQ-AI). It reuses that project's
model weights and its detection format, and ports its alert-dedup ideas. It does not share
code with soufiane-easy or depend on it at runtime.

---

## 1. Where the Android app runs

The Goolton glasses run their own Android OS (WEBB OS), so the **Android app is installed on
the glasses** and talks straight to the backend over Wi-Fi.

- **Plan A (target):** Android app on the glasses → backend.
- **Plan B (fallback):** if the Goolton SDK blocks custom apps or raw camera access, the same
  Android app runs on a paired Android phone, which talks to the glasses over USB-C/DP.
  The chain becomes glasses ↔ Android app on the phone ↔ backend.
- **During development:** build and test the Android app on any normal Android phone with its
  own camera. Only the camera source and the display output change for the glasses.

Full data chain (Plan A):

**glasses camera → Android app (on glasses) → Java scheduler → C++ worker → Java scheduler → Android app → glasses display**
with a copy of every result sent **async** to the Python control plane.

---

## 2. Overall shape

```
┌──────────────── Glasses (WEBB OS) ────────────────┐
│  Android app (Kotlin)                              │
│  camera → resize 640 → JPEG → send                 │
│  receive result → popup overlay (hold + fade)      │
└───────────────┬─────────────────▲─────────────────┘
                │ gRPC stream      │ results
                ▼  (Wi-Fi)         │
┌──── Backend (one machine, Linux: WSL Ubuntu in dev) ┐
│                                                     │
│  Java scheduler ───gRPC──► C++ worker 1  (ONNX)    │
│   - device sessions        C++ worker 2  (ONNX)    │
│   - pick free worker       C++ worker N  (ONNX)    │
│   - deadlines, drop stale  ◄── heartbeats/load ──  │
│   - fast reply to the app                          │
│        │                                            │
│        │ copy of each result (async, never blocks) │
│        ▼                                            │
│   Redis Streams ──► Python control plane (FastAPI) │
│                      - rules / dedup / severity    │
│                      - history in Postgres         │
│                      - REST API (history, config)  │
└─────────────────────────────────────────────────────┘
```

---

## 3. Components

### 3.1 Android app (Kotlin)

- **Capture:** CameraX during development, the Goolton SDK camera on the glasses.
- **Pre-process on the device:** shrink each frame to about 640px and compress it to JPEG
  (around 40–50 KB), so less data travels over Wi-Fi.
- **Backpressure:** at most **1–2 frames in flight**. If the backend is slow, drop new
  frames instead of queuing them. Newest frame wins.
- **Display:** a fixed-position popup in the top-right corner.
  - Show it when a detection passes the confidence threshold.
  - Hold it for about 3 seconds, then fade it out.
  - This smoothing happens in the Android app, so the popup stays steady without
    waiting for the Python rule engine.
- **Transport:** one gRPC two-way stream to the Java scheduler per device session.
- **Identity:** a simple per-device token, sent in the gRPC `authorization` header when the
  stream opens.

### 3.2 Java scheduler (the core of the project) ✅ built (Steps 4–5)

Java 21 with plain grpc-java (no Spring), virtual threads, Gradle. Runs in WSL Ubuntu.
One gRPC port (default 50051) serves both the Android app (`FrameStream`) and the workers'
heartbeats (`WorkerControl`).

- **Sessions:** one per Android app stream. A session closes only after every pending result
  went out. Results may go back out of order; the Android app matches them by `frame_id`.
- **Worker registry:** built only from heartbeats. The **first heartbeat registers a worker**
  (address, models, threads), so no worker list is configured. The scheduler connects to a
  worker as soon as it registers, and reconnects immediately (skipping gRPC's back-off) when a
  heartbeat arrives after a failure.
- **Health:** unhealthy after 3 s without heartbeats. A worker found **unreachable** during a
  call is **suspended at once**; its next heartbeat makes it healthy again.
- **Routing:** the healthy worker with the **fewest frames in flight**; on a tie, the one picked
  longest ago. Pick + reservation are one atomic step. The in-flight count is accurate because
  workers abort an inference when its deadline passes (3.3).
- **Deadline:** 300 ms per frame by default (`--deadline-ms`), from the moment the scheduler
  receives it. Past it → `DROPPED_DEADLINE`, never answered late. See section 6 for 300 vs 400 ms.
- **Retry:** on queue full (`RESOURCE_EXHAUSTED`) or unreachable (`UNAVAILABLE`), **one** retry
  on another worker if at least 50 ms of the deadline is left. Never on a deadline miss.
- **Per-session cap:** 2 frames in flight (`--session-max-in-flight`); more → `SESSION_LIMIT` at
  once, so one buggy client can't starve the others.
- **Device token:** read from the `authorization` header, recorded, not checked yet (Step 7).
- **Fast path:** the result goes straight back to the Android app on the same stream.
- **Async copy to Redis:** every result goes into a bounded memory buffer (10,000) and one
  background thread writes the buffer to the Redis Stream `ar:results` in batches. The fast path
  never waits: Redis down → results wait in the buffer (tested: nothing lost over a 4 s outage);
  buffer full → oldest dropped and counted. `--redis none` turns it off.
- **Identity:** session ids are unique across scheduler restarts; the device id is a hash of the
  device token (`dev-xxxxxxxxxxxx`), the token itself is never logged or published.
- **Metrics:** every 5 s: frames/s, counts per status, retries, backend p50/p95/p99, and per
  worker: health, frames sent, in flight, average inference time.
- **Tests:** 9 unit tests on the routing rules (fake clock), end-to-end suites for Steps 4 and 5.

### 3.3 C++ inference workers ✅ built (Steps 2–3)

- Each worker is a small **gRPC server** (`worker-server`): it takes in a JPEG and returns a
  list of detections.
- **Engine:** ONNX Runtime 1.32, CPU only. **OpenCV** for JPEG decode and resize.
- **Model in v1:** `default` = COCO **YOLOv8n**, exported to ONNX (fixed 640×640 input) from
  the same weights used in soufiane-easy. `fire` is left out of v1 (a weak fit for AR, never
  tested on real fire, AGPL license). Class names are read from the `.onnx` file itself, so
  another YOLOv8 export works without code changes.
- **Pre/post-processing reproduces Ultralytics exactly:** letterbox (gray 114 padding),
  per-class NMS (IoU 0.5), confidence threshold 0.4, boxes normalized to 0–1. Verified:
  0.00000 confidence difference and 0.000 px box difference on all test images, on both the
  Windows build (OpenCV 5.0) and the Linux build (OpenCV 4.6).
- **Concurrency model:** **one inference at a time per worker process.**
  - Up to `--max-queue` calls (default 2) wait; beyond that the worker answers
    `RESOURCE_EXHAUSTED` right away.
  - A call whose deadline passed while it waited is **skipped, not computed**.
  - A call whose deadline passes **during** its inference is **aborted halfway** (ONNX Runtime
    `RunOptions::SetTerminate`, from a watcher thread polling every 5 ms). Either way, no CPU is
    spent on answers nobody is waiting for.
  - Scaling = **more worker processes**. Measured best on this laptop: **3 workers × 2 threads**
    (section 6).
- **Failure handling:** frame-level problems (bad image, unknown model) → gRPC OK with
  `FrameResult.status = BAD_FRAME`. Capacity/transport problems (queue full, deadline,
  crash) → a gRPC error status.
- **Health:** a heartbeat every second to the Java scheduler. If the scheduler is down, the
  worker keeps serving and keeps retrying; heartbeats resume when it comes back.
- **Hardware measured (Step 2):** Intel i7-8550U, **4 cores / 8 threads, 15 W laptop CPU**.
  One worker: ~6 FPS with 2 threads, ~10 FPS with 6 threads. 8 threads is slower than 6.
  Sustained runs are 30–50% slower than cold runs (the CPU throttles as it heats), so every
  measurement needs repeated runs and p95, not one number.
- **Build:** CMake, same `CMakeLists.txt` for Linux (GCC) and Windows (MSVC). The main build
  is **Linux, in WSL Ubuntu**, with gRPC/protobuf/OpenCV from `apt` (see section 5).
- **Output format** (same as soufiane-easy):
  `{label, confidence, x1, y1, x2, y2, model}`, boxes normalized to 0–1.

### 3.4 Python control plane ✅ built (Step 6)

`control-python/`: SQLAlchemy 2 + Alembic + psycopg 3, redis-py, FastAPI. Two separate
processes, so a slow API request can never delay storing results:

- **Consumer** (`python -m ar_control.consumer`):
  - Reads `ar:results` through a Redis **consumer group** (several consumers could share it).
  - One Postgres transaction per batch (up to 200 results), **acknowledged only after commit**:
    after a crash, unacknowledged results are processed again; the unique
    `(session_id, frame_id)` stores each one once. At-least-once delivery, exactly-once storage.
  - Recreates its group if Redis restarted empty; skips (and counts) malformed entries so one
    bad message can't block the stream; retries with back-off when Redis or Postgres is down.
- **Rule engine** (ported from soufiane-easy, logic not code), in order:
  1. threshold: `confidence >= rule.min_confidence`
  2. quorum: at least `min_count` hits in one frame
  3. cooldown: nothing from this rule for N seconds, **per (rule, device)**
  - Time = when the scheduler **received** the frame, so a backlog processed after an outage
    raises the same alerts it would have raised live.
  - Rules reloaded from the database every 5 s (the API edits them). Cold start reads the last
    alert from the database (no re-announcing after a restart).
- **Storage** (Postgres, migrations by Alembic): `frame` (every result, any status),
  `detection` (OK frames), `alert_rule`, `alert` (kept when its rule is deleted).
- **REST API** (`uvicorn ar_control.api:app`, port 8090): `/rules` CRUD with validation,
  `/alerts` (+ idempotent `/ack`, `/summary`), `/detections`, `/stats` (status counts,
  latency percentiles, per-worker share), `/health` (database, Redis, consumer pending/lag).
  A `/config` endpoint is left for Step 7, when something reads it.
- It is **off the hot path**: tested by killing Redis and the consumer mid-stream; the popup's
  fast path was unaffected and no result was lost.

---

## 4. Communication

| Link | Choice | Why |
|---|---|---|
| Android app ↔ Java scheduler | gRPC two-way streaming | One stream per device, low overhead, works on Android (grpc-okhttp) |
| Java scheduler → C++ workers | gRPC, one call per frame | Fast, typed, easy to time; a gRPC deadline on every call |
| C++ workers → Java scheduler | gRPC heartbeat, one small call per second | A dead worker simply stops calling; workers reconnect by themselves |
| Java scheduler → Python | Redis Stream `ar:results` + consumer group | Light enough for an 8 GB machine; consumer groups give at-least-once delivery. Entry = protobuf `FrameResult` + `device` + `received_ms`. Could move to Kafka later |
| All of them | **One shared `.proto` contract** | Kotlin, Java, C++ and Python all generate their code from the same definition |

The contract is `proto/ar/v1/detection.proto` (package `ar.v1`):

- `Frame { session_id, frame_id, captured_at_ms, jpeg, models[] }`
- `Detection { label, confidence, x1, y1, x2, y2, model }` (boxes normalized 0–1)
- `FrameResult { session_id, frame_id, status, detections[], worker_id, infer_ms, backend_ms, error }`
- `ResultStatus`: `OK`, `DROPPED_DEADLINE`, `WORKER_FAILED`, `NO_WORKER` (no healthy worker,
  or every worker tried had a full queue), `BAD_FRAME`, `SESSION_LIMIT` (added in Step 5)
- `WorkerHeartbeat { worker_id, address, models[], busy, queue_depth, avg_infer_ms, threads }`
- Services: `FrameStream.Stream` (Android app ↔ Java scheduler), `InferenceWorker.Detect`
  (Java scheduler → C++ worker), `WorkerControl.Heartbeat` (C++ worker → Java scheduler)

---

## 5. Project folder layout and runtime environment

```
AR/
  proto/            ← shared contract                                   ✅
  worker-cpp/       ← C++ worker (detector, worker-cli, worker-bench,
                      worker-server)                                    ✅
  tools/            ← export/compare/test scripts (Python)              ✅
  models/           ← yolov8n.pt + yolov8n.onnx (not in git)            ✅
  testdata/         ← sample images + loop.mp4 (not in git)             ✅
  third_party/      ← Windows-only: OpenCV 5.0 + ONNX Runtime (not in git)
  scheduler-java/   ← Java scheduler (Gradle)                            ✅
  scripts/          ← start-workers.sh / stop-workers.sh                ✅
  bench/            ← Step 5 benchmark results + graph                  ✅
  control-python/   ← Python control plane (consumer, rules, API, migrations, tests) ✅
  android-app/      ← Step 7
```

**The backend runs on Linux.** In development that's **WSL Ubuntu 24.04** on this PC; in
deployment, any Linux server. The source code stays in the Windows folder
(`/mnt/c/.../AR` from WSL); build output and dependencies live in the Linux filesystem.

| Component | Where it runs (dev) | Dependencies |
|---|---|---|
| C++ worker ✅ | WSL Ubuntu | `apt`: build-essential, cmake, ninja-build, libgrpc++-dev, protobuf-compiler-grpc, libprotobuf-dev, protobuf-compiler, libopencv-{core,imgproc,imgcodecs}-dev (~560 MB). ONNX Runtime 1.32 Linux in `~/ar-deps/onnxruntime`. Build output in `~/ar-build/worker-cpp` |
| Java scheduler ✅ | WSL Ubuntu | `apt`: openjdk-21-jdk-headless (~300 MB). Gradle 9.9.1 wrapper; gRPC-Java 1.85.1, protobuf 4.36.2, JUnit 6.1.3 in `~/.gradle` (~400 MB) |
| Python tools / control plane ✅ | WSL Ubuntu | Python 3.12, venv in `~/ar-venv` (grpcio, OpenCV, matplotlib, FastAPI, SQLAlchemy, Alembic, psycopg, redis-py, pytest; ~480 MB) |
| Redis ✅ | WSL Ubuntu | `apt`: redis-server 7 (~8 MB), started by systemd |
| Postgres ✅ | WSL Ubuntu | `apt`: PostgreSQL 16 (~175 MB), started by systemd. Role `ar` (local dev password `ar`), databases `ar` and `ar_test` (tests only). The Postgres 15 on Windows is not used |

**Why everything in WSL, not split between Windows and WSL:** this PC runs **Windows 10**,
where WSL2 uses a NAT network. Windows → WSL through `127.0.0.1` works, but **WSL → Windows
through `127.0.0.1` does not**. A C++ worker in WSL could not send heartbeats to a Java
scheduler running on Windows without extra setup (host IP, firewall rules). With the whole
backend in WSL, every component reaches the others on `127.0.0.1`, exactly like on a real
Linux server.

**Editors:** the code is still edited in the Windows folder. IntelliJ IDEA can use a JDK
inside WSL; Visual Studio can build and debug the CMake project in WSL; VS Code has a WSL mode.

**No Docker, no nginx.**
- The services run as **native Linux processes**. That keeps profiling, debugging and
  CPU/thread control direct, and keeps throughput measurements free of container CPU caps.
- **No reverse proxy:** the Java scheduler is the single entry point and the Android app
  connects to it directly over gRPC. TLS can be turned on in grpc-java if needed.
- Docker can be added later for packaging and deployment only.

The Windows build of the C++ worker (MSVC, `third_party/`) still works for `worker-cli` and
`worker-bench` (Visual Studio profiling). `worker-server` on Windows would need gRPC from
vcpkg: a ~1 h from-source build needing ~10 GB free disk, so it isn't set up.

---

## 6. Latency and capacity (measured)

| Step | Value |
|---|---|
| Wi-Fi upload of about 50 KB | 20–50 ms (estimate, not measured yet) |
| Scheduler overhead on top of inference | 5–113 ms measured (mostly waiting under load) |
| C++ worker: decode + preprocess + postprocess | ~10–15 ms |
| C++ worker: YOLOv8n inference on CPU (640) | ~75–160 ms alone; **250–300 ms each when 3 workers share the CPU at full load** |
| **One session (8 fps), 3 workers × 2 threads** | client latency p50 ~210 ms, p95 ~245 ms, **99% within 300 ms** |
| **Two sessions (16 fps asked)** | p50 ~270 ms, p95 ~380 ms, 74% within 300 ms, **96% within 400 ms** |

All measured on one laptop (Intel i7-8550U, 4 cores / 8 threads, 15 W), clients included,
no Wi-Fi. Full benchmark: `bench/summary.md`, `bench/throughput.png`.

**Capacity:** ~11 frames/s in total on this laptop, reached with **3 workers × 2 threads**.
More workers or threads than the 4 physical cores only make each inference slower.
- One pair of glasses at 8 fps fits the 300 ms deadline.
- Two pairs at the same time need either a 400 ms deadline (96% in time) or a bigger machine.
  **Open decision** (section 8).
- **Sustained load throttles this laptop hard** (measured in Step 6): after ~30 min of
  continuous load, 2 sessions got only ~24% of frames within 300 ms (74% when cooler). For long
  demos: one pair of glasses, a 400 ms deadline, or a machine with real cooling.

That's fine for a fixed popup. It's too slow for boxes drawn on objects, because the
wearer's head has moved by the time the result arrives. That's why boxes are left for
the Stream + Local Model version.

---

## 6b. Network / deployment

The glasses **must have a network connection**, and in practice that means **Wi-Fi**, unless
the chosen Goolton model turns out to have a SIM / 4G (not confirmed). The Wi-Fi does **not**
have to be the same network as the server. The Android app on the glasses only needs to be able
to reach the server's address.

| Setup | How it works | When to use it |
|---|---|---|
| **Same Wi-Fi (local network)** | Glasses and server on the same router. The Android app connects to the server's local IP, e.g. `192.168.1.20:50051` | **Development.** Simplest and lowest latency, no internet needed |
| **Server on the internet** | Cloud VM, or a PC with port forwarding, reached through a public IP or domain. The glasses use any Wi-Fi that has internet | A real deployment across sites. Needs TLS, and latency goes up |
| **VPN (e.g. Tailscale)** | Glasses and server join the same private network over the internet | Remote testing without opening ports. Only if the VPN app installs on WEBB OS |
| **Phone hotspot** | The glasses join a phone's 4G/5G hotspot, which reaches a server on the internet | **Farmland with no Wi-Fi.** Adds about 30–100 ms and can be unstable |
| **On-site server ("edge")** | A mini-PC or laptop with its own Wi-Fi router, brought to the site | **No internet at all.** Low latency, fully offline, but you carry and power the hardware |

Plan B (Android app on a paired phone): the glasses don't need Wi-Fi at all. The glasses
connect to the phone over USB-C, and the phone provides the network (Wi-Fi or 4G).

**Reaching the backend in WSL from the phone or glasses (development, Windows 10):** other
devices on the Wi-Fi can't reach WSL directly (WSL2 NAT). Windows has to forward the Java
scheduler's port into WSL (`netsh interface portproxy`) and allow it in Windows Firewall.
One-time setup, needed from Step 7. Not an issue on a real Linux server.

Rules:
- Never hard-code the server address in the Android app. Make it a setting (host + port).
- When there's no network, Stream-Only shows nothing. That's the known limitation. The fix is
  a hotspot, an on-site server, or later the Stream + Local Model upgrade.
- To verify on the real glasses: whether they can join a phone hotspot, whether a VPN app
  installs on WEBB OS, and whether the model has a SIM.

---

## 7. Reused from soufiane-easy (by porting or reusing data, not shared code)

- Model weights: COCO yolov8n (exported to ONNX here). `fire.pt` is available for later.
- Detection format: `{label, confidence, box}`.
- Rule-engine logic: threshold → quorum → cooldown.
- "Newest frame wins" frame handling.
- Sample farm footage (`samples/land`, `samples/drone`) for testing without the glasses.

---

## 8. Open questions

1. ~~Models in v1~~ **Decided:** COCO yolov8n only. `fire` and `plant` are left out of v1.
2. **Goolton SDK camera access:** this decides between Plan A and Plan B
   (see `README-AR.md` → "To be tested"). Still to test on the real glasses.
3. **What the glasses should detect:** COCO's classes are enough to build and test the whole
   pipeline, but probably not what farm workers need. Decide before Step 8, ideally after a
   farm visit. If the classes aren't covered, that means **training our own YOLO model**.
4. **Frame deadline: 300 ms or 400 ms?** 300 ms serves one pair of glasses per laptop (99% in
   time). 400 ms serves two pairs (96% in time); the popup would appear ~100 ms later.
   Measured in Step 5, see section 6.
5. **License before any company delivery:** Ultralytics YOLOv8 is AGPL-3.0. Fine for learning
   and demos; a closed commercial delivery needs an Ultralytics license or an Apache-licensed
   model (YOLOX, RT-DETR, D-FINE). The C++ worker's `Detector` interface keeps that swap small.

---

## 9. Build order

See `ROADMAP.md` for the step-by-step plan and the status of each step.
