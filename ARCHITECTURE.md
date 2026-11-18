# AR Glasses Detection: Architecture (Stream-Only, v1)

Status: **Steps 0–6, 7.1–7.5 and 8 built and tested**: shared contract, C++ inference workers
(COCO YOLOv8 + the plant-disease model), Java scheduler with N workers (routing by model,
per-model deadlines, retries, failover, per-session cap), benchmarks, the Python control plane
(Redis Stream → Postgres, rule engine, REST API), and the **Android app on a glasses-shaped
emulator** (plant inspection "look and hold", popup, settings, self-repairing connection), tested
end to end down to Postgres alerts.
**Next:** Step 7.6 (train our own plant-disease YOLOv8 on Colab - prepared; the current model
gives confident false alarms on real field footage), then the glasses (Step 9).
Progress and results per step: see `ROADMAP.md`.

**What the system detects: plant diseases on leaves.** The goal of the detection part is
**hands-free leaf inspection**: the wearer looks at a corn, tomato or potato leaf and the
glasses show the disease (blight, rust, leaf spot, mosaic virus...), using soufiane-easy's
`plant` model (30 PlantDoc classes). The COCO YOLOv8n model (people, cows...) used in Steps 2–6
was a **placeholder** to build and test the pipeline; it stays as a fast pipeline test.

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
- **During development:** the Android app is built and tested on the **Android emulator**,
  configured to act like the glasses (landscape screen, black see-through background). **No phone
  step**: the app goes from the emulator straight to the glasses. A phone only comes back if
  Plan B is forced.

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

### 3.1 Android app (Kotlin) ✅ built (Step 8, on the emulator)

As built (`android-app/`, package `com.soufiane.ar.glasses`; details and measurements in
`ROADMAP.md` Step 8):
- `InspectController`: look and hold → up to 2 frames voted → verdict; only our crops (corn,
  tomato, potato) count; the same view again within 60 s re-shows the verdict without a check.
- `StreamController`: every frame, ≤2 in flight, drop when busy (COCO stream mode).
- `Popup` + `PopupRules`: what the popup says, its colour, 3 s hold, no flicker.
- `Connection`: one gRPC stream at a time, repaired by itself (retry 1-2-4-8-10 s, 5 s connect
  timeout); a frame with no answer in time resets the stream, so queued frames never arrive stale.
- `AppSettings` + `SettingsActivity`: saved settings; launch extras are saved too (configure the
  glasses with one `adb` command); long-press or the menu key opens the settings screen.
- `WelcomeActivity` (launcher) and `DemoListActivity` (8.9): entry screen with server status;
  demo videos listed from the control plane (`GET /demos`, `ar_control/demos.py`) plus the ones on
  the device, downloaded once on demand. Everything works with arrow keys + Enter (glasses).
- The inspection screen shows videos and, unless "show camera" is off (see-through glasses), the
  camera image; auto-hiding icons (Home, Pause, Settings); "expected: ... ✓/✗" for test videos.
- Sources: `FileSource` (video files, frames kept as JPEG), `CameraSource` (Camera2; the PC
  webcam on the emulator). The pipeline runs only while the app is in front.
- 31 JVM unit tests (`gradlew testDebugUnitTest`).

The design it was built from:

- **Modes:**
  - **Plant inspection, "look and hold" (main mode):** the app notices when the view is steady
    (the wearer looks closely at a leaf and holds still), sends that ONE frame with model `plant`,
    shows "checking...", then the result: e.g. "Tomato late blight 0.87" or "Healthy leaf".
  - **COCO stream (pipeline test):** continuous frames at ~8 fps with model `default`.
  - Continuous plant inspection comes once a fast plant model is trained (3.3).
- **Image sources**, behind one `FrameSource` interface: `FileSource` (videos / images, the
  main source on the PC), `CameraXSource` (the PC webcam through the emulator), later
  `GooltonSource` (the glasses camera).
- **Pre-process on the device:** shrink each frame and compress it to JPEG (around 40–50 KB at
  640 px), so less data travels over Wi-Fi.
- **Backpressure:** at most **1–2 frames in flight**. If the backend is slow, drop new
  frames instead of queuing them. Newest frame wins.
- **Display:** black background (see-through on optical glasses) and a fixed-position popup in
  the top-right corner.
  - Show it when a detection passes the confidence threshold (per model).
  - Hold it for about 3 seconds, then fade it out.
  - This smoothing happens in the Android app, so the popup stays steady without
    waiting for the Python rule engine.
- **Debug view** (development only, off on the glasses): the frame being sent, boxes drawn on
  it, frame counter, fps, latency.
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
  **Deadline per model** (Step 7.5, `--model-deadline`): **default `plant=3500`** - a "look and
  hold" inspection takes ~1.6–2.5 s on the laptop; 2.5 s proved too tight under load.
- **Model routing:** workers advertise their models in their heartbeat; a frame goes only to
  workers that have its model (`Frame.models`, default `default`). Tested with a `plant` worker
  next to a COCO worker: each frame only reaches workers of its model.
