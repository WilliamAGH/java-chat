#!/bin/bash

# Consolidated Documentation Fetcher with Deduplication
# This script fetches all required documentation (Java 24/25, Spring ecosystem)
# and ensures no redundant downloads by checking existing files

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Optional: centralized source URLs
if [ -f "$SCRIPT_DIR/docs_sources.sh" ]; then
  # shellcheck source=/dev/null
  . "$SCRIPT_DIR/docs_sources.sh"
fi
# Also allow sourcing from the Java resources properties to keep a single source of truth
RES_PROPS="$SCRIPT_DIR/../src/main/resources/docs-sources.properties"
if [ -f "$RES_PROPS" ]; then
  # Export variables defined as KEY=VALUE in the properties file
  set -a
  # shellcheck source=/dev/null
  . "$RES_PROPS"
  set +a
fi
DOCS_ROOT="$SCRIPT_DIR/../data/docs"
LOG_FILE="$SCRIPT_DIR/../fetch_all_docs.log"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=============================================="
echo "Consolidated Documentation Fetcher"
echo "=============================================="
echo "Docs root: $DOCS_ROOT"
echo "Log file: $LOG_FILE"
echo ""

# Initialize log
echo "[$(date)] Starting consolidated documentation fetch" > "$LOG_FILE"

# Function to log messages
log() {
    echo "[$(date)] $1" >> "$LOG_FILE"
    echo -e "$1"
}

# Function to check if directory has sufficient content
check_directory_content() {
    local dir="$1"
    local min_files="$2"
    local name="$3"
    
    if [ -d "$dir" ]; then
        local count=$(find "$dir" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')
        if [ "$count" -ge "$min_files" ]; then
            log "${GREEN}✓ $name already fetched: $count HTML files (minimum: $min_files)${NC}"
            return 0
        else
            log "${YELLOW}⚠ $name exists but incomplete: $count HTML files (expected: $min_files+)${NC}"
            return 1
        fi
    else
        log "${RED}✗ $name not found${NC}"
        return 1
    fi
}

# Function to fetch documentation with wget
fetch_docs() {
    local url="$1"
    local target_dir="$2"
    local name="$3"
    local cut_dirs="$4"
    
    log "${YELLOW}Fetching $name...${NC}"
    mkdir -p "$target_dir"
    cd "$target_dir"
    
    wget \
        --mirror \
        --convert-links \
        --adjust-extension \
        --page-requisites \
        --no-parent \
        --no-host-directories \
        --cut-dirs="$cut_dirs" \
        --reject="index.html?*" \
        --accept="*.html,*.css,*.js,*.png,*.gif,*.jpg,*.svg" \
        --quiet \
        --show-progress \
        --progress=bar:force \
        --timeout=30 \
        --tries=3 \
        "$url" 2>&1 | tee -a "$LOG_FILE"
    
    local result=$?
    cd - > /dev/null
    
    if [ $result -eq 0 ]; then
        local count=$(find "$target_dir" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')
        log "${GREEN}✓ $name fetched successfully: $count HTML files${NC}"
        return 0
    else
        log "${RED}✗ Failed to fetch $name (exit code: $result)${NC}"
        return 1
    fi
}

# Documentation sources configuration (using arrays for compatibility)
# Format: URL|TARGET_DIR|NAME|CUT_DIRS|MIN_FILES
DOC_SOURCES=(
    "${JAVA24_API_BASE:-https://docs.oracle.com/en/java/javase/24/docs/api/}|$DOCS_ROOT/java/java24-complete|Java 24 Complete API|5|9000"
    "${JAVA25_API_BASE:-https://docs.oracle.com/en/java/javase/25/docs/api/}|$DOCS_ROOT/java/java25-complete|Java 25 Complete API|5|8000"
    "${SPRING_BOOT_API_BASE:-https://docs.spring.io/spring-boot/docs/current/api/}|$DOCS_ROOT/spring-boot-complete|Spring Boot Complete API|5|7000"
    "${SPRING_FRAMEWORK_API_BASE:-https://docs.spring.io/spring-framework/docs/current/javadoc-api/}|$DOCS_ROOT/spring-framework-complete|Spring Framework Complete API|5|7000"
    "${SPRING_AI_API_BASE:-https://docs.spring.io/spring-ai/reference/1.0/api/}|$DOCS_ROOT/spring-ai-complete|Spring AI Complete API|6|200"
)

# Statistics
TOTAL_FETCHED=0
TOTAL_SKIPPED=0
TOTAL_FAILED=0

echo ""
log "Starting documentation fetch process..."
echo "=============================================="

# Process each documentation source
for source in "${DOC_SOURCES[@]}"; do
    IFS='|' read -r url target_dir name cut_dirs min_files <<< "$source"
    
    echo ""
    log "Processing: $name"
    log "URL: $url"
    log "Target: $target_dir"
    
    if check_directory_content "$target_dir" "$min_files" "$name"; then
        ((TOTAL_SKIPPED++))
    else
        if fetch_docs "$url" "$target_dir" "$name" "$cut_dirs"; then
            ((TOTAL_FETCHED++))
        else
            ((TOTAL_FAILED++))
            log "${YELLOW}Warning: Failed to fetch $name, continuing with others...${NC}"
        fi
    fi
done

# Fetch Java 25 from existing mirror if available
JAVA25_MIRROR="$DOCS_ROOT/java/java25-complete"
if [ -d "$JAVA25_MIRROR" ]; then
    count=$(find "$JAVA25_MIRROR" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$count" -ge 8000 ]; then
        log "${GREEN}✓ Java 25 Complete API already available: $count HTML files${NC}"
        ((TOTAL_SKIPPED++))
    fi
fi

echo ""
echo "=============================================="
echo "Documentation Fetch Summary"
echo "=============================================="
log "📊 Statistics:"
log "  - Newly fetched: $TOTAL_FETCHED"
log "  - Already present: $TOTAL_SKIPPED"
log "  - Failed: $TOTAL_FAILED"

# Count total files
TOTAL_HTML=$(find "$DOCS_ROOT" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')
TOTAL_FILES=$(find "$DOCS_ROOT" -type f 2>/dev/null | wc -l | tr -d ' ')

log "  - Total HTML files: $TOTAL_HTML"
log "  - Total files: $TOTAL_FILES"
log "  - Documentation root: $DOCS_ROOT"

echo ""
log "${GREEN}✅ Documentation fetch complete!${NC}"
log "Check log for details: $LOG_FILE"

# Create a marker file with fetch metadata
METADATA_FILE="$DOCS_ROOT/.fetch_metadata.json"
cat > "$METADATA_FILE" << EOF
{
  "last_fetch": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "statistics": {
    "newly_fetched": $TOTAL_FETCHED,
    "already_present": $TOTAL_SKIPPED,
    "failed": $TOTAL_FAILED,
    "total_html_files": $TOTAL_HTML,
    "total_files": $TOTAL_FILES
  },
  "directories": {
    "java24_complete": "$(find "$DOCS_ROOT/java/java24-complete" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "java25_complete": "$(find "$DOCS_ROOT/java/java25-complete" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_boot_complete": "$(find "$DOCS_ROOT/spring-boot-complete" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_framework_complete": "$(find "$DOCS_ROOT/spring-framework-complete" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_ai_complete": "$(find "$DOCS_ROOT/spring-ai-complete" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')"
  }
}
EOF

log "Metadata saved to: $METADATA_FILE"
echo ""
echo "Next step: Run 'make run' or './scripts/process_all_to_qdrant.sh' to process and upload to Qdrant"
