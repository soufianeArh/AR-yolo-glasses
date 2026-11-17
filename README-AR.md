# AR Glasses Detection Project

## Types of AR glasses

**Tethered (USB-C/DP)**
- Have no compute of their own.
- Connect via a wired USB-C cable using DisplayPort Alt Mode to a phone, share the phone's power/battery.
- The phone runs everything.

**Standalone**
- Glasses have their own OS (Goolton's is called "WEBB OS").
- Detachable battery, so they can potentially run apps and do processing on the glasses themselves.
- No phone required.

All 6 Goolton models checked (C5000D, C3000D, C2000S, G20S, H1000D, H4000D) list their own Android OS, chipset, and battery, so the standalone type is what this project is actually built for. No confirmed tethered model exists in Goolton's current lineup.

## How results get displayed

**Fixed position**
- Always the same spot in the display (e.g. top-right corner), never moves, never tries to sit "on" an object.
- Not tied to a location in the scene.
- Survives head movement.

**Box**
- Tied to a location in the scene.
- Meaningless if there's latency between capture and display (true for anything going through the network/backend).
- Stays meaningful for the local model, since it renders fast enough to keep up with movement.

## Project ideas

### Stream-Only (build first)

**Experience**: wearer walks around normally. A fixed-position popup (top right) appears when something hazard-relevant is detected, stays a few seconds, then fades.

**Technical workflow, tethered hardware**:
1. Glasses camera captures frames, sent over the USB-C/DP link to the paired phone.
2. Companion Android app streams frames to the backend.
3. Java scheduler assigns the frame to a free C++ worker.
4. C++ worker runs YOLO, returns label + confidence only.
5. Scheduler sends the result straight back to the phone app (fast path, unblocked).
6. In parallel, scheduler forwards a copy to a Python service that logs it and applies severity/dedup rules. Doesn't block step 5.
7. Phone app relays the result back over the USB-C/DP link. Glasses display shows/hides the popup.
8. Loop repeats continuously.

**Technical workflow, standalone hardware**:
1. Glasses camera captures frames directly.
2. An app running natively on the glasses' own OS (only possible if the SDK allows custom apps with direct camera access) streams those frames straight to the backend.
3. Java scheduler assigns the frame to a free C++ worker.
4. C++ worker runs YOLO, returns label + confidence only.
5. Scheduler sends the result straight back to the glasses' own app (fast path, unblocked).
6. In parallel, scheduler forwards a copy to a Python service that logs it and applies severity/dedup rules. Doesn't block step 5.
7. The glasses' own app shows/hides the fixed popup directly on its display.
8. Loop repeats continuously.

**Stack**:
- Glasses: Goolton hardware. Tethered = camera+display only, standalone = own OS ("WEBB OS") + own compute.
- Client: tethered = companion Android app on a phone; standalone = native app on the glasses' own OS.
- Java: scheduler, assigns frames to free workers, fast-path response.
- C++: inference worker(s), runs YOLO (libtorch/ONNX Runtime), returns label + confidence.
- Python: control plane, logs results, applies rules, stores history, off the hot path.
- Message/queue layer (candidate): connects scheduler, workers, and the Python service. Start simple (HTTP/queues), later a real broker.

### Stream + Local Model (upgrade of Stream-Only)

**Experience**:
- Box (drawn on the object, tracking it live): always on, from the local model, the main visual.
- Popup (fixed-position text): occasional, only when the cloud confirms/corrects/adds something, layered on top of the box.
- Works fully offline (box still works, popup just won't come).
- Costs more battery/CPU than Stream-Only.

**Tethered hardware workflow**:
1. Glasses camera sends frames over USB-C/DP to the phone.
2. Phone runs the local YOLO model, draws the live box immediately.
3. Phone checks network/load/battery, decides whether to offload.
4. If yes, companion app sends the frame to the backend.
5. Java scheduler assigns it to a free C++ worker.
6. C++ worker runs a heavier model, returns the result.
7. If still relevant, the cloud result shows as a popup alongside the box.
8. Python logs both results plus which path was used.
9. Glasses display shows the box (always) and the popup (sometimes).

**Standalone hardware workflow**: same steps, minus the phone. The native app on the glasses' own OS does everything directly (capture, local inference, decision policy, offload, render).

**Stack**:
- Glasses: Goolton hardware, standalone (confirmed lineup).
- Client: native glasses app, runs the local model plus the decision policy.
- Java: scheduler for the cloud path.
- C++: heavier inference worker(s) for the cloud path.
- Python: logs both results, applies rules.

## To be tested

- Test whether Goolton's SDK lets a custom app access the raw camera at all. This is the single item that determines if either project is buildable.
- Test installing a custom Android app on WEBB OS specifically.
- Benchmark actual YOLO inference FPS on the real chipset. This number decides how ambitious the local-model feature can honestly be.
- Select the exact Goolton glasses model to build for (C5000D, C3000D, C2000S, G20S, H1000D, H4000D). Specs vary a lot between them.
- Test Wi-Fi/network reliability in the actual work environment (open field, farm, or greenhouse).

## Limitations

**Stream-Only**
- Every detection needs a full round trip to the server and back. Could be slow, especially on bad Wi-Fi.
- If the connection drops, the feature goes completely dark. No backup.

**Stream + Local Model**
- The "instant" local model could run slowly because of basic CPU cores, a weak GPU, and no dedicated AI chip (NPU). Chipset has no confirmed AI accelerator, so performance is a guess until benchmarked.
- The server correction still has the same round-trip delay as Stream-Only, so it can arrive too late to matter.
- Running the YOLO model nonstop on the glasses' own chip for hours during a shift is heavy continuous work for small wearable hardware with no real cooling. Can cause the chip to overheat and throttle itself.

## Development plan

1. **Build Stream-Only first.** Full pipeline: glasses camera to app to Java scheduler to C++ YOLO worker to Python logging to result back on the display as a fixed popup.

2. **Upgrade into Stream + Local Model.** Same backend, same app, add a local YOLO model running on the glasses' own chip for a live tracking box, plus the decision logic for when to also check with the server.

3. **Keep the upgrade modest at first.** Run the local model at a low frame rate or in short bursts, not always-on. Test real speed on the device before deciding how much further to push it.