- **Retry:** on queue full (`RESOURCE_EXHAUSTED`) or unreachable (`UNAVAILABLE`), **one** retry
  on another worker if at least 50 ms of the deadline is left. Never on a deadline miss.
- **Per-session cap:** 2 frames in flight (`--session-max-in-flight`); more → `SESSION_LIMIT` at
  once, so one buggy client can't starve the others.
- **Device token:** read from the `authorization` header, recorded, not checked yet (Step 8).
- **Fast path:** the result goes straight back to the Android app on the same stream.
- **Async copy to Redis:** every result goes into a bounded memory buffer (10,000) and one
  background thread writes the buffer to the Redis Stream `ar:results` in batches. The fast path
  never waits: Redis down → results wait in the buffer (tested: nothing lost over a 4 s outage);
  buffer full → oldest dropped and counted. `--redis none` turns it off.
- **Identity:** session ids are unique across scheduler restarts; the device id is a hash of the
  device token (`dev-xxxxxxxxxxxx`), the token itself is never logged or published.
- **Metrics:** every 5 s: frames/s, counts per status, retries, backend p50/p95/p99 **per model**
  (a 2 s plant inspection and a 150 ms COCO frame are never mixed), and per worker: health,
  frames sent, in flight, average inference time.
- **Tests:** 13 unit tests (routing rules with a fake clock, per-model deadlines), end-to-end
  suites for Steps 4, 5 and 7.

### 3.3 C++ inference workers ✅ built (Steps 2–3, plant model Step 7)

- Each worker is a small **gRPC server** (`worker-server`): it takes in a JPEG and returns a
  list of detections.
