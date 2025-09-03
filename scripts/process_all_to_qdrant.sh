#!/bin/bash

# Consolidated Document Processor with Hash-based Deduplication
# This script processes all documentation with option for local caching or Qdrant upload
# Usage: ./process_all_to_qdrant.sh [--local-only | --upload]
#   --local-only: Cache embeddings locally without uploading to Qdrant (default)
#   --upload: Upload cached embeddings to Qdrant

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
DOCS_ROOT="$PROJECT_ROOT/data/docs"
HASH_DB="$PROJECT_ROOT/data/.processed_hashes.db"
LOG_FILE="$PROJECT_ROOT/process_qdrant.log"
CACHE_DIR="$PROJECT_ROOT/data/embeddings-cache"

# Parse command line arguments
UPLOAD_MODE="local-only"  # Default to local-only mode
for arg in "$@"; do
    case $arg in
        --local-only)
            UPLOAD_MODE="local-only"
            ;;
        --upload)
            UPLOAD_MODE="upload"
            ;;
        --help|-h)
            echo "Usage: $0 [--local-only | --upload]"
            echo "  --local-only : Cache embeddings locally without uploading (default)"
            echo "  --upload     : Upload cached embeddings to Qdrant"
            exit 0
            ;;
        *)
            echo "Unknown option: $arg"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo "=============================================="
echo "Document Processor with Deduplication"
echo "=============================================="
echo -e "${CYAN}Mode: ${YELLOW}${UPLOAD_MODE}${NC}"
echo "Project root: $PROJECT_ROOT"
echo "Docs root: $DOCS_ROOT"
echo "Hash database: $HASH_DB"
echo "Cache directory: $CACHE_DIR"
echo ""

# Load environment variables
if [ -f "$PROJECT_ROOT/.env" ]; then
    set -a
    source "$PROJECT_ROOT/.env"
    set +a
    echo -e "${GREEN}✓ Environment variables loaded${NC}"
else
    echo -e "${RED}✗ .env file not found${NC}"
    exit 1
fi

# Verify required environment variables based on mode
if [ "$UPLOAD_MODE" = "upload" ]; then
    required_vars=("QDRANT_HOST" "QDRANT_PORT" "QDRANT_API_KEY" "QDRANT_COLLECTION" "APP_LOCAL_EMBEDDING_ENABLED")
else
    # Local-only mode doesn't need Qdrant vars
    required_vars=("APP_LOCAL_EMBEDDING_ENABLED")
fi

missing_vars=()
for var in "${required_vars[@]}"; do
    if [ -z "${!var}" ]; then
        missing_vars+=("$var")
    fi
done

