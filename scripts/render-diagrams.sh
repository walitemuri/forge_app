#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIAGRAM_DIR="$ROOT/docs/diagrams"

IMAGE="ghcr.io/mermaid-js/mermaid-cli/mermaid-cli:11.16.1"

USER_ID="$(id -u)"
GROUP_ID="$(id -g)"

echo "Rendering Forge architecture diagrams..."
echo

docker pull "$IMAGE"

for source in "$DIAGRAM_DIR"/*.mmd; do
    name="$(basename "$source" .mmd)"

    echo "  $name.mmd -> $name.svg"

    docker run \
        --rm \
        --user "$USER_ID:$GROUP_ID" \
        -e HOME=/tmp \
        -v "$DIAGRAM_DIR:/data" \
        -w /data \
        "$IMAGE" \
        -i "/data/$name.mmd" \
        -o "/data/$name.svg" \
        -b transparent \
        -w 1800
done

echo
echo "✓ Diagrams rendered"
