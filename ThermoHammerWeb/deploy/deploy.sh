#!/bin/bash
# Copies the site generator to the server and (re)starts it. Safe to run again after any change.
#   deploy/deploy.sh            copy + restart the generator (templates, styles, copy, code)
#   deploy/deploy.sh --build    also rebuild the generator image (after changing deploy/Dockerfile)
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${TH_DEPLOY_HOST:-myserver}"
DIR="${TH_DEPLOY_DIR:-/home/ubuntu/docker/thweb}"

ssh "$HOST" "mkdir -p '$DIR/app' '$DIR/public' '$DIR/cache'"
rsync -az --delete --exclude '__pycache__' --exclude '.DS_Store' --exclude '/assets-src/screens' \
  "$HERE/build.py" "$HERE/thweb" "$HERE/templates" "$HERE/static" "$HERE/data" "$HERE/assets-src" "$HOST:$DIR/app/"
rsync -az "$HERE/deploy/Dockerfile" "$HERE/deploy/docker-compose.yaml" "$HERE/deploy/Caddyfile" "$HOST:$DIR/"

if [ "${1:-}" = "--build" ]; then
  ssh "$HOST" "cd '$DIR' && sudo docker compose build thweb-gen"
fi
ssh "$HOST" "cd '$DIR' && sudo docker compose up -d && sudo docker restart thweb-gen >/dev/null && sleep 20 && sudo docker logs --tail 5 thweb-gen && sudo docker ps --filter name=thweb --format '{{.Names}}  {{.Status}}'"
