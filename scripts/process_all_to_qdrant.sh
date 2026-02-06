#!/bin/bash

# Consolidated document processor for hybrid Qdrant ingestion.
# Usage: ./process_all_to_qdrant.sh [--doc-sets=docset1,docset2]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
DOCS_ROOT="$PROJECT_ROOT/data/docs"
LOG_FILE="$PROJECT_ROOT/process_qdrant.log"
PID_FILE="$PROJECT_ROOT/process_qdrant.pid"
DOCS_SETS_FILTER=""

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIR/lib/common_qdrant.sh"

for arg in "$@"; do
    case $arg in
        --doc-sets=*)
            DOCS_SETS_FILTER="${arg#*=}"
            ;;
        --help|-h)
            echo "Usage: $0 [--doc-sets=docset1,docset2]"
            exit 0
            ;;
        *)
            echo "Unknown option: $arg"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

log() {
    echo "[$(date)] $1" >> "$LOG_FILE"
    echo -e "$1"
}

corpus_indexed_summary() {
    local parsed_dir="$PROJECT_ROOT/data/parsed"
    local index_dir="$PROJECT_ROOT/data/index"
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

echo "[$(date)] Starting document processing" > "$LOG_FILE"

echo "=============================================="
echo "Document Processor"
echo "=============================================="
echo -e "${CYAN}Mode: ${YELLOW}qdrant-ingest${NC}"
echo "Project root: $PROJECT_ROOT"
echo "Docs root: $DOCS_ROOT"
echo ""

load_env_file
validate_required_vars "QDRANT_HOST" "QDRANT_PORT" "APP_LOCAL_EMBEDDING_ENABLED"
echo ""

if ! check_qdrant_connection "log"; then
    log "${RED}Cannot proceed without Qdrant connectivity${NC}"
    exit 1
fi

if ! check_embedding_server "log"; then
    log "${RED}Embedding provider check failed${NC}"
    exit 1
fi

setup_pid_and_cleanup

log "${YELLOW}Building application...${NC}"
build_application "$LOG_FILE"

if [ -n "$DOCS_SETS_FILTER" ]; then
    export DOCS_SETS="$DOCS_SETS_FILTER"
fi

app_jar=$(locate_app_jar)

log "${YELLOW}Starting document processor...${NC}"
java -Dspring.profiles.active=cli \
     -DDOCS_DIR="$DOCS_ROOT" \
     -jar "$app_jar" \
     --spring.main.web-application-type=none \
     --server.port=0 >> "$LOG_FILE" 2>&1 &
APP_PID=$!
echo "$APP_PID" > "$PID_FILE"

log "${BLUE}Application started with PID: $APP_PID${NC}"

monitor_java_process "$APP_PID" "$LOG_FILE" "$PID_FILE"

echo ""
rm -f "$PID_FILE"
log "${GREEN}Pipeline completed successfully${NC} ($(corpus_indexed_summary))"
log "Log file: $LOG_FILE"
