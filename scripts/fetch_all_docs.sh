#!/bin/bash

# Consolidated Documentation Fetcher with Deduplication
# This script fetches all required documentation (configured Java APIs, Spring ecosystem)
# and ensures no redundant downloads by checking existing files

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=lib/shell_bootstrap.sh
source "$SCRIPT_DIR/lib/shell_bootstrap.sh"
# shellcheck source=lib/env_loader.sh
source "$SCRIPT_DIR/lib/env_loader.sh"
# shellcheck source=lib/documentation_sources.sh
source "$SCRIPT_DIR/lib/documentation_sources.sh"
# shellcheck source=lib/documentation_fetch_metadata.sh
source "$SCRIPT_DIR/lib/documentation_fetch_metadata.sh"

# Centralized source definitions
RES_PROPS="$SCRIPT_DIR/../src/main/resources/docs-sources.properties"
JAVA_API_SOURCES_MANIFEST="$SCRIPT_DIR/../src/main/resources/java-api-documentation-sources.manifest"
DOCS_ROOT="$SCRIPT_DIR/../data/docs"
LOG_FILE="$SCRIPT_DIR/../fetch_all_docs.log"

# Options
INCLUDE_QUICK="${INCLUDE_QUICK:-false}"
CLEAN_INCOMPLETE="${CLEAN_INCOMPLETE:-true}"
FORCE_REFRESH="${FORCE_REFRESH:-false}"
LIST_JAVA_API_SOURCES="false"
DOCUMENTATION_FETCH_PARTIAL_STATUS=2

parse_fetch_arguments() {
    local fetch_argument
    for fetch_argument in "$@"; do
        case $fetch_argument in
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
                echo "Unknown option: $fetch_argument"
                echo "Use --help for usage information"
                exit 1
                ;;
        esac
    done
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
# enforces the minimum-files threshold. Returns 0 on success, 2 for a retained
# partial mirror, and 1 for any other failure.
#
# Arguments:
#   $1 - wget exit code
#   $2 - target directory (for HTML count)
#   $3 - human-readable name
#   $4 - minimum required HTML files (0 = no minimum)
#   $5 - whether a nonempty partial mirror is retained for incremental reruns
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

    if [ "$fetched_html_count" -eq 0 ]; then
        log "${RED}✗ $name fetch produced no HTML files${NC}"
        return 1
    fi
    if [ "$min_files" -gt 0 ] && [ "$fetched_html_count" -lt "$min_files" ]; then
        if [ "$partial_mirror_allowed" = "true" ]; then
            log "${YELLOW}⚠ $name mirror is still incomplete after fetch: $fetched_html_count HTML files (expected $min_files+); keeping partial mirror for incremental reruns${NC}"
            return "$DOCUMENTATION_FETCH_PARTIAL_STATUS"
        else
            log "${RED}✗ $name mirror is still incomplete after fetch: $fetched_html_count HTML files (expected $min_files+)${NC}"
            return 1
        fi
    fi
    log "${GREEN}✓ $name fetched successfully: $fetched_html_count HTML files${NC}"
    return 0
}

