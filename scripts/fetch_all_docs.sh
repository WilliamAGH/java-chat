#!/bin/bash

# Consolidated Documentation Fetcher with Deduplication
# This script fetches all required documentation (configured Java APIs, Spring ecosystem)
# and ensures no redundant downloads by checking existing files

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=lib/shell_bootstrap.sh
source "$SCRIPT_DIR/lib/shell_bootstrap.sh"
# shellcheck source=lib/env_loader.sh
source "$SCRIPT_DIR/lib/env_loader.sh"
# shellcheck source=lib/documentation_sources.sh
source "$SCRIPT_DIR/lib/documentation_sources.sh"

# Centralized source definitions
RES_PROPS="$SCRIPT_DIR/../src/main/resources/docs-sources.properties"
JAVA_API_SOURCES_MANIFEST="$SCRIPT_DIR/../src/main/resources/java-api-documentation-sources.manifest"
if [ -f "$RES_PROPS" ]; then
  preserve_process_env_then_source_file "$RES_PROPS"
fi
DOCS_ROOT="$SCRIPT_DIR/../data/docs"
LOG_FILE="$SCRIPT_DIR/../fetch_all_docs.log"

# Options
INCLUDE_QUICK="${INCLUDE_QUICK:-false}"
CLEAN_INCOMPLETE="${CLEAN_INCOMPLETE:-true}"
FORCE_REFRESH="${FORCE_REFRESH:-false}"
LIST_JAVA_API_SOURCES="false"
for arg in "$@"; do
    case $arg in
        --include-quick)
            INCLUDE_QUICK="true"
            ;;
        --no-clean)
            CLEAN_INCOMPLETE="false"
            ;;
        --force)
            FORCE_REFRESH="true"
            ;;
        --list-java-api-sources)
            LIST_JAVA_API_SOURCES="true"
            ;;
        --help|-h)
            echo "Usage: $0 [--include-quick] [--no-clean] [--force] [--list-java-api-sources]"
            echo "  --include-quick : Also refresh small 'quick' doc mirrors"
            echo "  --no-clean      : Do not quarantine incomplete mirrors before refetch"
            echo "  --force         : Refresh even when mirrors look complete"
            echo "  --list-java-api-sources : Print canonical Java API source projections without fetching"
            exit 0
            ;;
        *)
            echo "Unknown option: $arg"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

