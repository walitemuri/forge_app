#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export FORGE_COMPOSE_OVERLAY=docker-compose.azure.yml
exec "$script_dir/deploy.sh" "$@"
