#!/bin/bash

# Consolidated document processor for hybrid Qdrant ingestion.
# Usage: ./process_all_to_qdrant.sh [--doc-sets=docset1,docset2]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
DOCS_ROOT=""
LOG_FILE="$PROJECT_ROOT/process_qdrant.log"
PID_FILE="$PROJECT_ROOT/process_qdrant.pid"
DOCS_SETS_FILTER=""

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIR/lib/common_qdrant.sh"

corpus_indexed_summary() {
    local parsed_dir="$DOCS_PARSED_DIR"
    local index_dir="$DOCS_INDEX_DIR"
    local parsed_count=0
    local indexed_count=0

    if [ -d "$parsed_dir" ]; then
        parsed_count=$(find "$parsed_dir" -type f -name "*.txt" 2>/dev/null | wc -l | tr -d ' ')
    fi
    if [ -d "$index_dir" ]; then
        indexed_count=$(find "$index_dir" -maxdepth 1 -type f ! -name "file_*.marker" 2>/dev/null | wc -l | tr -d ' ')
    fi

    echo "${indexed_count} indexed / ${parsed_count} parsed"
}

verify_doc_set_postconditions() {
    local processing_log="$1"
    local qdrant_base_url
    qdrant_base_url="$(qdrant_rest_base_url)"
    local -a active_collection_names=(
        "$QDRANT_COLLECTION_BOOKS"
        "$QDRANT_COLLECTION_DOCS"
        "$QDRANT_COLLECTION_ARTICLES"
        "$QDRANT_COLLECTION_PDFS"
    )
    local verified_doc_set_count=0
    local documentation_set
    while IFS= read -r documentation_set; do
        [ -z "$documentation_set" ] && continue
        verified_doc_set_count=$((verified_doc_set_count + 1))
        local exact_doc_set_point_count=0
        local collection_name
        for collection_name in "${active_collection_names[@]}"; do
            local filtered_count_request
            filtered_count_request="$(jq -n --arg documentationSet "$documentation_set" '{
                exact: true,
                filter: {must: [{key: "docSet", match: {value: $documentationSet}}]}
            }')"
            local filtered_count_state
            filtered_count_state="$(qdrant_curl -s -X POST \
                -H "Content-Type: application/json" \
                -d "$filtered_count_request" \
                "$qdrant_base_url/collections/$collection_name/points/count" 2>/dev/null || echo "")"
            local collection_doc_set_count
            collection_doc_set_count="$(echo "$filtered_count_state" | jq -r '.result.count // -1' 2>/dev/null || echo -1)"
            if ! [[ "$collection_doc_set_count" =~ ^[0-9]+$ ]]; then
                echo "Failed to verify exact docSet '$documentation_set' in collection '$collection_name'" >&2
                return 1
            fi
            exact_doc_set_point_count=$((exact_doc_set_point_count + collection_doc_set_count))
        done
        if [ "$exact_doc_set_point_count" -le 0 ]; then
            echo "No Qdrant points exist for required docSet '$documentation_set'" >&2
            return 1
        fi
        log "${GREEN}Verified exact docSet postcondition: $documentation_set ($exact_doc_set_point_count points)${NC}"
    done < <(
        sed -n 's/^.*Qdrant postcondition required for docSet: //p' "$processing_log" \
            | sed 's/[[:space:]]*$//'
    )
    if [ "$verified_doc_set_count" -le 0 ]; then
        echo "Document processor emitted no docSet postconditions" >&2
        return 1
    fi
}

validate_environment_generation_contract() {
    local expected_collection_prefix="java-chat-${SPRING_PROFILE}-qwen3-embedding-4b-2560"
    local -a expected_collection_names=(
        "${expected_collection_prefix}-books"
        "${expected_collection_prefix}-docs"
        "${expected_collection_prefix}-articles"
        "${expected_collection_prefix}-pdfs"
    )
    local -a configured_collection_names=(
        "$QDRANT_COLLECTION_BOOKS"
        "$QDRANT_COLLECTION_DOCS"
        "$QDRANT_COLLECTION_ARTICLES"
        "$QDRANT_COLLECTION_PDFS"
    )
    local collection_index
    for collection_index in "${!expected_collection_names[@]}"; do
        if [ "${configured_collection_names[$collection_index]}" != "${expected_collection_names[$collection_index]}" ]; then
            echo "Qdrant collection names must match SPRING_PROFILE and the 4B/2560 generation" >&2
            return 1
        fi
    done

    local -a configured_state_directories=(
        "$DOCS_SNAPSHOT_DIR"
        "$DOCS_PARSED_DIR"
        "$DOCS_INDEX_DIR"
    )
    local -a expected_state_leaf_directories=(snapshots parsed index)
    local state_index
    for state_index in "${!configured_state_directories[@]}"; do
        local expected_state_suffix="qwen3-embedding-4b-2560/${SPRING_PROFILE}/${expected_state_leaf_directories[$state_index]}"
        if [[ "${configured_state_directories[$state_index]}" != *"/$expected_state_suffix" ]]; then
            echo "Ingestion state paths must match SPRING_PROFILE and the 4B/2560 generation" >&2
            return 1
        fi
    done
}

