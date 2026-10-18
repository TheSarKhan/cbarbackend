#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "${2:-}" == '--replace-data' ]] || { echo 'Bərpa mövcud deploy datasını əvəz edir. İstifadə: scripts/restore.sh BACKUP_QOVLUĞU --replace-data'; exit 1; }
backup_dir="$(cd "${1:?Backup qovluğu lazımdır}" && pwd)"
[[ -s "$backup_dir/postgres.dump" && -d "$backup_dir/sim" ]] || { echo 'Backup natamamdır.'; exit 1; }
docker compose exec -T db pg_restore --list < "$backup_dir/postgres.dump" >/dev/null
docker compose stop api sim >/dev/null
docker compose exec -T db pg_restore --clean --if-exists --no-owner --exit-on-error -U kampus -d kampus < "$backup_dir/postgres.dump"
docker compose cp "$backup_dir/sim/." sim:/data >/dev/null
docker compose start sim api
docker compose up -d --wait --wait-timeout 180
