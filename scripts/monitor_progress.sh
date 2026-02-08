#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIR/lib/common_qdrant.sh"

load_env_file
apply_pipeline_defaults

QDRANT_BASE_URL="$(qdrant_rest_base_url)"

while true; do
    point_count=$(qdrant_curl -s "${QDRANT_BASE_URL}/collections/${QDRANT_COLLECTION}" 2>/dev/null \
        | jq -r '.result.points_count // "unknown"' 2>/dev/null || echo "unknown")
    embedding_calls=$(grep -c "EMBEDDING.*Calling API" process_qdrant.log 2>/dev/null || echo "0")
    last_file=$(grep "Processing file:" process_qdrant.log 2>/dev/null | tail -1 | cut -d: -f2)

    echo -ne "\r[$(date +%H:%M:%S)] Vectors: $point_count | Embedding calls: $embedding_calls | Last: $last_file          "
    sleep 5
done
