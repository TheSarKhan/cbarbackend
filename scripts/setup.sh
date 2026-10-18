#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -x .local/tools/node_modules/node/bin/node ]]; then export PATH="$PWD/.local/tools/node_modules/node/bin:$PATH"; fi
if ! node -e "if (![22,24,26].includes(Number(process.versions.node.split('.')[0]))) process.exit(1)"; then
  npm install --prefix .local/tools node@24 --no-audit --no-fund
  export PATH="$PWD/.local/tools/node_modules/node/bin:$PATH"
fi
npm ci --prefix apps/mobile
npm ci --prefix apps/admin
mvn -q -f services/api/pom.xml package
mvn -q -f services/sim/pom.xml package
