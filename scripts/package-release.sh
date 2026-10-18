#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts
COPYFILE_DISABLE=1 tar --exclude=__pycache__ --exclude='*.pyc' --exclude=node_modules --exclude=target --exclude=.next --exclude=.expo --exclude=dist --exclude=android --exclude=ios --exclude='*.tsbuildinfo' --exclude='*.log' --exclude=.DS_Store --exclude=.env --exclude=.env.local -czf artifacts/kampus-deploy-source.tar.gz README.md package.json .github .gitignore .dockerignore compose.yaml apps services scripts deploy docs
shasum -a 256 artifacts/kampus-deploy-source.tar.gz > artifacts/kampus-deploy-source.tar.gz.sha256
echo 'artifacts/kampus-deploy-source.tar.gz'
