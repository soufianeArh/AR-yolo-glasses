#!/usr/bin/env bash
set -euo pipefail

N=${1:?usage: start-workers.sh N THREADS [BASE_PORT] [SCHEDULER]}
THREADS=${2:?usage: start-workers.sh N THREADS [BASE_PORT] [SCHEDULER]}
BASE_PORT=${3:-50061}
SCHEDULER=${4:-127.0.0.1:50051}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
BIN=${AR_BIN:-$HOME/ar-build/worker-cpp}
LOG_DIR=${AR_LOG_DIR:-/tmp/ar-workers}
mkdir -p "$LOG_DIR"

# one worker per port
for i in $(seq 1 "$N"); do
  port=$((BASE_PORT + i - 1))
  "$BIN/worker-server" --model "$ROOT/models/yolov8n.onnx" --port "$port" \
      --worker-id "worker-$i" --threads "$THREADS" --scheduler "$SCHEDULER" \
      > "$LOG_DIR/worker-$i.log" 2>&1 &
  echo $! >> "$LOG_DIR/pids"
  echo "worker-$i: port $port, $THREADS threads, pid $!"
done
echo "logs in $LOG_DIR"
