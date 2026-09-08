#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIAGRAM_DIR="$ROOT/docs/diagrams"

IMAGE="ghcr.io/mermaid-js/mermaid-cli/mermaid-cli:11.16.1"

USER_ID="$(id -u)"
GROUP_ID="$(id -g)"

render() {
    local source="$1"
    local name="$2"
    local mode="$3"

    echo "  $name.mmd -> $name-$mode.svg"

    docker run \
        --rm \
        --user "$USER_ID:$GROUP_ID" \
        -e HOME=/tmp \
        -v "$DIAGRAM_DIR:/data" \
        -w /data \
        "$IMAGE" \
        -i "/data/$source" \
        -o "/data/$name-$mode.svg" \
        -c "/data/mermaid-config-$mode.json" \
        -C "/data/theme.css" \
        -b transparent \
        -w 1800
}

echo "Rendering Forge diagrams..."

for source in "$DIAGRAM_DIR"/*.mmd; do
    file="$(basename "$source")"
    name="${file%.mmd}"

    render "$file" "$name" light
    render "$file" "$name" dark
done

echo
echo "✓ Light and dark diagrams rendered"
