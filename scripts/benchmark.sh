#!/usr/bin/env bash
# Regenerates the shared benchmark with the Java generator and measures the Python models and the Java statistics on it.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$PWD/services/analytics/benchmark/benchmark.json"
mvn -q -f services/api/pom.xml test -Dtest=BenchmarkExportTests -Dbenchmark.out="$OUT"
cd services/analytics
[ -d .venv ] || { python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt; }
.venv/bin/python -m kampus_analytics.evaluate benchmark/benchmark.json --write benchmark/results.md