run_documentation_ingestion() {
DOCS_SETS_FILTER=""
for ingestion_argument in "$@"; do
    case $ingestion_argument in
        --doc-sets=*)
            DOCS_SETS_FILTER="${ingestion_argument#*=}"
            ;;
        --help|-h)
            echo "Usage: $0 [--doc-sets=docset1,docset2]"
            exit 0
            ;;
        *)
            echo "Unknown option: $ingestion_argument"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done
load_env_file
if ! validate_required_vars "QDRANT_HOST" "QDRANT_PORT" "APP_LOCAL_EMBEDDING_ENABLED" "SPRING_PROFILE" \
    "QDRANT_COLLECTION_BOOKS" "QDRANT_COLLECTION_DOCS" "QDRANT_COLLECTION_ARTICLES" "QDRANT_COLLECTION_PDFS" \
    "DOCS_SNAPSHOT_DIR" "DOCS_PARSED_DIR" "DOCS_INDEX_DIR"; then
    return 1
fi
case "$SPRING_PROFILE" in
    local|dev|prod) ;;
    *) echo "SPRING_PROFILE must be exactly local, dev, or prod" >&2; exit 1 ;;
esac
if ! validate_environment_generation_contract; then
    return 1
fi
DOCS_ROOT="${DOCS_DIR:-$PROJECT_ROOT/data/docs}"
export DOCS_DIR="$DOCS_ROOT"

if [ ! -d "$DOCS_ROOT" ] || [ ! -r "$DOCS_ROOT" ]; then
    echo "DOCS_DIR must identify a readable documentation root: $DOCS_ROOT" >&2
    exit 1
fi
for writable_state_directory in "$DOCS_SNAPSHOT_DIR" "$DOCS_PARSED_DIR" "$DOCS_INDEX_DIR"; do
    if ! mkdir -p "$writable_state_directory" || [ ! -w "$writable_state_directory" ]; then
        echo "Ingestion state directory must be writable: $writable_state_directory" >&2
        exit 1
    fi
    if [ "$(id -u)" -eq 1001 ] && [ "$(stat -c '%u' "$writable_state_directory" 2>/dev/null || stat -f '%u' "$writable_state_directory")" -ne 1001 ]; then
        echo "Container ingestion state directory must be owned by UID 1001: $writable_state_directory" >&2
        exit 1
    fi
done

echo "[$(date)] Starting document processing" > "$LOG_FILE"
echo "=============================================="
echo "Document Processor"
echo "=============================================="
echo -e "${CYAN}Mode: ${YELLOW}qdrant-ingest${NC}"
echo "Project root: $PROJECT_ROOT"
echo "Docs root: $DOCS_ROOT"
echo ""
echo ""

if ! check_qdrant_connection "log"; then
    log "${RED}Cannot proceed without Qdrant connectivity${NC}"
    exit 1
fi

if ! check_embedding_server "log"; then
    log "${RED}Embedding provider check failed${NC}"
    exit 1
fi

setup_pid_and_cleanup "$PID_FILE"

log "${YELLOW}Building application...${NC}"
build_application "$LOG_FILE"

if [ -n "$DOCS_SETS_FILTER" ]; then
    export DOCS_SETS="$DOCS_SETS_FILTER"
fi

app_jar=$(locate_app_jar)

log "${YELLOW}Starting document processor...${NC}"
java -Dspring.profiles.active=cli \
     -jar "$app_jar" \
     --spring.main.web-application-type=none \
     --server.port=0 >> "$LOG_FILE" 2>&1 &
APP_PID=$!
echo "$APP_PID" > "$PID_FILE"

log "${BLUE}Application started with PID: $APP_PID${NC}"

monitor_java_process "$APP_PID" "$LOG_FILE" "$PID_FILE"
verify_doc_set_postconditions "$LOG_FILE"

echo ""
rm -f "$PID_FILE"
log "${GREEN}Pipeline completed successfully${NC} ($(corpus_indexed_summary))"
log "Log file: $LOG_FILE"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    run_documentation_ingestion "$@"
fi