- **Engine:** ONNX Runtime 1.32, CPU only. **OpenCV** for JPEG decode and resize.
- **Models:**
  - **Built: `default` = COCO YOLOv8n**, exported to ONNX (fixed 640×640 input) from the same
    weights used in soufiane-easy. A **placeholder** used to build and test the pipeline; kept as
    a fast pipeline test. Class names are read from the `.onnx` file itself, so another YOLOv8
    export works without code changes.
  - **Built (Step 7): `plant` = soufiane-easy's plant-disease model** (`models/plant.onnx`),
    YOLOS-small trained on PlantDoc (30 classes over 13 crops). A **transformer** with a **second
    C++ detector**, `YolosDetector`: dynamic input size (short side 512, long side ≤ 864,
    multiple of 16), a port of torchvision's antialiased fixed-point resize, ImageNet
    normalisation, softmax, no NMS. **Identical to soufiane-easy's Python on 45/45 test leaves.**
    The position-embedding resize is exported as two MatMuls instead of a bicubic Resize node
    (same math; 6.1 s → 1.9 s per inference, abort 2 s → 7 ms). **~1.6 s per inspection with
    3 threads** (the whole CPU), hence "look and hold", not a live stream.
    **Measured weak spots of the model:** right disease on only **48%** of labelled leaves
    (neighbouring diseases confused, sometimes the wrong plant, healthy corn → blight), and
    **unstable between frames** (8/14 reframed copies of a leaf get the same disease).
  - **Next (Step 7.6, prepared): our own YOLOv8 trained on PlantDoc** (29 classes; training on
    Google Colab's free GPU with `notebooks/train_plant_yolov8.ipynb`). Runs on the existing fast
    YOLOv8 detector: the dry run took 136 ms per leaf. Compared with the current model by
    `tools/eval_plant_models.py` (accuracy, stability, speed) before switching.
  - **Choosing the detector:** `load_detector()` reads `model_type` from the ONNX metadata
    (`yolos` for our plant export, YOLOv8 otherwise); `worker-server --model-type` forces it.
    Both detectors share one ONNX Runtime layer (`OrtModel`: loading, metadata, run with abort).
  - `fire` is not used (a weak fit for AR, never tested on real fire).
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
  - Scaling = **more worker processes**. Measured best on this laptop: COCO **3 workers × 2
    threads**; plant **1 worker × 3 threads**, and **not both at once** (section 6).
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
  A `/config` endpoint is left for Step 8, when something reads it.
- **"Label contains" rules** (Step 7, migration `0002`): `match = 'contains'` lets one rule cover
  several labels, e.g. `blight` → Corn leaf blight, Tomato leaf late blight, Potato leaf early
  blight...; the alert stores the label the model actually emitted. Tested end to end: alerts for
  two blighted leaves, none for a healthy one.
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
  scripts/          ← start-backend.sh / stop-backend.sh (plant-only setup, --coco, --control),
                      start-workers.sh / stop-workers.sh                ✅
  bench/            ← benchmark results (Step 5 COCO, Step 7 plant split / model comparison) ✅
  notebooks/        ← train_plant_yolov8.ipynb (Colab training, Step 7.6) ✅
  datasets/         ← PlantDoc in YOLO format (not in git)               ✅
  control-python/   ← Python control plane (consumer, rules, API, migrations, tests) ✅
  android-app/      ← Step 8
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

**The Android app on the emulator** (Step 8.8; emulator on the real graphics card, backend in WSL
on the same laptop; the emulator's network adds ~0.3 s that real Wi-Fi should not):

| Mode | Value |
|---|---|
| Plant inspection, per frame | round trip p50 2.2 s / p95 2.8 s (backend p50 1.6 s / p95 2.1 s) |
| **Plant inspection, leaf held still → popup** | **p50 4.1 s, max 5.7 s** (0.5 s to see the view is still + 2 frames voted one after the other) |
| COCO stream (8 fps asked, 300 ms deadline) | round trip p50 ~300 ms, ~5 answers/s, 14–23% of frames miss the deadline |

Software rendering in the emulator (`-gpu swiftshader_indirect`) took ~65% of the CPU and doubled
the plant time (leaf → popup 8.4 s): use `-gpu host`.

**Plant inspection** (Step 7, current YOLOS model): **~1.6 s per inspection with 3 threads**
(p95 1.8 s; 1.5–2.5 s through the whole chain as the CPU heats). Plant deadline 3.5 s.
**Plant and COCO at the same time don't fit this laptop:** with a plant inspection every 2.5 s
during a COCO stream, COCO fell to 0–61% OK and plant took 2.8–4.7 s (`bench/plant_split.json`).
→ plant only on this machine; a trained YOLOv8 plant model (Step 7.6) should be ~10× lighter.

**Capacity (COCO):** ~11 frames/s in total on this laptop, reached with **3 workers × 2 threads**.
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

**Reaching the backend in WSL from the glasses (development, Windows 10):** other
devices on the Wi-Fi can't reach WSL directly (WSL2 NAT). Windows has to forward the Java
scheduler's port into WSL (`netsh interface portproxy`) and allow it in Windows Firewall.
One-time setup, needed from Step 9 (the glasses). Not an issue on a real Linux server.

**The emulator (Step 8), corrected 2026-11-18:** `10.0.2.2` (the emulator's address for the PC)
does **not** reach the backend on this Windows 10 PC: it leads to Windows' IPv4 localhost, while
WSL's relay only listens on IPv6 localhost (`::1`). The working route without admin rights is
**WSL's own IP** (`wsl hostname -I`, changes after a reboot), set in the app (launch option now,
settings screen in Step 8.6). A `portproxy` from `127.0.0.1` would also fix it (admin).
**The backend must run in a WSL session that stays open:** WSL shuts down when no session is
attached, stopping everything started in the background.

Rules:
- Never hard-code the server address in the Android app. Make it a setting (host + port).
- When there's no network, Stream-Only shows nothing. That's the known limitation. The fix is
  a hotspot, an on-site server, or later the Stream + Local Model upgrade.
- To verify on the real glasses: whether they can join a phone hotspot, whether a VPN app
  installs on WEBB OS, and whether the model has a SIM.

---

## 7. Reused from soufiane-easy (by porting or reusing data, not shared code)

- **The plant-disease model** (`models/plant-disease`, YOLOS-small / PlantDoc, 30 classes):
  the core model of this project from Step 7, exported to ONNX here. Its 6 test samples
  (`samples/plant/`) and its measured behaviour are reused too.
- Model weights: COCO yolov8n (exported to ONNX here), the pipeline placeholder.
- Detection format: `{label, confidence, box}`.
- Rule-engine logic: threshold → quorum → cooldown.
- "Newest frame wins" frame handling.
- Sample farm footage (`samples/land`, `samples/drone`) for testing without the glasses.

---

## 8. Open questions

1. ~~Models~~ **Decided:** the core model is **`plant`** (plant-disease inspection, Step 7).
   COCO yolov8n stays as a pipeline test. `fire` is not used.
2. **Goolton SDK camera access:** this decides between Plan A and Plan B
   (see `README-AR.md` → "To be tested"). Still to test on the real glasses.
3. ~~What the glasses should detect~~ **Decided:** plant diseases on leaves. **"Look and hold"
   first** (one frame per inspection, works with the existing YOLOS model); **continuous
   inspection later**, once our own fast YOLOv8 is trained on plant diseases (Step 7.6).
   Measured (Step 7.4): ~1.6 s per inspection on this CPU with the current model. Still open:
   whether the glasses camera can focus on a leaf close up (Step 9).
   **Model quality** (Step 7): the current model is right on only 48% of labelled leaves and
   unstable between frames. Answer in progress: train our own YOLOv8 (Step 7.6), and vote over
   several frames of a hold in the app (Step 8.4).
4. **Frame deadline: 300 ms or 400 ms?** 300 ms serves one pair of glasses per laptop (99% in
   time). 400 ms serves two pairs (96% in time); the popup would appear ~100 ms later.
   Measured in Step 5, see section 6.
5. **License before any company delivery:** Ultralytics YOLOv8 is AGPL-3.0. Fine for learning
   and demos; a closed commercial delivery needs an Ultralytics license or an Apache-licensed
   model (YOLOX, RT-DETR, D-FINE). The C++ worker's `Detector` interface keeps that swap small.

---

## 9. Build order

See `ROADMAP.md` for the step-by-step plan and the status of each step.