extract_meta_version() {
    local file_path="$1"
    if [ -z "$file_path" ] || [ ! -f "$file_path" ]; then
        echo ""
        return 0
    fi
    local line
    line="$(grep -E '<meta name="version" content="[^"]+"' "$file_path" 2>/dev/null | head -n 1 || true)"
    if [ -z "$line" ]; then
        echo ""
        return 0
    fi
    echo "$line" | sed -E 's/.*content="([^"]+)".*/\1/'
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

quarantine_path() {
    local dir="$1"
    local name="$2"

    if [ "$CLEAN_INCOMPLETE" != "true" ]; then
        return 0
    fi
    if [ -z "$dir" ] || [ ! -e "$dir" ]; then
        return 0
    fi

    local quarantine_root="$DOCS_ROOT/.quarantine"
    local timestamp
    timestamp="$(date -u +%Y%m%d_%H%M%S)"
    mkdir -p "$quarantine_root"

    local base
    base="$(basename "$dir")"
    local quarantine_dir="$quarantine_root/${base}.${timestamp}"
    log "${YELLOW}⚠ Quarantining legacy mirror path: $name -> $quarantine_dir${NC}"
    mv "$dir" "$quarantine_dir"
}

quarantine_versioned_reference_subdirs() {
    local target_dir="$1"
    local name="$2"
    local allow_regex="${3:-}"

    if [ "$CLEAN_INCOMPLETE" != "true" ]; then
        return 0
    fi
    if [ -z "$target_dir" ] || [ ! -d "$target_dir/reference" ]; then
        return 0
    fi

    shopt -s nullglob
    local child
    for child in "$target_dir/reference/"*; do
        local child_base
        child_base="$(basename "$child")"
        if [ -n "$allow_regex" ] && [[ "$child_base" =~ $allow_regex ]]; then
            continue
        fi
        if [[ "$child_base" =~ ^[0-9] ]] || [[ "$child_base" == *SNAPSHOT* ]]; then
            quarantine_path "$child" "$name reference/$child_base"
        fi
    done
    shopt -u nullglob
}

# Validates a wget fetch result: checks exit code, counts HTML files, and
# enforces the minimum-files threshold.  Returns 0 on success, 1 on failure.
#
# Arguments:
#   $1 - wget exit code
#   $2 - target directory (for HTML count)
#   $3 - human-readable name
#   $4 - minimum required HTML files (0 = no minimum)
#   $5 - whether a validated partial mirror is accepted
validate_fetch_result() {
    local wget_exit_code="$1"
    local target_dir="$2"
    local name="$3"
    local min_files="$4"
    local partial_mirror_allowed="$5"

    local fetched_html_count
    fetched_html_count="$(count_html_files "$target_dir")"

    # wget returns 8 for HTTP errors (e.g., a few 404s) even when the mirror is usable.
    # Prefer our post-fetch validation over raw exit status.
    if [ "$wget_exit_code" -ne 0 ] && [ "$wget_exit_code" -ne 8 ]; then
        log "${RED}✗ Failed to fetch $name (exit code: $wget_exit_code)${NC}"
        return 1
    fi

    log "${GREEN}✓ $name fetched successfully: $fetched_html_count HTML files${NC}"
    if [ "$min_files" -gt 0 ] && [ "$fetched_html_count" -lt "$min_files" ]; then
        if [ "$partial_mirror_allowed" = "true" ]; then
            log "${YELLOW}⚠ $name mirror is still incomplete after fetch: $fetched_html_count HTML files (expected $min_files+); keeping partial mirror for incremental reruns${NC}"
        else
            log "${RED}✗ $name mirror is still incomplete after fetch: $fetched_html_count HTML files (expected $min_files+)${NC}"
            return 1
        fi
    fi
    return 0
}

# Fetches Oracle Javadoc using an explicit seed list derived from the Javadoc
# search indices.  This avoids incomplete recursive crawls that miss pages.
#
# Arguments:
#   $1 - Oracle Javadoc base URL
#   $2 - target directory
#   $3 - human-readable name
#   $4 - --cut-dirs value
#   $5 - minimum required HTML files
#   $6 - whether a validated partial mirror is accepted
fetch_oracle_javadoc_seed() {
    local url="$1"
    local target_dir="$2"
    local name="$3"
    local cut_dirs="$4"
    local min_files="$5"
    local partial_mirror_allowed="$6"

    local seed_file="$target_dir/.oracle-javadoc-seed.txt"
    log "${BLUE}ℹ Oracle Javadoc detected; generating explicit URL seed list...${NC}"
    python3 "$SCRIPT_DIR/oracle_javadoc_seed.py" --base-url "$url" --output "$seed_file" 2>&1 | tee -a "$LOG_FILE"
    local seed_url_count
    seed_url_count="$(wc -l "$seed_file" | awk '{print $1}')"
    log "${BLUE}ℹ Oracle Javadoc seed URLs: $seed_url_count${NC}"

    local wget_seed_args=(
        --timestamping
        --no-host-directories
        --cut-dirs="$cut_dirs"
        --input-file="$seed_file"
        --directory-prefix="$target_dir"
        --show-progress
        --progress=bar:force
        --timeout=120
        --dns-timeout=30
        --connect-timeout=30
        --read-timeout=120
        --tries=5
        --waitretry=1
        --retry-connrefused
        --user-agent="java-chat-doc-fetcher/1.0"
    )

    wget "${wget_seed_args[@]}" 2>&1 | tee -a "$LOG_FILE"
    local wget_exit_code=$?
    cd - > /dev/null

    validate_fetch_result "$wget_exit_code" "$target_dir" "$name" "$min_files" "$partial_mirror_allowed"
}

# Fetches documentation using wget --mirror for generic (non-Oracle) sites.
#
# Arguments:
#   $1 - URL to mirror
#   $2 - target directory
#   $3 - human-readable name
#   $4 - --cut-dirs value
#   $5 - minimum required HTML files
#   $6 - reject regex (optional)
#   $7 - whether a validated partial mirror is accepted
fetch_docs_mirror() {
    local url="$1"
    local target_dir="$2"
    local name="$3"
    local cut_dirs="$4"
    local min_files="$5"
    local reject_regex="${6:-}"
    local partial_mirror_allowed="$7"

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
    local wget_exit_code=$?
    cd - > /dev/null

    validate_fetch_result "$wget_exit_code" "$target_dir" "$name" "$min_files" "$partial_mirror_allowed"
}

# Dispatches documentation fetching: performs pre-fetch housekeeping (existing
# mirror check, quarantine, legacy cleanup), then delegates to the appropriate
# strategy — seed-list for Oracle Javadoc, wget --mirror for everything else.
#
# Arguments (pipe-delimited config row):
#   $1 - URL
#   $2 - target directory
#   $3 - human-readable name
#   $4 - --cut-dirs value
#   $5 - minimum required HTML files
#   $6 - reject regex (optional)
#   $7 - whether a validated partial mirror is accepted
fetch_docs() {
    local url="$1"
    local target_dir="$2"
    local name="$3"
    local cut_dirs="$4"
    local min_files="$5"
    local reject_regex="${6:-}"
    local partial_mirror_allowed="$7"

    # Allow config-friendly placeholder for regex alternation without breaking our field delimiter.
    reject_regex="${reject_regex//__OR__/|}"

    # ── Pre-fetch: check existing mirror and quarantine if incomplete ──
    local existing_count
    existing_count="$(count_html_files "$target_dir")"
    if [ "$existing_count" -gt 0 ]; then
        log "${BLUE}ℹ Existing mirror: $existing_count HTML files${NC}"
    fi
    if [ "$partial_mirror_allowed" != "true" ] && [ "$min_files" -gt 0 ] && [ "$existing_count" -gt 0 ] && [ "$existing_count" -lt "$min_files" ]; then
        quarantine_incomplete_dir "$target_dir" "$name" "$existing_count" "$min_files"
    fi

    # Proactive cleanup for known legacy Spring mirror layouts that otherwise mask incomplete fetches.
    if [[ "$name" == *"Spring Framework Javadoc"* ]]; then
        quarantine_path "$target_dir/api/current" "$name legacy api/current"
    fi
    if [[ "$name" == *"Spring Framework Reference"* ]]; then
        quarantine_versioned_reference_subdirs "$target_dir" "$name" ""
    fi
    if [[ "$name" == *"Spring AI Reference"* ]]; then
        quarantine_versioned_reference_subdirs "$target_dir" "$name" "^2\\."
    fi

    log "${YELLOW}Fetching $name...${NC}"
    mkdir -p "$target_dir"
    cd "$target_dir"

    # ── Dispatch to strategy ──
    if [[ "$url" == *"docs.oracle.com/en/java/javase/"*"/docs/api/" ]]; then
        if [ "$FORCE_REFRESH" != "true" ] && [ "$min_files" -gt 0 ] && [ "$existing_count" -ge "$min_files" ]; then
            log "${GREEN}✓ $name already fetched: $existing_count HTML files (minimum: $min_files)${NC}"
            return 0
        fi
        fetch_oracle_javadoc_seed "$url" "$target_dir" "$name" "$cut_dirs" "$min_files" "$partial_mirror_allowed"
    else
        fetch_docs_mirror "$url" "$target_dir" "$name" "$cut_dirs" "$min_files" "$reject_regex" "$partial_mirror_allowed"
    fi
}

# Projects one seven-field documentation source row into the fetch boundary.
fetch_documentation_source() {
    local documentation_source_projection="$1"
    local fetch_projection_delimiters="${documentation_source_projection//[^|]/}"
    if [ "${#fetch_projection_delimiters}" -ne 6 ]; then
        log "${RED}✗ Documentation source projection must contain exactly seven fields${NC}"
        return 1
    fi

    local documentation_source_url
    local mirror_directory
    local documentation_source_name
    local cut_directories
    local minimum_html_files
    local reject_regex
    local partial_mirror_allowed
    IFS='|' read -r documentation_source_url mirror_directory documentation_source_name cut_directories minimum_html_files reject_regex partial_mirror_allowed <<< "$documentation_source_projection"

    if [ "$partial_mirror_allowed" != "true" ] && [ "$partial_mirror_allowed" != "false" ]; then
        log "${RED}✗ Documentation source partial-mirror policy must be true or false${NC}"
        return 1
    fi

    echo ""
    log "Processing: $documentation_source_name"
    log "URL: $documentation_source_url"
    log "Target: $mirror_directory"

    fetch_docs "$documentation_source_url" "$mirror_directory" "$documentation_source_name" "$cut_directories" "$minimum_html_files" "$reject_regex" "$partial_mirror_allowed"
}

if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
    return 0
fi

# Documentation sources configuration
# Format: URL|TARGET_DIR|NAME|CUT_DIRS|MIN_FILES|REJECT_REGEX|ALLOW_PARTIAL
DOC_SOURCES=(
    # Spring Boot (current)
    "${SPRING_BOOT_REFERENCE_BASE:-https://docs.spring.io/spring-boot/reference/}|$DOCS_ROOT/spring-boot-complete|Spring Boot Reference (current)|1|50||false"
    "${SPRING_BOOT_API_BASE:-https://docs.spring.io/spring-boot/api/}|$DOCS_ROOT/spring-boot-complete|Spring Boot API (current)|1|7000||false"

    # Spring AI
    # Stable reference (1.1.x) - avoid pulling versioned reference subtrees (including 2.0) and SNAPSHOT content.
    "${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|$DOCS_ROOT/spring-ai-reference|Spring AI Reference (stable)|1|80|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT|false"
    # 2.0 reference (milestone) - avoid SNAPSHOT content.
    "${SPRING_AI_REFERENCE_2_BASE:-https://docs.spring.io/spring-ai/reference/2.0/}|$DOCS_ROOT/spring-ai-reference-2|Spring AI Reference (2.0)|1|80|/spring-ai/reference/[^/]*SNAPSHOT|false"
    # API docs (stable + 2.x). Keep these separate to avoid quarantine/validation conflicts.
    "${SPRING_AI_API_STABLE_BASE:-https://docs.spring.io/spring-ai/docs/current/api/}|$DOCS_ROOT/spring-ai-api-stable|Spring AI API (stable)|1|200||false"
    "${SPRING_AI_API_2_BASE:-https://docs.spring.io/spring-ai/docs/2.0.x/api/}|$DOCS_ROOT/spring-ai-api-2|Spring AI API (2.x)|1|200||false"

    # Spring Framework (current) - avoid pulling older reference versions under /reference/6.x, etc.
    "${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|$DOCS_ROOT/spring-framework-complete|Spring Framework Reference (current)|1|3000|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT|false"
    "${SPRING_FRAMEWORK_API_BASE:-https://docs.spring.io/spring-framework/docs/current/javadoc-api/}|$DOCS_ROOT/spring-framework-complete|Spring Framework Javadoc (current)|1|7000||false"

    "${JAVA25_RELEASE_NOTES_ISSUES_URL:-https://www.oracle.com/java/technologies/javase/25-relnote-issues.html}|$DOCS_ROOT/oracle/javase|Java 25 Release Notes Issues|3|1||false"
    "${IBM_JAVA25_ARTICLE_URL:-https://developer.ibm.com/articles/java-whats-new-java25/}|$DOCS_ROOT/ibm/articles|IBM Java 25 Overview|1|1||false"
    "${JETBRAINS_JAVA25_BLOG_URL:-https://blog.jetbrains.com/idea/2025/09/java-25-lts-and-intellij-idea/}|$DOCS_ROOT/jetbrains/idea/2025/09|JetBrains Java 25 Blog|3|1||false"
)

load_java_api_documentation_sources "$JAVA_API_SOURCES_MANIFEST"
append_java_api_fetch_sources "$DOCS_ROOT"

if [ "$LIST_JAVA_API_SOURCES" = "true" ]; then
    printf '%s\n' "${JAVA_API_SOURCE_PROJECTIONS[@]}"
    exit 0
fi

if [ "$INCLUDE_QUICK" = "true" ]; then
    DOC_SOURCES+=(
        "${SPRING_BOOT_REFERENCE_BASE:-https://docs.spring.io/spring-boot/reference/}|$DOCS_ROOT/spring-boot|Spring Boot Quick (reference landing)|1|1||false"
        "${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|$DOCS_ROOT/spring-framework|Spring Framework Quick (reference landing)|1|1|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT|false"
        "${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|$DOCS_ROOT/spring-ai|Spring AI Quick (reference landing)|1|1|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT|false"
        "${SPRING_AI_REFERENCE_2_BASE:-https://docs.spring.io/spring-ai/reference/2.0/}|$DOCS_ROOT/spring-ai-2|Spring AI Quick (2.0 landing)|1|1|/spring-ai/reference/[^/]*SNAPSHOT|false"
    )
fi

echo "=============================================="
echo "Consolidated Documentation Fetcher"
echo "=============================================="
echo "Docs root: $DOCS_ROOT"
echo "Log file: $LOG_FILE"
echo ""

# Initialize log
echo "[$(date)] Starting consolidated documentation fetch" > "$LOG_FILE"

# Statistics
TOTAL_FETCHED=0
TOTAL_FAILED=0

echo ""
log "Starting documentation fetch process..."
echo "=============================================="

# Process each documentation source
for documentation_source_projection in "${DOC_SOURCES[@]}"; do
    if fetch_documentation_source "$documentation_source_projection"; then
        TOTAL_FETCHED=$((TOTAL_FETCHED + 1))
    else
        TOTAL_FAILED=$((TOTAL_FAILED + 1))
        log "${RED}Error: Failed to fetch documentation source; auditing the remaining sources before exit${NC}"
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

SPRING_BOOT_REFERENCE_VERSION="$(extract_meta_version "$DOCS_ROOT/spring-boot-complete/reference/index.html")"
SPRING_FRAMEWORK_REFERENCE_VERSION="$(extract_meta_version "$DOCS_ROOT/spring-framework-complete/reference/index.html")"
SPRING_AI_REFERENCE_STABLE_VERSION="$(extract_meta_version "$DOCS_ROOT/spring-ai-reference/reference/index.html")"
SPRING_AI_REFERENCE_2_VERSION="$(extract_meta_version "$DOCS_ROOT/spring-ai-reference-2/reference/2.0/index.html")"
JAVA_JAVADOC_VERSIONS_METADATA="$(render_java_javadoc_versions_metadata "$DOCS_ROOT")"
JAVA_COMPLETE_DIRECTORIES_METADATA="$(render_java_complete_directories_metadata "$DOCS_ROOT")"

log "  - Total HTML files: $TOTAL_HTML"
log "  - Total files: $TOTAL_FILES"
log "  - Documentation root: $DOCS_ROOT"

echo ""
if [ "$TOTAL_FAILED" -eq 0 ]; then
    log "${GREEN}✅ Documentation fetch complete!${NC}"
else
    log "${RED}✗ Documentation fetch failed for $TOTAL_FAILED source(s)${NC}"
fi
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
  "versions": {
$JAVA_JAVADOC_VERSIONS_METADATA,
    "spring_boot_reference": "$SPRING_BOOT_REFERENCE_VERSION",
    "spring_framework_reference": "$SPRING_FRAMEWORK_REFERENCE_VERSION",
    "spring_ai_reference_stable": "$SPRING_AI_REFERENCE_STABLE_VERSION",
    "spring_ai_reference_2": "$SPRING_AI_REFERENCE_2_VERSION"
  },
  "directories": {
$JAVA_COMPLETE_DIRECTORIES_METADATA,
    "spring_boot_complete": "$(find "$DOCS_ROOT/spring-boot-complete" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_framework_complete": "$(find "$DOCS_ROOT/spring-framework-complete" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_ai_reference_stable": "$(find "$DOCS_ROOT/spring-ai-reference" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_ai_reference_2": "$(find "$DOCS_ROOT/spring-ai-reference-2" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_ai_api_stable": "$(find "$DOCS_ROOT/spring-ai-api-stable" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_ai_api_2": "$(find "$DOCS_ROOT/spring-ai-api-2" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')"
  }
}
EOF

log "Metadata saved to: $METADATA_FILE"
echo ""
if [ "$TOTAL_FAILED" -gt 0 ]; then
    exit 1
fi
echo "Next step: Run 'make run' or './scripts/process_all_to_qdrant.sh' to process and upload to Qdrant"
