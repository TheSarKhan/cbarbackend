#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_project="kampus-validation-$$"
compose=(docker compose -p "$test_project" -f compose.yaml -f deploy/compose.test.yaml)
cleanup() { "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1; }
trap cleanup EXIT
"${compose[@]}" up --no-build -d --wait --wait-timeout 180 db sim api
python3 - <<'PY'
import sys,unittest
sys.path.insert(0,'scripts')
import smoke
smoke.BASE='http://127.0.0.1:18090/api/v1/'
smoke.SIM_BASE='http://127.0.0.1:18091'
result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(smoke.Checks))
raise SystemExit(not result.wasSuccessful())
PY
