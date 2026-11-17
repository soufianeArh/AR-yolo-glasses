# AR Glasses Detection: Architecture (Stream-Only, v1)

Status: **design / discussion**. Nothing here is implemented yet.

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
┌────────── Backend (one machine, native processes) ─┐
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
  (around 40 KB), so less data travels over Wi-Fi.
- **Backpressure:** at most **1–2 frames in flight**. If the backend is slow, drop new
  frames instead of queuing them. Newest frame wins.
- **Display:** a fixed-position popup in the top-right corner.
  - Show it when a detection passes the confidence threshold.
  - Hold it for about 3 seconds, then fade it out.
  - This smoothing happens in the Android app, so the popup stays steady without
    waiting for the Python rule engine.
- **Transport:** one gRPC two-way stream to the Java scheduler per device session.
- **Identity:** a simple per-device token, sent when the stream opens.

### 3.2 Java scheduler (the core of the project)

Plain Java 21 with grpc-java, using virtual threads. Spring isn't needed on the hot path.

- **Sessions:** accepts the gRPC streams from the Android app, one session per device.
- **Worker registry:** a live table of every C++ worker: whether it's busy, its queue depth,
  its recent latency and its last heartbeat.
- **Scheduling policy (v1):**
  - Send each frame to the **least-loaded** healthy worker.
  - Give each frame a **deadline** of about 300 ms. A frame that misses it is dropped,
    never processed late.
  - **Failover:** a worker that misses its heartbeats is marked unhealthy and gets no new
    frames. Its in-flight frame is retried once on another worker.
- **Fast path:** sends the result straight back to the Android app on the same stream.
- **Async copy:** publishes every result to Redis Streams for the Python control plane.
  It never waits on this; if Redis is down, the fast path keeps working.
- **Metrics:** frames per second, queue depth and latency percentiles (p50/p95/p99), both
  per worker and per session.

### 3.3 C++ inference workers

- Each worker is a small **gRPC server**: it takes in a JPEG and returns a list of detections.
- **Engine:** ONNX Runtime, CPU only.
- **Models:** YOLOv8n exported to ONNX from the same weights used in soufiane-easy:
  - `default`: COCO yolov8n (person, cow, bird, car…)
  - `fire`: `fire.pt` (classes `fire`, `smoke`)
- **Scaling:** several worker processes, each with 2 intra-op threads, sized to the
  8-core machine. This is where the multi-core throughput work happens: measure FPS and
  latency per worker, and how they change as you add workers.
- **Health:** sends heartbeats and its current load to the Java scheduler.
- **Output format** (same as soufiane-easy):
  `{label, confidence, box: (x1, y1, x2, y2)}`

### 3.4 Python control plane (FastAPI)

- **Consumes** results from Redis Streams through a consumer group.
- **Rule engine:** ported from soufiane-easy (not copied). It applies three filters in order:
  1. threshold: `confidence >= rule.min_confidence`
  2. quorum: at least `min_count` hits in one frame
  3. cooldown: nothing from this rule for N seconds, per (rule, device)
- **Storage:** detections and alerts in Postgres.
- **REST API:** history (detections, alerts) and config (enabled models, per-model
  confidence thresholds, rules).
- It is **off the hot path**: it can be slow or even down without breaking the popup on
  the glasses.

---

## 4. Communication

| Link | Choice | Why |
|---|---|---|
| Android app ↔ Java scheduler | gRPC two-way streaming | One stream per device, low overhead, works on Android (grpc-okhttp) |
| Java scheduler ↔ C++ workers | gRPC | Fast, typed, easy to time |
| Java scheduler → Python | Redis Streams | Light enough for an 8 GB machine. Could move to Kafka later |
| All of them | **One shared `.proto` contract** | Kotlin, Java, C++ and Python all generate their code from the same definition |

Main messages in the `.proto` contract (draft):

- `Frame { session_id, frame_id, captured_at_ms, jpeg_bytes, models[] }`
- `Detection { label, confidence, x1, y1, x2, y2 }`
- `FrameResult { session_id, frame_id, worker_id, detections[], infer_ms, total_ms }`
- `WorkerHeartbeat { worker_id, busy, queue_depth, avg_infer_ms }`

---

## 5. Project folder layout

```
AR/
  proto/            ← shared contract (frame, detection, worker health)
  android-app/      ← Kotlin
  scheduler-java/
  worker-cpp/
  control-python/
  scripts/          ← start/stop scripts (launch N C++ workers, scheduler, python)
```

**No Docker, no nginx.**
- Your own services (Java scheduler, C++ workers, Python control plane) run as **native
  processes**. That makes profiling, debugging and CPU/thread control direct, and keeps
  throughput measurements free of container CPU caps.
- **Postgres:** native Windows install.
- **Redis:** Memurai (Redis-compatible for Windows), WSL, or a single small container just
  for Redis, whichever is easiest.
- **No reverse proxy:** the Java scheduler is the single entry point and the Android app
  connects to it directly over gRPC. TLS can be turned on in grpc-java if needed.
- Docker can be added later for packaging and deployment only.

---

## 6. Expected latency (CPU only, to check against real measurements)

| Step | Estimate |
|---|---|
| Wi-Fi upload of about 40 KB | 20–50 ms |
| Scheduler routing | < 5 ms |
| YOLOv8n inference on CPU (640) | 40–80 ms |
| Return trip | a few ms |
| **Total round trip** | **about 100–200 ms on good Wi-Fi** |

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

Rules:
- Never hard-code the server address in the Android app. Make it a setting (host + port).
- When there's no network, Stream-Only shows nothing. That's the known limitation. The fix is
  a hotspot, an on-site server, or later the Stream + Local Model upgrade.
- To verify on the real glasses: whether they can join a phone hotspot, whether a VPN app
  installs on WEBB OS, and whether the model has a SIM.

---

## 7. Reused from soufiane-easy (by porting or reusing data, not shared code)

- Model weights: COCO yolov8n, `fire.pt` (exported to ONNX here).
- Detection format: `{label, confidence, box}`.
- Rule-engine logic: threshold → quorum → cooldown.
- "Newest frame wins" frame handling.
- Sample farm footage (`samples/land`, `samples/drone`) for testing without the glasses.

---

## 8. Open questions

1. **Models in v1:** only COCO plus `fire`, or also `plant`? The plant-disease model is a
   transformers model (YOLOS-small), so it's much harder to port to C++. The proposal is to
   leave it out of v1.
2. **Goolton SDK camera access:** this decides between Plan A and Plan B
   (see `README-AR.md` → "To be tested").
3. **Training our own YOLO model:** only needed if the glasses must detect classes that the
   existing models don't cover.

---

## 9. Build order

See `ROADMAP.md` for the step-by-step plan.
