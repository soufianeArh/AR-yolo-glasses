#!/usr/bin/env bash
set -uo pipefail

LOG_DIR=${AR_LOG_DIR:-/tmp/ar-workers}
PIDS="$LOG_DIR/pids"
[ -f "$PIDS" ] || { echo "no workers running (no $PIDS)"; exit 0; }

while read -r pid; do kill -INT "$pid" 2>/dev/null; done < "$PIDS"
# give them 5 s to stop cleanly before killing
for _ in $(seq 1 50); do
  alive=0
  while read -r pid; do kill -0 "$pid" 2>/dev/null && alive=1; done < "$PIDS"
  [ "$alive" = 0 ] && break
  sleep 0.1
done
while read -r pid; do kill -KILL "$pid" 2>/dev/null; done < "$PIDS"
rm -f "$PIDS"
echo "workers stopped"
