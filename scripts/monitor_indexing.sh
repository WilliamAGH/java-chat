#!/bin/bash

# Real-time Indexing Monitor
# Shows actual progress of document indexing to Qdrant

# Load environment and shared libraries
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIR/lib/common_qdrant.sh"

load_env_file
apply_pipeline_defaults

# Configuration (use REST API)
QDRANT_BASE_URL="$(qdrant_rest_base_url)"
QDRANT_URL="${QDRANT_BASE_URL}/collections/${QDRANT_COLLECTION}"
LOG_FILE="$PROJECT_ROOT/process_qdrant.log"
REFRESH_INTERVAL=${1:-5}  # Default 5 seconds, or pass as argument

# Function to get Qdrant stats
get_qdrant_stats() {
    local qdrant_response
    if qdrant_response=$(qdrant_curl -s "$QDRANT_URL" 2>/dev/null); then
        echo "$qdrant_response" | jq -r '.result | "\(.points_count)|\(.vectors_count)|\(.indexed_vectors_count)"' 2>/dev/null || echo "0|0|0"
    else
        echo "0|0|0"
    fi
}

# Function to get embedding server status
check_embedding_server_status() {
    local probe_url="${LOCAL_EMBEDDING_SERVER_URL:-http://127.0.0.1:1234}/v1/models"
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" "$probe_url" 2>/dev/null)
    if [ "$http_code" = "200" ]; then
        echo "OK"
    else
        echo "UNAVAILABLE"
    fi
}

# Function to count log events
count_log_events() {
    local pattern="$1"
    grep -c "$pattern" "$LOG_FILE" 2>/dev/null || true
}

# Function to get last log entry
get_last_log() {
    local pattern="$1"
    local last=$(grep "$pattern" "$LOG_FILE" 2>/dev/null | tail -1)
    if [ -n "$last" ]; then
        # Extract just the message part, removing timestamp and formatting
        echo "$last" | sed 's/.*\] //' | sed 's/\[[0-9;]*m//g' | cut -c1-60
    else
        echo "None"
    fi
}

# Initialize
clear
echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}${CYAN}           REAL-TIME INDEXING MONITOR${NC}"
echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo ""

# Main monitoring loop
PREV_VECTORS=0
START_TIME=$(date +%s)
LAST_UPDATE_TIME=$START_TIME

