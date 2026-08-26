#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIR/lib/common_qdrant.sh"

load_env_file
apply_pipeline_defaults
validate_required_vars "QDRANT_COLLECTION_BOOKS" "QDRANT_COLLECTION_DOCS" \
    "QDRANT_COLLECTION_ARTICLES" "QDRANT_COLLECTION_PDFS"

QDRANT_BASE_URL="$(qdrant_rest_base_url)"
ACTIVE_COLLECTION_NAMES=(
    "$QDRANT_COLLECTION_BOOKS"
    "$QDRANT_COLLECTION_DOCS"
    "$QDRANT_COLLECTION_ARTICLES"
    "$QDRANT_COLLECTION_PDFS"
)

while true; do
    point_count=0
    point_count_known=true
    for active_collection_name in "${ACTIVE_COLLECTION_NAMES[@]}"; do
        collection_point_count=$(qdrant_curl -s "${QDRANT_BASE_URL}/collections/${active_collection_name}" 2>/dev/null \
            | jq -r '.result.points_count // -1' 2>/dev/null || echo "-1")
        if ! [[ "$collection_point_count" =~ ^[0-9]+$ ]]; then
            point_count_known=false
            break
        fi
        point_count=$((point_count + collection_point_count))
    done
    if [ "$point_count_known" != true ]; then
        point_count="unknown"
    fi
    embedding_calls=$(grep -c "\[EMBEDDING\].*embedding" "$PROJECT_ROOT/process_qdrant.log" 2>/dev/null || echo "0")
    last_file=$(grep "\[INDEXING\] Processing file with" "$PROJECT_ROOT/process_qdrant.log" 2>/dev/null | tail -1)

    echo -ne "\r[$(date +%H:%M:%S)] Core points: $point_count | Embedding calls: $embedding_calls | Last: $last_file          "
    sleep 5
done