if [ ${#missing_vars[@]} -gt 0 ]; then
    echo -e "${RED}✗ Missing required environment variables:${NC}"
    printf '%s\n' "${missing_vars[@]}"
    exit 1
fi

echo -e "${GREEN}✓ All required environment variables present${NC}"
echo ""

# Function to log messages
log() {
    echo "[$(date)] $1" >> "$LOG_FILE"
    echo -e "$1"
}

# Initialize log
echo "[$(date)] Starting document processing in $UPLOAD_MODE mode" > "$LOG_FILE"

# Compute overall % complete (indexed vs parsed)
percent_complete() {
    if [ "$UPLOAD_MODE" = "local-only" ]; then
        # For local mode, show cache statistics
        if [ -d "$CACHE_DIR" ]; then
            local cache_files=$(find "$CACHE_DIR" -name "*.gz" 2>/dev/null | wc -l | tr -d ' ')
            echo "Cache: $cache_files files"
        else
            echo "Cache: 0 files"
        fi
    else
        # Original Qdrant-based percentage
        local parsed_dir="$PROJECT_ROOT/data/parsed"
        local index_dir="$PROJECT_ROOT/data/index"
        local parsed_count=0
        local indexed_count=0
        if [ -d "$parsed_dir" ]; then
            parsed_count=$(find "$parsed_dir" -type f -name "*.txt" 2>/dev/null | wc -l | tr -d ' ')
        fi
        if [ -d "$index_dir" ]; then
            indexed_count=$(ls -1 "$index_dir" 2>/dev/null | wc -l | tr -d ' ')
        fi
        if [ "$parsed_count" -gt 0 ]; then
            awk -v i="$indexed_count" -v p="$parsed_count" 'BEGIN { printf("%.1f%%", (i/p)*100) }'
        else
            # Fallback: use Qdrant points vs 60k estimate
            local url="https://$QDRANT_HOST/collections/$QDRANT_COLLECTION"
            local pts=$(curl -s -H "api-key: $QDRANT_API_KEY" "$url" | grep -o '"points_count":[0-9]*' | cut -d: -f2)
            pts=${pts:-0}
            awk -v i="$pts" 'BEGIN { printf("%.1f%%", (i/60000)*100) }'
        fi
    fi
}

# Function to get SHA-256 hash of a file
get_file_hash() {
    local file="$1"
    if [[ "$OSTYPE" == "darwin"* ]]; then
        shasum -a 256 "$file" | cut -d' ' -f1
    else
        sha256sum "$file" | cut -d' ' -f1
    fi
}

# Initialize hash database if it doesn't exist
init_hash_db() {
    if [ ! -f "$HASH_DB" ]; then
        mkdir -p "$(dirname "$HASH_DB")"
        echo "# Processed file hashes database" > "$HASH_DB"
        echo "# Format: hash|filepath|timestamp|status" >> "$HASH_DB"
        log "${GREEN}✓ Created hash database${NC}"
    else
        local count=$(grep -v '^#' "$HASH_DB" 2>/dev/null | wc -l | tr -d ' ')
        log "${BLUE}ℹ Found existing hash database with $count entries${NC}"
    fi
}

# Check if file has been processed
is_processed() {
    local file="$1"
    local hash=$(get_file_hash "$file")
    grep -q "^$hash|" "$HASH_DB" 2>/dev/null
}

# Mark file as processed
mark_processed() {
    local file="$1"
    local status="$2"
    local hash=$(get_file_hash "$file")
    echo "$hash|$file|$(date -u +%Y-%m-%dT%H:%M:%SZ)|$status" >> "$HASH_DB"
}

# Function to check Qdrant connection (only in upload mode)
check_qdrant_connection() {
    if [ "$UPLOAD_MODE" = "local-only" ]; then
        log "${YELLOW}ℹ Running in local-only mode - Qdrant connection not required${NC}"
        return 0
    fi
    
    log "${YELLOW}Checking Qdrant connection...${NC}"
    
    local url="https://$QDRANT_HOST/collections/$QDRANT_COLLECTION"
    local response=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "api-key: $QDRANT_API_KEY" \
        "$url")
    
    if [ "$response" = "200" ]; then
        log "${GREEN}✓ Qdrant connection successful${NC} ($(percent_complete))"
        
        # Get collection info
        local info=$(curl -s -H "api-key: $QDRANT_API_KEY" "$url")
        local points=$(echo "$info" | grep -o '"points_count":[0-9]*' | cut -d: -f2)
        local dimensions=$(echo "$info" | grep -o '"size":[0-9]*' | head -1 | cut -d: -f2)
        
        log "${BLUE}ℹ Collection: $QDRANT_COLLECTION${NC}"
        log "${BLUE}ℹ Current vectors: ${points:-0}${NC}"
        log "${BLUE}ℹ Dimensions: ${dimensions:-unknown}${NC}"
        return 0
    else
        log "${RED}✗ Failed to connect to Qdrant (HTTP $response)${NC}"
        return 1
    fi
}

# Function to check embedding server
check_embedding_server() {
    if [ "$APP_LOCAL_EMBEDDING_ENABLED" = "true" ]; then
        log "${YELLOW}Checking local embedding server...${NC}"
        
        local url="${LOCAL_EMBEDDING_SERVER_URL:-http://127.0.0.1:1234}/v1/models"
        local response=$(curl -s -o /dev/null -w "%{http_code}" "$url")
        
        if [ "$response" = "200" ]; then
            log "${GREEN}✓ Local embedding server is healthy${NC} ($(percent_complete))"
            return 0
        else
            log "${RED}✗ Local embedding server not responding (HTTP $response)${NC}"
            return 1
        fi
    else
        log "${BLUE}ℹ Using OpenAI embeddings${NC}"
        return 0
    fi
}

# Function to trigger cache upload (for upload mode)
trigger_cache_upload() {
    log "${YELLOW}Triggering cache upload to Qdrant...${NC}"
    
    local response=$(curl -s -X POST "http://localhost:${PORT:-8085}/api/embeddings-cache/upload?batchSize=100")
    local uploaded=$(echo "$response" | grep -o '"uploaded":[0-9]*' | cut -d: -f2)
    
    if [ -n "$uploaded" ] && [ "$uploaded" -gt 0 ]; then
        log "${GREEN}✓ Successfully uploaded $uploaded embeddings to Qdrant${NC}"
    else
        log "${YELLOW}ℹ No new embeddings to upload${NC}"
    fi
}

# Main processing function
process_documents() {
    # Check if application is already running
    if pgrep -f "java.*java-chat" > /dev/null; then
        log "${YELLOW}⚠ Java application is already running. Stopping it...${NC}"
        pkill -f "java.*java-chat" || true
        sleep 2
    fi
    
    # Build the application
    log "${YELLOW}Building application...${NC}"
    cd "$PROJECT_ROOT"
    ./mvnw -DskipTests clean package >> "$LOG_FILE" 2>&1
    
    if [ $? -eq 0 ]; then
        log "${GREEN}✓ Application built successfully${NC} ($(percent_complete))"
    else
        log "${RED}✗ Failed to build application${NC}"
        return 1
    fi
    
    # Set environment variable for upload mode
    export EMBEDDINGS_UPLOAD_MODE="$UPLOAD_MODE"
    
    # Start the application with document processor
    log "${YELLOW}Starting document processor in ${CYAN}${UPLOAD_MODE}${YELLOW} mode...${NC}"
    
    # Run DocumentProcessor with cli profile for document ingestion
    cd "$PROJECT_ROOT"
    if [ -f .env ]; then
        set -a
        source .env
        set +a
    fi
    
    # Run with cli profile to trigger DocumentProcessor
    java -Dspring.profiles.active=cli \
         -DEMBEDDINGS_UPLOAD_MODE="$UPLOAD_MODE" \
         -DDOCS_DIR="$DOCS_ROOT" \
         -jar target/*.jar >> "$LOG_FILE" 2>&1 &
    local APP_PID=$!
    
    log "${BLUE}ℹ Application started with PID: $APP_PID${NC}"
    log "${YELLOW}Processing documents (this may take several minutes)...${NC}"
    
    # Monitor the log for completion
    local start_time=$(date +%s)
    
    while true; do
        if grep -q "DOCUMENT PROCESSING COMPLETE" "$LOG_FILE" 2>/dev/null; then
            log "${GREEN}✓ Document processing completed${NC} ($(percent_complete))"
            
            # Extract statistics
            local total_processed=$(grep "Total new documents processed:" "$LOG_FILE" | tail -1 | grep -o '[0-9]*')
            local total_duplicates=$(grep "Total duplicates skipped:" "$LOG_FILE" | tail -1 | grep -o '[0-9]*')
            
            log "${BLUE}ℹ Documents processed: ${total_processed:-0}${NC}"
            log "${BLUE}ℹ Duplicates skipped: ${total_duplicates:-0}${NC}"
            
            # If in upload mode, trigger the cache upload
            if [ "$UPLOAD_MODE" = "upload" ]; then
                sleep 2  # Give the app a moment to stabilize
                trigger_cache_upload
            fi
            
            break
        fi
        
        if ! kill -0 $APP_PID 2>/dev/null; then
            log "${RED}✗ Application terminated unexpectedly${NC}"
            return 1
        fi
        
        local current_time=$(date +%s)
        local elapsed=$((current_time - start_time))
        
        # Show progress
        local processed_count=$(grep -c "✓ Processed" "$LOG_FILE" 2>/dev/null || echo "0")
        echo -ne "\r${YELLOW}Progress: $processed_count document sets processed... (${elapsed}s elapsed)${NC}"
        
        sleep 5
    done
    
    echo ""  # New line after progress indicator
    
    # Application will continue running for API access
    log "${GREEN}✓ Application is running on port ${PORT:-8085}${NC} ($(percent_complete))"
    log "${BLUE}ℹ Access the chat at: http://localhost:${PORT:-8085}${NC}"
    
    return 0
}

# Function to get final statistics
show_statistics() {
    log ""
    log "=============================================="
    log "Processing Statistics"
    log "=============================================="
    
    if [ "$UPLOAD_MODE" = "upload" ]; then
        # Get Qdrant statistics
        local url="https://$QDRANT_HOST/collections/$QDRANT_COLLECTION"
        local info=$(curl -s -H "api-key: $QDRANT_API_KEY" "$url")
        local points=$(echo "$info" | grep -o '"points_count":[0-9]*' | cut -d: -f2)
        
        log "${BLUE}📊 Qdrant Statistics:${NC}"
        log "  - Collection: $QDRANT_COLLECTION"
        log "  - Total vectors: ${points:-0}"
        log "  - Host: $QDRANT_HOST"
    else
        log "${BLUE}📊 Local Cache Statistics:${NC}"
        if [ -d "$CACHE_DIR" ]; then
            local cache_files=$(find "$CACHE_DIR" -name "*.gz" 2>/dev/null | wc -l | tr -d ' ')
            local cache_size=$(du -sh "$CACHE_DIR" 2>/dev/null | cut -f1)
            log "  - Cache files: $cache_files"
            log "  - Cache size: ${cache_size:-0}"
            log "  - Cache directory: $CACHE_DIR"
        else
            log "  - No cache files yet"
        fi
    fi
    
    # Get documentation statistics
    log ""
    log "${BLUE}📚 Documentation Statistics:${NC}"
    
    for dir in "$DOCS_ROOT"/*/; do
        if [ -d "$dir" ]; then
            local name=$(basename "$dir")
            local count=$(find "$dir" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')
            if [ "$count" -gt 0 ]; then
                log "  - $name: $count HTML files"
            fi
        fi
    done
    
    # Get hash database statistics
    if [ -f "$HASH_DB" ]; then
        local hash_count=$(grep -v '^#' "$HASH_DB" 2>/dev/null | wc -l | tr -d ' ')
        log ""
        log "${BLUE}🔒 Deduplication Statistics:${NC}"
        log "  - Tracked files: $hash_count"
    fi
    
    # Show mode-specific instructions
    log ""
    if [ "$UPLOAD_MODE" = "local-only" ]; then
        log "${CYAN}💡 To upload cached embeddings to Qdrant later:${NC}"
        log "  1. Run: ${YELLOW}./scripts/process_all_to_qdrant.sh --upload${NC}"
        log "  2. Or use API: ${YELLOW}curl -X POST http://localhost:${PORT:-8085}/api/embeddings-cache/upload${NC}"
    fi
}

# Main execution
main() {
    log "Starting document processing pipeline in ${CYAN}${UPLOAD_MODE}${NC} mode..."
    
    # Initialize hash database
    init_hash_db
    
    # Check prerequisites
    if [ "$UPLOAD_MODE" = "upload" ]; then
        if ! check_qdrant_connection; then
            log "${RED}✗ Cannot proceed without Qdrant connection${NC}"
            exit 1
        fi
    fi
    
    if ! check_embedding_server; then
        log "${YELLOW}⚠ Warning: Embedding server issues detected${NC}"
        read -p "Continue anyway? (y/n) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
    
    # Process documents
    if process_documents; then
        show_statistics
        log ""
        log "${GREEN}✅ Processing pipeline completed successfully!${NC} ($(percent_complete))"
        log "Check log for details: $LOG_FILE"
    else
        log "${RED}✗ Processing pipeline failed${NC}"
        log "Check log for details: $LOG_FILE"
        exit 1
    fi
}

# Run main function
main