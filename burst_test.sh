#!/usr/bin/env bash
# Wallet & P2P Transfer Microservice: Burst Test in Bash + curl
set -e

BASE_URL="${1:-http://localhost:8080}"
echo "Running Live Invariant Burst Script against: $BASE_URL"

# Probe 1: Concurrent get-or-create
echo "--- [PROBE 1] Concurrent get-or-create ---"
USER_ID="bash_user_$RANDOM"
PIDS=()
for i in {1..15}; do
  curl -s -X POST "$BASE_URL/wallets" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $USER_ID" \
    -d "{\"user_id\":\"$USER_ID\",\"initial_balance_paise\":50000}" &
  PIDS+=($!)
done
for pid in "${PIDS[@]}"; do
  wait "$pid"
done
echo ""
echo "Concurrent get-or-create finished."

# Probe 2: Health & Prometheus
echo "--- [PROBE 2] Health & Metrics ---"
curl -s "$BASE_URL/actuator/health"
echo ""
curl -s "$BASE_URL/logs" | head -c 200
echo ""
echo "Done."
