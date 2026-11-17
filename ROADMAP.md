# AR Glasses Detection: Roadmap (Stream-Only, v1)

Step-by-step plan for building the architecture in `ARCHITECTURE.md`.
Each step has a clear **"done when"**, so you know when to move on.

The backend is built and measured first, using a fake client and the sample footage.
The Android app comes after that, and the glasses come last. That way, hardware blockers
never stop the backend work.

---

## Step 0: Setup and the hardware check

**Goal:** tools installed, models ready, and the biggest unknown about the glasses checked early.

- Install: JDK 21, CMake + MSVC (Visual Studio Build Tools), ONNX Runtime (CPU),
  Python 3.12, Android Studio, Postgres, Memurai (Redis).
- Export the models to ONNX with Ultralytics (`yolo export format=onnx`):
  - COCO `yolov8n.pt` → `yolov8n.onnx`
  - `fire.pt` (from soufiane-easy, already audited) → `fire.onnx`
- Copy sample footage and images (`samples/land`, `samples/drone`) into a `testdata/` folder.
- **In parallel, on the glasses:** check whether a custom Android app installs on WEBB OS and
  can read the raw camera through the Goolton SDK. This decides Plan A vs Plan B.

**Done when:** both `.onnx` files exist, the toolchains compile a "hello world", and you know
the answer (or have a contact at Goolton) on camera access.

---

## Step 1: The shared contract (`proto/`)

**Goal:** one definition of the messages every language uses.

- Write `detection.proto`: `Frame`, `Detection`, `FrameResult`, `WorkerHeartbeat`.
- Define the services:
  - `FrameStream`: Android app ↔ Java scheduler, two-way streaming
  - `InferenceWorker`: Java scheduler → C++ worker
  - `WorkerControl`: heartbeats from the C++ worker to the Java scheduler
- Generate code for Java, C++, Python (and Kotlin later).

**Done when:** code generates without errors in all three backend languages.

---

## Step 2: C++ worker, on its own (no network)

**Goal:** a correct and fast YOLO forward pass in C++. This is the inference hot path.

- Decode the JPEG (OpenCV or stb_image).
- Pre-process: letterbox resize to 640, BGR→RGB, normalise, convert HWC→CHW.
- Run inference with ONNX Runtime.
- Post-process: decode the output, apply the confidence threshold, NMS, and map boxes back
  to the original image size.
- A small command-line tool: `worker-cli image.jpg` → prints the detections.
- **Check correctness:** compare against Ultralytics in Python on the same images.
  Labels must match, and boxes should be within a few pixels.
- **Benchmark:** ms per frame for 1, 2 and 4 threads.

**Done when:** the output matches Ultralytics on the sample images, and you have a
benchmark table.

---

## Step 3: C++ worker as a gRPC server

**Goal:** the worker becomes a service.

- Wrap Step 2 in a gRPC server (`InferenceWorker.Detect`).
- Load the model once at startup, and warm it up with one dummy inference.
- Send a heartbeat every second with `{busy, queue_depth, avg_infer_ms}`.
- Startup options: port, model path, thread count, worker id.

**Done when:** a small Python script sends a JPEG and gets the detections back over gRPC.

---

## Step 4: Java scheduler v1, with one worker and a fake client

**Goal:** the full backend path working end to end.

- A gRPC server for `FrameStream`, holding one session per stream.
- Forward each frame to a single C++ worker and send the result back on the same stream.
- **Fake client** (Python or Java): plays `loop.mp4`, sends frames at a fixed FPS, and allows
  only 1–2 frames in flight, exactly like the real Android app will.
- Measure: end-to-end latency (p50/p95/p99) and FPS.

**Done when:** the fake client receives correct detections, and you have latency numbers.

---

## Step 5: Java scheduler v2, with N workers (the scheduling core)

**Goal:** real scheduling, load balancing and failover. This is the Java and HPC heart of
the project.

- Worker registry, updated from the heartbeats.
- Least-loaded routing.
- A deadline per frame (about 300 ms). Frames past their deadline are dropped.
- Failover: a worker that misses its heartbeats is marked unhealthy, and its in-flight
  frame is retried once on another worker.
- `scripts/start-workers` launches N C++ workers on different ports.
- **Experiments:**
  - Throughput vs number of workers (1, 2, 4, 6) and threads per worker.
  - Several fake clients at once.
  - Kill a worker during a run and check that no session stops.
- Expose metrics: a simple `/metrics` endpoint or periodic log lines.

**Done when:** you have graphs of throughput and latency against worker count, and killing
a worker causes no client-visible failure beyond one retried frame.

---

## Step 6: Python control plane

**Goal:** alerts and history, off the hot path.

- The Java scheduler publishes each `FrameResult` to a Redis Stream.
- A Python consumer reads it through a consumer group.
- Port the rule engine from soufiane-easy: threshold → quorum → cooldown per (rule, device).
- Store detections and alerts in Postgres (with real migrations, e.g. Alembic).
- FastAPI endpoints: `/detections`, `/alerts`, `/rules` CRUD, `/config` (models, thresholds).
- **Test:** stop Python or Redis during a run. The fast path must keep working.

**Done when:** alerts appear correctly from the sample footage, and killing Python doesn't
affect the latency of the fast path.

---

## Step 7: Android app on a normal phone

**Goal:** the real client, without the glasses yet.

- Kotlin app with CameraX capture: resize to 640, JPEG, gRPC stream (grpc-okhttp).
- Backpressure: at most 1–2 frames in flight, drop the rest.
- Popup overlay in the top-right corner: show on detection, hold about 3 seconds, fade.
- A settings screen for the server host and port (never hard-coded).
- Test on the same Wi-Fi as the PC, then through a phone hotspot.

**Done when:** pointing the phone at a sample image on a screen shows the correct popup,
with measured round-trip latency.

---

## Step 8: Moving to the glasses

**Goal:** the same Android app on the Goolton glasses.

- **Plan A:** install the Android app on WEBB OS. Swap CameraX for the Goolton SDK camera,
  and adapt the popup to the glasses display (position, size, contrast).
- **Plan B (if the SDK blocks camera access):** the Android app stays on a paired phone, and
  the glasses connect over USB-C/DP.
- Field test: measure Wi-Fi and hotspot reliability and latency in the real setting
  (farm, field, greenhouse).

**Done when:** the wearer sees a correct popup on the glasses during a walk-around.

---

## Later: Stream + Local Model

Not part of v1. It starts only after Step 8, with a benchmark of YOLO FPS on the real
glasses chipset. See `README-AR.md` → Development plan.
