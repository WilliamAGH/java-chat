#!/bin/bash

# Consolidated document processor for hybrid Qdrant ingestion.
# Usage: ./process_all_to_qdrant.sh [--doc-sets=docset1,docset2]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
DOCS_ROOT="$PROJECT_ROOT/data/docs"
LOG_FILE="$PROJECT_ROOT/process_qdrant.log"
DOCS_SETS_FILTER=""

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

if [ -n "${RED:-}" ]; then
    :
else
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    CYAN='\033[0;36m'
    NC='\033[0m'
fi

log() {
    echo "[$(date)] $1" >> "$LOG_FILE"
    echo -e "$1"
}

percent_complete() {
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

    if [ "$parsed_count" -gt 0 ]; then
        awk -v i="$indexed_count" -v p="$parsed_count" 'BEGIN { printf("%.1f%%", (i/p)*100) }'
    else
        echo "0.0%"
    fi
}

qdrant_rest_base_url() {
    if [ "${QDRANT_SSL:-false}" = "true" ] || [ "${QDRANT_SSL:-false}" = "1" ]; then
        if [ -n "${QDRANT_REST_PORT:-}" ]; then
            echo "https://${QDRANT_HOST}:${QDRANT_REST_PORT}"
        else
            echo "https://${QDRANT_HOST}"
        fi
    else
        echo "http://${QDRANT_HOST}:${QDRANT_REST_PORT:-8087}"
    fi
}

check_qdrant_connection() {
    log "${YELLOW}Checking Qdrant connection...${NC}"

    local base_url
    base_url=$(qdrant_rest_base_url)
    local url="${base_url}/collections"

    local curl_opts=(-s -o /dev/null -w "%{http_code}")
    if [ -n "${QDRANT_API_KEY:-}" ]; then
        curl_opts+=(-H "api-key: $QDRANT_API_KEY")
    fi

    local response
    response=$(curl "${curl_opts[@]}" "$url" || echo "000")

    if [ "$response" != "200" ]; then
        log "${RED}Qdrant connection failed (HTTP $response)${NC}"
        log "${YELLOW}URL: $url${NC}"
        return 1
    fi

    log "${GREEN}Qdrant connection successful${NC} ($(percent_complete))"
    return 0
}

check_embedding_server() {
    if [ "${APP_LOCAL_EMBEDDING_ENABLED:-false}" = "true" ]; then
        log "${YELLOW}Checking local embedding server...${NC}"
        local url="${LOCAL_EMBEDDING_SERVER_URL:-http://127.0.0.1:1234}/v1/models"
        local response
        response=$(curl -s -o /dev/null -w "%{http_code}" "$url")
        if [ "$response" = "200" ]; then
            log "${GREEN}Local embedding server is healthy${NC} ($(percent_complete))"
            return 0
        fi
        log "${RED}Local embedding server not responding (HTTP $response)${NC}"
        return 1
    fi

    log "${BLUE}Using remote embedding provider${NC}"
    return 0
}

cleanup() {
    log ""
    log "${YELLOW}Received interrupt signal. Shutting down...${NC}"
    if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then
        kill -TERM "$APP_PID" 2>/dev/null || true
    fi
    exit 0
}

trap cleanup INT TERM

echo "[$(date)] Starting document processing" > "$LOG_FILE"

echo "=============================================="
echo "Document Processor"
echo "=============================================="
echo -e "${CYAN}Mode: ${YELLOW}qdrant-ingest${NC}"
echo "Project root: $PROJECT_ROOT"
echo "Docs root: $DOCS_ROOT"
echo ""

if [ -f "$PROJECT_ROOT/.env" ]; then
    PRESET_APP_LOCAL_EMBEDDING_ENABLED="${APP_LOCAL_EMBEDDING_ENABLED:-}"
    set -a
    source "$PROJECT_ROOT/.env"
    set +a
    if [ -n "$PRESET_APP_LOCAL_EMBEDDING_ENABLED" ]; then
        APP_LOCAL_EMBEDDING_ENABLED="$PRESET_APP_LOCAL_EMBEDDING_ENABLED"
        export APP_LOCAL_EMBEDDING_ENABLED
    fi
    echo -e "${GREEN}Environment variables loaded${NC}"
else
    echo -e "${RED}.env file not found${NC}"
    exit 1
fi

required_vars=("QDRANT_HOST" "QDRANT_PORT" "APP_LOCAL_EMBEDDING_ENABLED")
missing_vars=()
for var in "${required_vars[@]}"; do
    if [ -z "${!var:-}" ]; then
        missing_vars+=("$var")
    fi
done

if [ ${#missing_vars[@]} -gt 0 ]; then
    echo -e "${RED}Missing required environment variables:${NC}"
    printf '%s\n' "${missing_vars[@]}"
    exit 1
fi

echo -e "${GREEN}All required environment variables present${NC}"
echo ""

if ! check_qdrant_connection; then
    log "${RED}Cannot proceed without Qdrant connectivity${NC}"
    exit 1
fi

if ! check_embedding_server; then
    log "${RED}Embedding provider check failed${NC}"
    exit 1
fi

if pgrep -f "java.*java-chat" > /dev/null; then
    log "${YELLOW}Stopping existing java-chat process...${NC}"
    pkill -f "java.*java-chat" || true
    sleep 2
fi

log "${YELLOW}Building application...${NC}"
cd "$PROJECT_ROOT"
if ! ./gradlew buildForScripts --no-configuration-cache --quiet >> "$LOG_FILE" 2>&1; then
    log "${RED}Build failed${NC}"
    exit 1
fi
log "${GREEN}Build succeeded${NC} ($(percent_complete))"

if [ -n "$DOCS_SETS_FILTER" ]; then
    export DOCS_SETS="$DOCS_SETS_FILTER"
fi

app_jar=$(ls -1 build/libs/*.jar 2>/dev/null | grep -v -- "-plain.jar" | head -1 || true)
if [ -z "$app_jar" ]; then
    log "${RED}Failed to locate runnable jar${NC}"
    exit 1
fi

log "${YELLOW}Starting document processor...${NC}"
java -Dspring.profiles.active=cli \
     -DDOCS_DIR="$DOCS_ROOT" \
     -jar "$app_jar" >> "$LOG_FILE" 2>&1 &
APP_PID=$!

log "${BLUE}Application started with PID: $APP_PID${NC}"

start_time=$(date +%s)
last_files=0
while true; do
    if grep -q "DOCUMENT PROCESSING COMPLETE" "$LOG_FILE" 2>/dev/null; then
        echo ""
        log "${GREEN}Document processing completed${NC} ($(percent_complete))"
        break
    fi

    if ! kill -0 "$APP_PID" 2>/dev/null; then
        echo ""
        log "${RED}Application terminated unexpectedly${NC}"
        exit 1
    fi

    current_time=$(date +%s)
    elapsed=$((current_time - start_time))
    files_count=$(grep -c "Completed processing" "$LOG_FILE" 2>/dev/null || true)
    files_count=$(echo "${files_count:-0}" | tr -dc '0-9')
    files_count=${files_count:-0}
    percent=$(percent_complete)

    if [ "$files_count" -gt "$last_files" ]; then
        echo -ne "\r${YELLOW}[$percent] Files: $files_count (${elapsed}s)${NC}     "
        last_files=$files_count
    fi

    sleep 2
done

echo ""
log "${GREEN}Pipeline completed successfully${NC} ($(percent_complete))"
log "Log file: $LOG_FILE"
