#!/usr/bin/env bash
set -Eeuo pipefail

revision="${1:-}"
deploy_dir="${FORGE_DEPLOY_DIR:-/opt/forge}"
compose_files=(-f docker-compose.yml -f docker-compose.public.yml)
expected_services=(caddy controller dashboard postgres worker-a worker-b worker-c)

if [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Usage: $0 <40-character-git-revision>" >&2
  exit 2
fi

cd "$deploy_dir"

exec 9> .deploy.lock
if ! flock -n 9; then
  echo "Another deployment is already running." >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  echo "$deploy_dir/.env is missing; run the Oracle bootstrap first." >&2
  exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Tracked files in $deploy_dir have local changes; refusing to overwrite them." >&2
  exit 1
fi

echo "Fetching $revision from origin..."
git fetch --prune origin main
git cat-file -e "${revision}^{commit}"

if ! git merge-base --is-ancestor "$revision" origin/main; then
  echo "$revision is not part of origin/main." >&2
  exit 1
fi

git checkout --detach "$revision"

docker compose "${compose_files[@]}" config --quiet

# The Always Free A1 host currently has two OCPUs. Serializing Compose builds
# avoids several compiler-heavy images exhausting host resources at once.
export COMPOSE_PARALLEL_LIMIT="${COMPOSE_PARALLEL_LIMIT:-1}"
docker compose "${compose_files[@]}" build --pull
docker compose "${compose_files[@]}" up -d --remove-orphans

deployment_ok=false
for attempt in {1..60}; do
  all_running=true
  for service in "${expected_services[@]}"; do
    if ! docker compose "${compose_files[@]}" ps --services --filter status=running | grep -Fxq "$service"; then
      all_running=false
      break
    fi
  done

  if [[ "$all_running" == true ]] && \
     docker compose "${compose_files[@]}" exec -T dashboard \
       node -e "fetch('http://127.0.0.1:3000/templates').then(r => { if (!r.ok) process.exit(1) })" && \
     docker compose "${compose_files[@]}" exec -T dashboard \
       node -e "fetch('http://controller:8080/api/workers').then(r => { if (!r.ok) process.exit(1); return r.json() }).then(w => { if (!w.some(x => x.online)) process.exit(1) })"; then
    deployment_ok=true
    break
  fi

  sleep 5
done

if [[ "$deployment_ok" != true ]]; then
  echo "Deployment did not become healthy." >&2
  docker compose "${compose_files[@]}" ps >&2
  docker compose "${compose_files[@]}" logs --tail=100 controller dashboard worker-a caddy >&2
  exit 1
fi

printf '%s\n' "$revision" > .deployed-revision
echo "Forge deployment is healthy at revision $revision."
