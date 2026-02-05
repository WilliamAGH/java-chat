#!/bin/bash

# Consolidated Documentation Fetcher with Deduplication
# This script fetches all required documentation (Java 24/25, Spring ecosystem)
# and ensures no redundant downloads by checking existing files

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Centralized source URLs (single source of truth)
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

# Options
INCLUDE_QUICK="${INCLUDE_QUICK:-false}"
CLEAN_INCOMPLETE="${CLEAN_INCOMPLETE:-true}"
for arg in "$@"; do
    case $arg in
        --include-quick)
            INCLUDE_QUICK="true"
            ;;
        --no-clean)
            CLEAN_INCOMPLETE="false"
            ;;
        --help|-h)
            echo "Usage: $0 [--include-quick] [--no-clean]"
            echo "  --include-quick : Also refresh small 'quick' doc mirrors"
            echo "  --no-clean      : Do not quarantine incomplete mirrors before refetch"
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

count_html_files() {
    local dir="$1"
    if [ -d "$dir" ]; then
        find "$dir" -name "*.html" 2>/dev/null | wc -l | tr -d ' '
    else
        echo "0"
    fi
}

quarantine_incomplete_dir() {
    local dir="$1"
    local name="$2"
    local html_count="$3"
    local min_files="$4"

    if [ "$CLEAN_INCOMPLETE" != "true" ]; then
        return 0
    fi
    if [ ! -d "$dir" ]; then
        return 0
    fi
    if [ "$html_count" -ge "$min_files" ]; then
        return 0
    fi

    local quarantine_root="$DOCS_ROOT/.quarantine"
    local timestamp
    timestamp="$(date -u +%Y%m%d_%H%M%S)"
    mkdir -p "$quarantine_root"

    local base
    base="$(basename "$dir")"
    local quarantine_dir="$quarantine_root/${base}.${timestamp}"
    log "${YELLOW}⚠ Quarantining incomplete mirror: $name ($html_count HTML files; expected $min_files+) -> $quarantine_dir${NC}"
    mv "$dir" "$quarantine_dir"
}

# Function to fetch documentation with wget (incremental via timestamping)
fetch_docs() {
    local url="$1"
    local target_dir="$2"
    local name="$3"
    local cut_dirs="$4"
    local min_files="$5"
    local reject_regex="${6:-}"

    # Allow config-friendly placeholder for regex alternation without breaking our field delimiter.
    reject_regex="${reject_regex//__OR__/|}"

    local existing_count
    existing_count="$(count_html_files "$target_dir")"
    if [ "$existing_count" -gt 0 ]; then
        log "${BLUE}ℹ Existing mirror: $existing_count HTML files${NC}"
    fi
    if [ "$min_files" -gt 0 ] && [ "$existing_count" -gt 0 ] && [ "$existing_count" -lt "$min_files" ]; then
        quarantine_incomplete_dir "$target_dir" "$name" "$existing_count" "$min_files"
    fi

    log "${YELLOW}Fetching $name...${NC}"
    mkdir -p "$target_dir"
    cd "$target_dir"

    local wget_args=(
        --mirror \
        --convert-links \
        --adjust-extension \
        --page-requisites \
        --no-parent \
        --no-host-directories \
        --cut-dirs="$cut_dirs" \
        --reject="index.html?*" \
        --accept="*.html,*.css,*.js,*.png,*.gif,*.jpg,*.jpeg,*.svg,*.pdf,*.woff,*.woff2,*.ttf,*.eot" \
        --quiet \
        --show-progress \
        --progress=bar:force \
        --timeout=30 \
        --dns-timeout=30 \
        --connect-timeout=30 \
        --read-timeout=30 \
        --tries=3 \
        --waitretry=1 \
        --retry-connrefused \
        --no-verbose \
    )

    if [ -n "$reject_regex" ]; then
        wget_args+=(--reject-regex="$reject_regex")
    fi

    wget "${wget_args[@]}" "$url" 2>&1 | tee -a "$LOG_FILE"

    local result=$?
    cd - > /dev/null
    
    if [ $result -eq 0 ]; then
        local count
        count="$(count_html_files "$target_dir")"
        log "${GREEN}✓ $name fetched successfully: $count HTML files${NC}"
        if [ "$min_files" -gt 0 ] && [ "$count" -lt "$min_files" ]; then
            log "${RED}✗ $name mirror is still incomplete after fetch: $count HTML files (expected $min_files+)${NC}"
            return 1
        fi
        return 0
    else
        log "${RED}✗ Failed to fetch $name (exit code: $result)${NC}"
        return 1
    fi
}

