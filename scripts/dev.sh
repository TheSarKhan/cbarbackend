#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .local/runtime
if [[ -x .local/tools/node_modules/node/bin/node ]]; then export PATH="$PWD/.local/tools/node_modules/node/bin:$PATH"; fi
for port in 3000 8080 8081 8082; do
  if lsof -iTCP:"$port" -sTCP:LISTEN -t >/dev/null; then
    echo "Port $port artıq istifadə olunur. Mövcud Kampus proseslərini dayandırın və yenidən sınayın."
    exit 1
  fi
done
children=()
cleanup() { for pid in "${children[@]}"; do kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM
cp services/sim/target/kampus-sim-0.1.0.jar ".local/runtime/sim-$$.jar"
cp services/api/target/kampus-api-0.1.0.jar ".local/runtime/api-$$.jar"
java -jar ".local/runtime/sim-$$.jar" > .local/sim.log 2>&1 & children+=("$!")
java -jar ".local/runtime/api-$$.jar" > .local/api.log 2>&1 & children+=("$!")
npm --prefix apps/admin run dev > .local/admin.log 2>&1 & children+=("$!")
npm --prefix apps/mobile run web -- --port 8082 > .local/mobile.log 2>&1 & children+=("$!")
echo "Kampus: mobil http://localhost:8082 · admin http://localhost:3000"
echo "Loglar .local/ qovluğundadır. Dayandırmaq üçün Ctrl+C."
wait
