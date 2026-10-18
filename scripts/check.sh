#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -x .local/tools/node_modules/node/bin/node ]]; then export PATH="$PWD/.local/tools/node_modules/node/bin:$PATH"; fi
npm --prefix apps/mobile run lint -- --max-warnings 0
npm --prefix apps/mobile run typecheck
npm --prefix apps/admin run typecheck
mvn -q -f services/api/pom.xml test package
mvn -q -f services/sim/pom.xml package
python3 scripts/smoke.py
