#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
backup_dir="$PWD/.local/backups/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup_dir"
# Pause writers so the database and simulated provider snapshot share one boundary.
trap 'docker compose start sim api >/dev/null' EXIT
docker compose stop api sim >/dev/null
docker compose exec -T db pg_dump -U kampus -d kampus -Fc > "$backup_dir/postgres.dump"
docker compose cp sim:/data "$backup_dir/sim" >/dev/null
docker compose exec -T db pg_restore --list < "$backup_dir/postgres.dump" > "$backup_dir/contents.txt"
printf '%s\n' "$backup_dir"
