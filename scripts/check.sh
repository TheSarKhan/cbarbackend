#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -f services/api/pom.xml test package
mvn -q -f services/sim/pom.xml package
python3 scripts/smoke.py