while true; do
    # Get current stats
    CURRENT_TIME=$(date +%s)
    ELAPSED=$((CURRENT_TIME - START_TIME))
    
    # Get Qdrant stats
    IFS='|' read -r POINTS VECTORS INDEXED <<< "$(get_qdrant_stats)"
    
    # Calculate rates
    if [ "$ELAPSED" -gt 0 ]; then
        VECTOR_RATE=$(echo "scale=2; $VECTORS / $ELAPSED" | bc 2>/dev/null || echo "0")
    else
        VECTOR_RATE="0"
    fi
    
    # Calculate delta since last update
    VECTOR_DELTA=$((VECTORS - PREV_VECTORS))
    TIME_DELTA=$((CURRENT_TIME - LAST_UPDATE_TIME))
    if [ "$TIME_DELTA" -gt 0 ] && [ "$VECTOR_DELTA" -gt 0 ]; then
        CURRENT_RATE=$(echo "scale=2; $VECTOR_DELTA / $TIME_DELTA" | bc 2>/dev/null || echo "0")
    else
        CURRENT_RATE="0"
    fi
    
    # Get processing stats from logs
    EMBEDDINGS_GENERATED=$(count_log_events "Generated.*embeddings successfully")
    EMBEDDINGS_CALLED=$(count_log_events "Calling embedding API")
    DOCS_PROCESSED=$(count_log_events "✓ Processed")
    ERRORS=$(count_log_events "ERROR")
    WARNINGS=$(count_log_events "WARN")
    
    # Get last activity
    LAST_EMBEDDING=$(get_last_log "LocalEmbeddingClient")
    LAST_ERROR=$(get_last_log "ERROR")
    
    # Check services
    EMBEDDING_STATUS=$(check_embedding_server_status)
    APP_RUNNING=$(pgrep -f "java.*java-chat" > /dev/null && echo "✓" || echo "✗")
    
    # Clear screen and display dashboard
    clear
    echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}${CYAN}           REAL-TIME INDEXING MONITOR${NC}"
    echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
    
    # Qdrant Status
    echo -e "${BOLD}${YELLOW}QDRANT STATUS${NC}"
    echo -e "├─ Collection: ${CYAN}$QDRANT_COLLECTION${NC}"
    echo -e "├─ Vectors: ${GREEN}${BOLD}$VECTORS${NC}"
    if [ "$VECTOR_DELTA" -gt 0 ]; then
        echo -e "├─ Change: ${GREEN}+$VECTOR_DELTA${NC} in last ${TIME_DELTA}s"
    else
        echo -e "├─ Change: ${YELLOW}No change${NC} in last ${TIME_DELTA}s"
    fi
    echo -e "├─ Avg Rate: ${BLUE}$VECTOR_RATE${NC} vectors/sec"
    echo -e "└─ Current Rate: ${BLUE}$CURRENT_RATE${NC} vectors/sec"
    echo ""
    
    # Services Status
    echo -e "${BOLD}${YELLOW}SERVICES${NC}"
    echo -e "├─ Java App: $([[ "$APP_RUNNING" == "✓" ]] && echo -e "${GREEN}Running${NC}" || echo -e "${RED}Stopped${NC}")"
    echo -e "└─ Embedding Server: $([[ "$EMBEDDING_STATUS" == "OK" ]] && echo -e "${GREEN}Healthy${NC}" || echo -e "${RED}Unavailable${NC}")"
    echo ""
    
    # Processing Stats
    echo -e "${BOLD}${YELLOW}PROCESSING STATS${NC}"
    echo -e "├─ Documents Processed: ${CYAN}$DOCS_PROCESSED${NC}"
    echo -e "├─ Embedding API Calls: ${CYAN}$EMBEDDINGS_CALLED${NC}"
    echo -e "├─ Embeddings Generated: ${GREEN}$EMBEDDINGS_GENERATED${NC}"
    echo -e "├─ Errors: $([[ "$ERRORS" -gt 0 ]] && echo -e "${RED}$ERRORS${NC}" || echo -e "${GREEN}0${NC}")"
    echo -e "└─ Warnings: $([[ "$WARNINGS" -gt 0 ]] && echo -e "${YELLOW}$WARNINGS${NC}" || echo -e "${GREEN}0${NC}")"
    echo ""
    
    # Last Activity
    echo -e "${BOLD}${YELLOW}LAST ACTIVITY${NC}"
    echo -e "├─ Embedding: ${BLUE}$LAST_EMBEDDING${NC}"
    if [ "$ERRORS" -gt 0 ]; then
        echo -e "└─ Error: ${RED}$LAST_ERROR${NC}"
    else
        echo -e "└─ Error: ${GREEN}None${NC}"
    fi
    echo ""
    
    # Progress Estimation
    if [ "$VECTORS" -gt 0 ] && [ "$CURRENT_RATE" != "0" ]; then
        # Estimate based on typical document count
        ESTIMATED_TOTAL=60000
        REMAINING=$((ESTIMATED_TOTAL - VECTORS))
        if [ "$REMAINING" -gt 0 ]; then
            ETA=$(echo "scale=0; $REMAINING / $CURRENT_RATE / 60" | bc 2>/dev/null || echo "?")
            echo -e "${BOLD}${YELLOW}ESTIMATED TIME${NC}"
            echo -e "├─ Progress: ${GREEN}$(echo "scale=1; $VECTORS * 100 / $ESTIMATED_TOTAL" | bc)%${NC}"
            echo -e "└─ ETA: ${CYAN}~$ETA minutes${NC}"
            echo ""
        fi
    fi
    
    # Status bar
    echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "Last Update: $(date '+%H:%M:%S') | Refresh: ${REFRESH_INTERVAL}s | Press Ctrl+C to exit"
    
    # Store current values for next iteration
    PREV_VECTORS=$VECTORS
    LAST_UPDATE_TIME=$CURRENT_TIME
    
    sleep $REFRESH_INTERVAL
done
