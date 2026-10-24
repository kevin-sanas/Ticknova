#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
NODE_IMAGE='node:24.22.0-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f'
docker run --rm --user "$(id -u):$(id -g)" -v "$ROOT:/workspace" -w /workspace "$NODE_IMAGE" node scripts/New-LocalEnv.mjs
compose() { docker compose --env-file .env.demo -p ticknova-demo -f deploy/compose.yaml -f deploy/compose.demo.yaml "$@"; }
compose config --quiet
compose build
compose up --no-build -d --wait
sh scripts/Seed-Demo.sh
compose restart api
compose up -d --no-deps --wait api
printf '%s\n' 'Ticknova: http://127.0.0.1:5173 (default port).' 'Accounts: admin / agent / requester / editor / reviewer. Current passwords are only in .env.demo.'
