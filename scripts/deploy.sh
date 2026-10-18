#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f .env ]] || { echo 'Əvvəl scripts/deploy-init.py ilə .env yaradın.'; exit 1; }
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 240
echo 'Bütün servislər healthy vəziyyətindədir. Ünvan və PIN-lər şəxsi .env faylındadır.'