# Fetches a manifest-governed Java API using an explicit seed list derived from the Javadoc
# search indices.  This avoids incomplete recursive crawls that miss pages.
#
# Arguments:
#   $1 - Java API Javadoc base URL
#   $2 - target directory
#   $3 - human-readable name
#   $4 - --cut-dirs value
#   $5 - minimum required HTML files
#   $6 - reject regex (optional)
#   $7 - whether a validated partial mirror is accepted
fetch_java_api_javadoc_seed() {
    local url="$1"
    local target_dir="$2"
    local name="$3"
    local cut_dirs="$4"
    local min_files="$5"
    local reject_regex="${6:-}"
    local partial_mirror_allowed="$7"

    local seed_file="$target_dir/.oracle-javadoc-seed.txt"
    log "${BLUE}ℹ Java API Javadoc source; generating explicit URL seed list...${NC}"
    python3 "$SCRIPT_DIR/oracle_javadoc_seed.py" --base-url "$url" --output "$seed_file" 2>&1 | tee -a "$LOG_FILE"
    local seed_url_count
    seed_url_count="$(wc -l "$seed_file" | awk '{print $1}')"
    log "${BLUE}ℹ Java API Javadoc seed URLs: $seed_url_count${NC}"

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
    if [ -n "$reject_regex" ]; then
        wget_seed_args+=(--reject-regex="$reject_regex")
    fi

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
#   $7 - whether a nonempty partial mirror is retained for incremental reruns
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
# strategy — seed-list for manifest-governed Java APIs, wget --mirror for everything else.
#
# Arguments (canonical manifest order):
#   $1 - Java release, blank only for non-Java documentation
#   $2 - URL
#   $3 - relative mirror path beneath DOCS_ROOT
#   $4 - human-readable name
#   $5 - --cut-dirs value
#   $6 - minimum required HTML files
#   $7 - reject regex (optional)
#   $8 - whether a nonempty partial mirror is retained for incremental reruns
fetch_docs() {
    local java_release="$1"
    local url="$2"
    local relative_mirror_path="$3"
    local name="$4"
    local cut_dirs="$5"
    local min_files="$6"
    local reject_regex="${7:-}"
    local partial_mirror_allowed="$8"
    local target_dir="$DOCS_ROOT/$relative_mirror_path"

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
    if [ -n "$java_release" ]; then
        if [ "$FORCE_REFRESH" != "true" ] && [ "$min_files" -gt 0 ] && [ "$existing_count" -ge "$min_files" ]; then
            log "${GREEN}✓ $name already fetched: $existing_count HTML files (minimum: $min_files)${NC}"
            return 0
        fi
        fetch_java_api_javadoc_seed "$url" "$target_dir" "$name" "$cut_dirs" "$min_files" "$reject_regex" "$partial_mirror_allowed"
    else
        fetch_docs_mirror "$url" "$target_dir" "$name" "$cut_dirs" "$min_files" "$reject_regex" "$partial_mirror_allowed"
    fi
}

# Projects one eight-field documentation source row into the fetch boundary.
fetch_documentation_source() {
    local documentation_source_projection="$1"
    local fetch_projection_delimiters="${documentation_source_projection//[^|]/}"
    if [ "${#fetch_projection_delimiters}" -ne 7 ]; then
        log "${RED}✗ Documentation source projection must contain exactly eight fields${NC}"
        return 1
    fi

    local java_release
    local documentation_source_url
    local relative_mirror_path
    local documentation_source_name
    local cut_directories
    local minimum_html_files
    local reject_regex
    local partial_mirror_allowed
    IFS='|' read -r java_release documentation_source_url relative_mirror_path documentation_source_name cut_directories minimum_html_files reject_regex partial_mirror_allowed <<< "$documentation_source_projection"

    if [ -z "$documentation_source_url" ] || [ -z "$relative_mirror_path" ] || [ -z "$documentation_source_name" ]; then
        log "${RED}✗ Documentation source projection has a blank required field${NC}"
        return 1
    fi
    if has_boundary_whitespace "$documentation_source_url" \
        || has_boundary_whitespace "$relative_mirror_path" \
        || has_boundary_whitespace "$documentation_source_name" \
        || has_boundary_whitespace "$reject_regex" \
        || has_manifest_control_character "$documentation_source_url" \
        || has_manifest_control_character "$relative_mirror_path" \
        || has_manifest_control_character "$documentation_source_name" \
        || has_manifest_control_character "$reject_regex"; then
        log "${RED}✗ Documentation source projection has invalid text fields${NC}"
        return 1
    fi
    if [ -n "$java_release" ] \
        && { ! is_canonical_manifest_integer "$java_release" || [ "$java_release" = "0" ]; }; then
        log "${RED}✗ Documentation source Java release must be blank or a positive canonical integer${NC}"
        return 1
    fi
    if ! is_normalized_relative_mirror_path "$relative_mirror_path"; then
        log "${RED}✗ Documentation source mirror path must be normalized and relative${NC}"
        return 1
    fi
    if [ -n "$java_release" ] && ! is_absolute_https_remote_base_url "$documentation_source_url"; then
        log "${RED}✗ Java API documentation source URL must be an absolute HTTPS base URL${NC}"
        return 1
    fi
    if ! is_canonical_manifest_integer "$cut_directories"; then
        log "${RED}✗ Documentation source cut-directories value must be a canonical integer${NC}"
        return 1
    fi
    if ! is_canonical_manifest_integer "$minimum_html_files" || [ "$minimum_html_files" = "0" ]; then
        log "${RED}✗ Documentation source minimum HTML files must be a positive canonical integer${NC}"
        return 1
    fi

    if [ "$partial_mirror_allowed" != "true" ] && [ "$partial_mirror_allowed" != "false" ]; then
        log "${RED}✗ Documentation source partial-mirror policy must be true or false${NC}"
        return 1
    fi

    echo ""
    log "Processing: $documentation_source_name"
    log "URL: $documentation_source_url"
    log "Target: $DOCS_ROOT/$relative_mirror_path"

    fetch_docs "$java_release" "$documentation_source_url" "$relative_mirror_path" "$documentation_source_name" "$cut_directories" "$minimum_html_files" "$reject_regex" "$partial_mirror_allowed"
}

run_documentation_fetch() {
set -euo pipefail
if [ -f "$RES_PROPS" ]; then
    preserve_process_env_then_source_file "$RES_PROPS"
fi
parse_fetch_arguments "$@"

# Documentation sources configuration
# Format: JAVA_RELEASE|URL|RELATIVE_MIRROR_PATH|NAME|CUT_DIRS|MIN_FILES|REJECT_REGEX|ALLOW_PARTIAL
# JAVA_RELEASE is blank for non-Java documentation sources.
DOC_SOURCES=(
    # Spring Boot (current)
    "|${SPRING_BOOT_REFERENCE_BASE:-https://docs.spring.io/spring-boot/reference/}|spring-boot-complete|Spring Boot Reference (current)|1|50||false"
    "|${SPRING_BOOT_API_BASE:-https://docs.spring.io/spring-boot/api/}|spring-boot-complete|Spring Boot API (current)|1|7000||false"

    # Spring AI
    # Stable reference (1.1.x) - avoid pulling versioned reference subtrees (including 2.0) and SNAPSHOT content.
    "|${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|spring-ai-reference|Spring AI Reference (stable)|1|80|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT|false"
    # 2.0 reference (milestone) - avoid SNAPSHOT content.
    "|${SPRING_AI_REFERENCE_2_BASE:-https://docs.spring.io/spring-ai/reference/2.0/}|spring-ai-reference-2|Spring AI Reference (2.0)|1|80|/spring-ai/reference/[^/]*SNAPSHOT|false"
    # API docs (stable + 2.x). Keep these separate to avoid quarantine/validation conflicts.
    "|${SPRING_AI_API_STABLE_BASE:-https://docs.spring.io/spring-ai/docs/current/api/}|spring-ai-api-stable|Spring AI API (stable)|1|200||false"
    "|${SPRING_AI_API_2_BASE:-https://docs.spring.io/spring-ai/docs/2.0.x/api/}|spring-ai-api-2|Spring AI API (2.x)|1|200||false"

    # Spring Framework (current) - avoid pulling older reference versions under /reference/6.x, etc.
    "|${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|spring-framework-complete|Spring Framework Reference (current)|1|3000|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT|false"
    "|${SPRING_FRAMEWORK_API_BASE:-https://docs.spring.io/spring-framework/docs/current/javadoc-api/}|spring-framework-complete|Spring Framework Javadoc (current)|1|7000||false"

    "|${JAVA25_RELEASE_NOTES_ISSUES_URL:-https://www.oracle.com/java/technologies/javase/25-relnote-issues.html}|oracle/javase|Java 25 Release Notes Issues|3|1||false"
    "|${IBM_JAVA25_ARTICLE_URL:-https://developer.ibm.com/articles/java-whats-new-java25/}|ibm/articles|IBM Java 25 Overview|1|1||false"
    "|${JETBRAINS_JAVA25_BLOG_URL:-https://blog.jetbrains.com/idea/2025/09/java-25-lts-and-intellij-idea/}|jetbrains/idea/2025/09|JetBrains Java 25 Blog|3|1||false"
)

load_java_api_documentation_sources "$JAVA_API_SOURCES_MANIFEST"
append_java_api_fetch_sources

if [ "$LIST_JAVA_API_SOURCES" = "true" ]; then
    printf '%s\n' "${JAVA_API_SOURCE_PROJECTIONS[@]}"
    exit 0
fi

if [ "$INCLUDE_QUICK" = "true" ]; then
    DOC_SOURCES+=(
        "|${SPRING_BOOT_REFERENCE_BASE:-https://docs.spring.io/spring-boot/reference/}|spring-boot|Spring Boot Quick (reference landing)|1|1||false"
        "|${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|spring-framework|Spring Framework Quick (reference landing)|1|1|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|spring-ai|Spring AI Quick (reference landing)|1|1|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_REFERENCE_2_BASE:-https://docs.spring.io/spring-ai/reference/2.0/}|spring-ai-2|Spring AI Quick (2.0 landing)|1|1|/spring-ai/reference/[^/]*SNAPSHOT|false"
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
TOTAL_PARTIAL=0
TOTAL_FAILED=0

echo ""
log "Starting documentation fetch process..."
echo "=============================================="

# Process each documentation source
for documentation_source_projection in "${DOC_SOURCES[@]}"; do
    if fetch_documentation_source "$documentation_source_projection"; then
        TOTAL_FETCHED=$((TOTAL_FETCHED + 1))
    else
        documentation_fetch_status=$?
        if [ "$documentation_fetch_status" -eq "$DOCUMENTATION_FETCH_PARTIAL_STATUS" ]; then
            TOTAL_PARTIAL=$((TOTAL_PARTIAL + 1))
            log "${YELLOW}Partial documentation source preserved; completion remains blocked${NC}"
        else
            TOTAL_FAILED=$((TOTAL_FAILED + 1))
            log "${RED}Error: Failed to fetch documentation source; auditing the remaining sources before exit${NC}"
        fi
    fi
done

echo ""
echo "=============================================="
echo "Documentation Fetch Summary"
echo "=============================================="
log "📊 Statistics:"
log "  - Newly fetched: $TOTAL_FETCHED"
log "  - Partial: $TOTAL_PARTIAL"
log "  - Failed: $TOTAL_FAILED"
log "  - Include quick mirrors: $INCLUDE_QUICK"
log "  - Clean incomplete mirrors: $CLEAN_INCOMPLETE"

echo ""
if [ "$TOTAL_FAILED" -gt 0 ]; then
    log "${RED}✗ Documentation fetch failed for $TOTAL_FAILED source(s)${NC}"
elif [ "$TOTAL_PARTIAL" -gt 0 ]; then
    log "${YELLOW}⚠ Documentation fetch remains partial for $TOTAL_PARTIAL source(s)${NC}"
else
    log "${GREEN}✅ Documentation fetch complete!${NC}"
fi
log "Check log for details: $LOG_FILE"

write_documentation_fetch_metadata
echo ""
if [ "$TOTAL_FAILED" -gt 0 ] || [ "$TOTAL_PARTIAL" -gt 0 ]; then
    exit 1
fi
echo "Next step: Run 'make run' or './scripts/process_all_to_qdrant.sh' to process and upload to Qdrant"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    run_documentation_fetch "$@"
fi