# Documentation sources configuration
# Format: URL|TARGET_DIR|NAME|CUT_DIRS|MIN_FILES|REJECT_REGEX
DOC_SOURCES=(
    "${JAVA24_API_BASE:-https://docs.oracle.com/en/java/javase/24/docs/api/}|$DOCS_ROOT/java/java24-complete|Java 24 Complete API|5|9000|"
    "${JAVA25_API_BASE:-https://docs.oracle.com/en/java/javase/25/docs/api/}|$DOCS_ROOT/java/java25-complete|Java 25 Documentation|5|8000|"
    "${JAVA25_RELEASE_NOTES_ISSUES_URL:-https://www.oracle.com/java/technologies/javase/25-relnote-issues.html}|$DOCS_ROOT/oracle/javase|Java 25 Release Notes Issues|3|1|"
    "${IBM_JAVA25_ARTICLE_URL:-https://developer.ibm.com/articles/java-whats-new-java25/}|$DOCS_ROOT/ibm/articles|IBM Java 25 Overview|1|1|"
    "${JETBRAINS_JAVA25_BLOG_URL:-https://blog.jetbrains.com/idea/2025/09/java-25-lts-and-intellij-idea/}|$DOCS_ROOT/jetbrains/idea/2025/09|JetBrains Java 25 Blog|3|1|"

    # Spring Boot (current)
    "${SPRING_BOOT_REFERENCE_BASE:-https://docs.spring.io/spring-boot/reference/}|$DOCS_ROOT/spring-boot-complete|Spring Boot Reference (current)|1|50|"
    "${SPRING_BOOT_API_BASE:-https://docs.spring.io/spring-boot/api/}|$DOCS_ROOT/spring-boot-complete|Spring Boot API (current)|1|7000|"

    # Spring Framework (current) - avoid pulling older reference versions under /reference/6.x, etc.
    "${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|$DOCS_ROOT/spring-framework-complete|Spring Framework Reference (current)|1|3000|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT"
    "${SPRING_FRAMEWORK_API_BASE:-https://docs.spring.io/spring-framework/docs/current/javadoc-api/}|$DOCS_ROOT/spring-framework-complete|Spring Framework Javadoc (current)|1|7000|"

    # Spring AI (current) - avoid pulling older reference versions under /reference/1.0, etc.
    "${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|$DOCS_ROOT/spring-ai-complete|Spring AI Reference (current)|1|200|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT"
    "${SPRING_AI_API_BASE:-https://docs.spring.io/spring-ai/reference/api/}|$DOCS_ROOT/spring-ai-complete|Spring AI API (current)|1|200|"
)

if [ "$INCLUDE_QUICK" = "true" ]; then
    DOC_SOURCES+=(
        "${SPRING_BOOT_REFERENCE_BASE:-https://docs.spring.io/spring-boot/reference/}|$DOCS_ROOT/spring-boot|Spring Boot Quick (reference landing)|1|1|"
        "${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|$DOCS_ROOT/spring-framework|Spring Framework Quick (reference landing)|1|1|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT"
        "${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|$DOCS_ROOT/spring-ai|Spring AI Quick (reference landing)|1|1|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT"
    )
fi

# Statistics
TOTAL_FETCHED=0
TOTAL_FAILED=0

echo ""
log "Starting documentation fetch process..."
echo "=============================================="

# Process each documentation source
for source in "${DOC_SOURCES[@]}"; do
    IFS='|' read -r url target_dir name cut_dirs min_files reject_regex <<< "$source"
    
    echo ""
    log "Processing: $name"
    log "URL: $url"
    log "Target: $target_dir"
    
    if fetch_docs "$url" "$target_dir" "$name" "$cut_dirs" "$min_files" "${reject_regex:-}"; then
        ((TOTAL_FETCHED++))
    else
        ((TOTAL_FAILED++))
        log "${YELLOW}Warning: Failed to fetch $name, continuing with others...${NC}"
    fi
done

echo ""
echo "=============================================="
echo "Documentation Fetch Summary"
echo "=============================================="
log "📊 Statistics:"
log "  - Newly fetched: $TOTAL_FETCHED"
log "  - Failed: $TOTAL_FAILED"
log "  - Include quick mirrors: $INCLUDE_QUICK"
log "  - Clean incomplete mirrors: $CLEAN_INCOMPLETE"

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
