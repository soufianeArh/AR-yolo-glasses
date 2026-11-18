#!/usr/bin/env bash
set -euo pipefail

# plant only by default because plant and coco together dont fit this cpu
COCO=0; COCO_THREADS=1; PLANT_THREADS=3; PLANT_MODEL=models/plant.onnx; CONTROL=0; PORT=50051; DEADLINE=300; PLANT_DEADLINE=3500
while [ $# -gt 0 ]; do
  case "$1" in
    --coco) COCO=$2; shift 2 ;;
    --coco-threads) COCO_THREADS=$2; shift 2 ;;
    --plant-threads) PLANT_THREADS=$2; shift 2 ;;
    --plant-model) PLANT_MODEL=$2; shift 2 ;;
    --control) CONTROL=1; shift ;;
    --port) PORT=$2; shift 2 ;;
    --deadline-ms) DEADLINE=$2; shift 2 ;;
    --plant-deadline-ms) PLANT_DEADLINE=$2; shift 2 ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
done

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
BIN=${AR_BIN:-$HOME/ar-build/worker-cpp}
VENV=${AR_VENV:-$HOME/ar-venv}
LOG_DIR=${AR_LOG_DIR:-/tmp/ar-backend}
mkdir -p "$LOG_DIR"
PIDS="$LOG_DIR/pids"
[ -f "$PIDS" ] && { echo "already running? $PIDS exists - run scripts/stop-backend.sh first"; exit 1; }

wait_for() {
  for _ in $(seq 1 $(( $3 * 10 ))); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 0.1; done
  echo "timed out waiting for '$2' in $1"; tail -5 "$1"; return 1
}

# keep the wsl session open after this or wsl stops everything
REDIS_ARG="--redis none"
[ "$CONTROL" = 1 ] && REDIS_ARG="--redis 127.0.0.1:6379"

scheduler-java/build/install/scheduler-java/bin/scheduler-java --port "$PORT" --deadline-ms "$DEADLINE" --model-deadline "plant=$PLANT_DEADLINE" $REDIS_ARG \
  > "$LOG_DIR/scheduler.log" 2>&1 &
echo $! >> "$PIDS"
wait_for "$LOG_DIR/scheduler.log" READY 30
echo "scheduler: port $PORT ($(grep -o 'frame deadline.*heartbeat' "$LOG_DIR/scheduler.log" | sed 's/, heartbeat//'))"

"$BIN/worker-server" --model "$PLANT_MODEL" --name plant --port 50061 --worker-id plant-1 \
  --threads "$PLANT_THREADS" --scheduler "127.0.0.1:$PORT" > "$LOG_DIR/plant-1.log" 2>&1 &
echo $! >> "$PIDS"
for i in $(seq 1 "$COCO"); do
  "$BIN/worker-server" --model models/yolov8n.onnx --name default --port $((50070 + i)) \
    --worker-id "coco-$i" --threads "$COCO_THREADS" --scheduler "127.0.0.1:$PORT" > "$LOG_DIR/coco-$i.log" 2>&1 &
  echo $! >> "$PIDS"
done
wait_for "$LOG_DIR/plant-1.log" READY 60
echo "plant-1: $PLANT_MODEL, $PLANT_THREADS threads"
for i in $(seq 1 "$COCO"); do wait_for "$LOG_DIR/coco-$i.log" READY 30; echo "coco-$i: models/yolov8n.onnx, $COCO_THREADS thread(s)"; done

if [ "$CONTROL" = 1 ]; then
  (cd control-python && "$VENV/bin/alembic" upgrade head > "$LOG_DIR/alembic.log" 2>&1)
  (cd control-python && exec "$VENV/bin/python" -m ar_control.consumer) > "$LOG_DIR/consumer.log" 2>&1 &
  echo $! >> "$PIDS"
  (cd control-python && exec "$VENV/bin/uvicorn" ar_control.api:app --host 0.0.0.0 --port 8090) \
    > "$LOG_DIR/api.log" 2>&1 &
  echo $! >> "$PIDS"
  wait_for "$LOG_DIR/consumer.log" READY 30
  wait_for "$LOG_DIR/api.log" "Application startup complete" 30
  echo "control plane: consumer + API on http://127.0.0.1:8090 (try /health, /alerts)"
fi
echo "backend up. Android emulator: server $(hostname -I | cut -d' ' -f1):$PORT (WSL IP; 10.0.2.2 does NOT work on Windows 10)."
echo "KEEP THIS WSL SESSION OPEN: WSL stops everything when no session is attached."
echo "Logs: $LOG_DIR. Stop: bash scripts/stop-backend.sh"
