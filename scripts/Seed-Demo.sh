#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
compose() { docker compose --env-file .env.demo -p ticknova-demo -f deploy/compose.yaml -f deploy/compose.demo.yaml "$@"; }
mkdir -p data
LOCK="$ROOT/data/demo-seed-shell.lock"
if ! mkdir "$LOCK" 2>/dev/null; then printf '%s\n' 'Seed already running or interrupted; inspect its lock before continuing.' >&2; exit 1; fi
trap 'rmdir "$LOCK"' 0
identity=$(compose exec -T postgres psql -U ticknova_demo -d ticknova_demo -At -c 'SELECT current_database()')
if [ "$identity" != ticknova_demo ]; then printf '%s\n' 'Refusing seed: database identity mismatch.' >&2; exit 1; fi
fresh=$(compose exec -T postgres psql -U ticknova_demo -d ticknova_demo -At -c "SELECT (SELECT count(*) FROM app_user)=4 AND NOT EXISTS(SELECT 1 FROM app_user WHERE username NOT IN ('admin','agent','requester','editor') OR NOT must_change_password OR NOT active) AND (SELECT count(*) FROM user_role)=4 AND (SELECT count(*) FROM support_queue)=1 AND EXISTS(SELECT 1 FROM support_queue WHERE code='GENERAL' AND active) AND (SELECT count(*) FROM service_catalog)=1 AND EXISTS(SELECT 1 FROM service_catalog WHERE code='GENERAL' AND active) AND NOT EXISTS(SELECT 1 FROM ticket) AND NOT EXISTS(SELECT 1 FROM knowledge_space)")
if [ "$fresh" != t ]; then
  completed=$(compose exec -T postgres psql -U ticknova_demo -d ticknova_demo -At -c "SELECT EXISTS(SELECT 1 FROM audit_event WHERE action='DEMO_CONTENT_SEEDED' AND subject_type='SYSTEM')")
  if [ "$completed" != t ]; then printf '%s\n' 'Existing or interrupted data preserved; no seed completion marker.' >&2; exit 1; fi
  printf '%s\n' 'Demo seed skipped: completed seed already exists; all data preserved.'
  exit 0
fi
web=$(compose ps -q web)
if [ -z "$web" ]; then printf '%s\n' 'Demo web service not running.' >&2; exit 1; fi
docker run --rm --user "$(id -u):$(id -g)" --network "container:$web" -v "$ROOT:/workspace:ro" -v "$ROOT/.env.demo:/workspace/.env.demo" -w /workspace node:24.22.0-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f node scripts/Seed-Demo.mjs http://127.0.0.1 /workspace/.env.demo verified-empty-ticknova-demo
compose exec -T postgres psql -U ticknova_demo -d ticknova_demo -v ON_ERROR_STOP=1 -c "INSERT INTO audit_event(id,actor_id,action,subject_type,trace_id,metadata) SELECT gen_random_uuid(),id,'DEMO_CONTENT_SEEDED','SYSTEM','original-demo-seed',jsonb_build_object('dataset','original-service-desk-v1','mode','mock','documents',12,'tickets',24) FROM app_user WHERE username='admin'"
