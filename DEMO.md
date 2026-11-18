# Demo runbook - AR Plant Inspector (emulator)

One page to run the demo without surprises: what to prepare, what to show in which order, what to
say, and what to do when something goes wrong. Everything runs on this laptop: the backend in
WSL Ubuntu, the "glasses" in the Android emulator.

---

## 1. The day before (once)

- [ ] **Do a full dry run of section 3** on your own, start to finish.
- [ ] **Warm every demo video**: open each one once from the app's "Demo videos" screen. The first
      open downloads it and decodes it (15-90 s); after that it opens in ~0.25 s, even after a
      restart (frames are cached on the emulator).
- [ ] **Alert rules exist** (for the "backend" part of the demo). Check in a browser:
      http://localhost:8090/rules - you should see "leaf blight", "leaf rust", "leaf spot".
      If empty, create them (WSL terminal, in the `AR` folder):
      ```
      for r in "leaf blight|blight|high" "leaf rust|rust|medium" "leaf spot|spot|medium"; do IFS="|" read n l s <<< "$r"; curl -s -X POST localhost:8090/rules -H 'content-type: application/json' -d "{\"name\":\"$n\",\"label\":\"$l\",\"match\":\"contains\",\"min_confidence\":0.5,\"cooldown_seconds\":20,\"severity\":\"$s\"}"; echo; done
      ```

## 2. 15 minutes before

**RAM first** (8 GB laptop: the emulator alone takes ~3 GB):
- [ ] Close Chrome, Edge (also in the background: Task Manager → end "Microsoft Edge"), IntelliJ.
      Keep only what you need for the presentation.

**Start in this order:**

1. **Backend** - open a WSL terminal and **keep it open** (WSL stops everything when no terminal is open):
   ```
   cd /mnt/c/Users/Soufiane1/Desktop/projects/beta/AR
   bash scripts/start-backend.sh --plant-threads 3 --plant-deadline-ms 8000 --coco 1 --coco-threads 2 --control
   ```
   Wait for `backend up`. It prints the address the emulator must use, e.g. `172.22.36.122:50051`.
   (`--coco 1` only if you show the object videos; `--control` = Redis + Python + API + demo videos.)

2. **Emulator** - from Android Studio (Device Manager → AR_Glasses_API34 ▶), or a terminal:
   ```
   "%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd AR_Glasses_API34 -gpu host -no-snapshot-save
   ```
   Wait 2-3 minutes after it boots. If "System UI isn't responding" appears: press **Wait** (it is
   Android itself catching up after boot, not our app).

3. **App** - open "AR Glasses" (it is installed; to reinstall: Android Studio ▶ Run).

**Check on the welcome screen:**
- [ ] `server 172.22.36.122:50051: reachable ✓` - green.
      Red ✗ → the WSL address changed (after a Windows restart). Get it with `wsl hostname -I`
      (in a Windows terminal) and put it in **Settings → Server address → ✓ Save**.
- [ ] `8 demo videos` (not "demo server not reachable").
- [ ] Language = the one you will present in (Settings → first field).

## 3. The demo (~10 minutes)

| # | Show | Say |
|---|---|---|
| 1 | **Welcome screen** | "The wearer looks at a corn, tomato or potato leaf and holds still - the glasses say if it is diseased. The glasses only stream frames; the analysis runs on a server: Java scheduler, C++ AI workers, Python services." |
| 2 | **Demo videos** list | "These videos play as if they were the glasses' camera. The list itself comes from the backend's REST API - new demo videos appear without updating the app." |
| 3 | **Corn leaves - look and hold** (✓ 4/5 correct) | "The view moves, then stops on a leaf. After half a second of stillness the app sends ONE frame, then a second one, and votes. The popup shows the disease and confidence; the yellow box is where the model looked; the last line compares with the right answer - this video was made from labelled photos." |
| 4 | **Pause / Play** in the bar | "Pause stops the camera and the stream - on the glasses the camera turns off." |
| 5 | **Potato leaves** (✓ 3/4) | Point at one ✓ and the ✗ ("Not a corn, potato or tomato leaf" - the model saw a blueberry leaf; we only accept our three crops). |
| 6 | **Settings → Language → 中文 → Save** | Same video in Chinese: disease names translated (玉米大斑病 ...). Switch back afterwards. |
| 7 | **Videos → People walking among cows** (needs `--coco 1`) | "Same pipeline, a second AI model: the scheduler routes each frame to the right worker by model name. Popup: person / cow." |
| 8 | **The backend**: browser → http://localhost:8090/alerts and http://localhost:8090/stats | "Every result also goes, asynchronously, through Redis to the Python control plane: stored in Postgres, alert rules with cooldowns. The glasses never wait for it." |
| 9 | **Optional - robustness**: in a second WSL terminal `pkill -f scheduler-java`, watch the app ("No connection to the server"), then restart the backend (section 5) → "Server back" | "The app repairs its own connection and never shows a stale answer." Only if you rehearsed it. |
| 10 | **Corn field (real footage, healthy)** | **The honest part:** "On real field footage the model still raises false alarms - it says blight on a healthy field. It was trained on a small public dataset of photos. Next step: retrain it (YOLOv8 on Colab, already prepared) and re-measure on these same clips." |

**Avoid** unless you explain it: *Tomato leaves - look and hold* (1/5 correct: some of its photos are
lecture slides with text) and the webcam (the plant model finds nothing in a room - correct, but dull).

## 4. If something goes wrong

| Problem | Fix |
|---|---|
| Welcome: server **NOT reachable ✗** | Backend terminal still open? `backend up` printed? WSL IP changed → `wsl hostname -I` → Settings → Server address → Save. |
| "Demo server not reachable" | Backend started without `--control`. Restart it with `--control` (section 5). |
| Black screen with "Loading video... n%" | First open of that video: wait (or pick another one you warmed). |
| "System UI isn't responding" | Press **Wait**. Close other programs (RAM). |
| Popups say **"No connection to the server"** | The backend stopped (terminal closed?). Restart it - the app reconnects by itself within ~10 s. |
| "Network too slow" | Something else is using the CPU/network; close it. The app recovers by itself. |
| Stuck in a keyboard / a screen | **◁ Back** (bottom of the emulator screen) or **Ctrl+Backspace**. In Settings, ✕ Cancel never saves anything. |
| Popup answers are slow (>6 s) | Emulator not on the graphics card: start it with `-gpu host`. Close other programs. |
| Wrong language | Settings → first field → ✓ Save. |

## 5. Stop / restart the backend

```
bash scripts/stop-backend.sh                  # in WSL, in the AR folder
bash scripts/start-backend.sh --plant-threads 3 --plant-deadline-ms 8000 --coco 1 --coco-threads 2 --control
```
The app reconnects by itself; no need to restart it.

---

Architecture diagram for slides: `archi.mmd` (paste it into https://mermaid.live). Details and all
measurements: `ARCHITECTURE.md`, `ROADMAP.md`.
