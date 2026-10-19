#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .local/runtime
for port in 8080 8081; do
 if lsof -iTCP:"$port" -sTCP:LISTEN -t >/dev/null; then echo "Port $port artıq istifadə olunur."; exit 1; fi
done
cp services/api/target/kampus-api-0.1.0.jar ".local/runtime/api-$$.jar"
cp services/sim/target/kampus-sim-0.1.0.jar ".local/runtime/sim-$$.jar"
children=()
trap 'for pid in "${children[@]}"; do kill "$pid" 2>/dev/null || true; done' EXIT INT TERM
java -jar ".local/runtime/sim-$$.jar" > .local/sim.log 2>&1 & children+=("$!")
java -jar ".local/runtime/api-$$.jar" > .local/api.log 2>&1 & children+=("$!")
echo 'API: http://localhost:8080/api/v1 · simulator: http://localhost:8081'
wait
