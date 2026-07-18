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
# shellcheck source=lib/documentation_fetch_sources.sh
source "$SCRIPT_DIR/lib/documentation_fetch_sources.sh"
# shellcheck source=lib/documentation_fetch_metadata.sh
source "$SCRIPT_DIR/lib/documentation_fetch_metadata.sh"

# Centralized source definitions
RES_PROPS="$SCRIPT_DIR/../src/main/resources/docs-sources.properties"
JAVA_API_SOURCES_MANIFEST="$SCRIPT_DIR/../src/main/resources/java-api-documentation-sources.manifest"
DOCUMENTATION_SOURCES_MANIFEST="$SCRIPT_DIR/../src/main/resources/documentation-sources.manifest"
DOCS_ROOT="$SCRIPT_DIR/../data/docs"
LOG_FILE="$SCRIPT_DIR/../fetch_all_docs.log"

# Options
INCLUDE_QUICK="${INCLUDE_QUICK:-false}"
CLEAN_INCOMPLETE="${CLEAN_INCOMPLETE:-true}"
FORCE_REFRESH="${FORCE_REFRESH:-false}"
LIST_JAVA_API_SOURCES="false"
LIST_DOCUMENTATION_SOURCES="false"
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
            --list-documentation-sources)
                LIST_DOCUMENTATION_SOURCES="true"
                ;;
            --help|-h)
                echo "Usage: $0 [--include-quick] [--no-clean] [--force] [--list-java-api-sources] [--list-documentation-sources]"
                echo "  --include-quick : Also refresh small 'quick' doc mirrors"
                echo "  --no-clean      : Do not quarantine incomplete mirrors before refetch"
                echo "  --force         : Refresh even when mirrors look complete"
                echo "  --list-java-api-sources : Print canonical Java API source projections without fetching"
                echo "  --list-documentation-sources : Print canonical non-Java source projections without fetching"
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

# Converts one canonical Java API seed URL into the exact relative filesystem path
# wget writes when it receives the manifest-owned --cut-dirs setting.
java_api_seed_url_to_mirror_path() {
    local seed_url="$1"
    local cut_directories="$2"

    if [[ "$seed_url" != https://* || "$seed_url" == *\?* || "$seed_url" == *\#* ]]; then
        printf 'Java API seed URL must be an absolute query-free HTTPS URL: %s\n' "$seed_url" >&2
        return 1
    fi

    local authority_and_path="${seed_url#https://}"
    local remote_path="${authority_and_path#*/}"
    if [ "$remote_path" = "$authority_and_path" ] || [ -z "$remote_path" ]; then
        printf 'Java API seed URL must contain a remote path: %s\n' "$seed_url" >&2
        return 1
    fi

    local IFS='/'
    local -a remote_path_segments
    read -r -a remote_path_segments <<< "$remote_path"
    if [ "${#remote_path_segments[@]}" -le "$cut_directories" ]; then
        printf 'Java API seed URL has fewer path segments than --cut-dirs: %s\n' "$seed_url" >&2
        return 1
    fi

    local mirror_path=""
    local path_segment_index
    for ((path_segment_index = cut_directories; path_segment_index < ${#remote_path_segments[@]}; path_segment_index++)); do
        local remote_path_segment="${remote_path_segments[$path_segment_index]}"
        if [ -z "$remote_path_segment" ] \
            || [ "$remote_path_segment" = "." ] \
            || [ "$remote_path_segment" = ".." ]; then
            printf 'Java API seed URL has an unsafe path segment: %s\n' "$seed_url" >&2
            return 1
        fi
        if [ -n "$mirror_path" ]; then
            mirror_path+="/"
        fi
        mirror_path+="$remote_path_segment"
    done

    printf '%s\n' "$mirror_path"
}

# Writes the manifest-governed local paths represented by the current Java API seed.
write_java_api_seed_mirror_paths() {
    local remote_base_url="$1"
    local seed_file="$2"
    local cut_directories="$3"
    local mirror_paths_file="$4"

    : > "$mirror_paths_file"
    local seed_url
    while IFS= read -r seed_url || [ -n "$seed_url" ]; do
        if [ -z "$seed_url" ] || [[ "$seed_url" != "$remote_base_url"* ]]; then
            log "${RED}✗ Java API seed contains a URL outside its manifest-owned remote base${NC}"
            return 1
        fi

        local mirror_path
        if ! mirror_path="$(java_api_seed_url_to_mirror_path "$seed_url" "$cut_directories")"; then
            return 1
        fi
        printf '%s\n' "$mirror_path" >> "$mirror_paths_file"
    done < "$seed_file"

    if [ ! -s "$mirror_paths_file" ]; then
        log "${RED}✗ Java API seed produced no mirror paths${NC}"
        return 1
    fi
}

# Quarantines Java API HTML files that are absent from the current canonical seed.
# The quarantine is a sibling of data/docs because local ingestion recursively scans data/docs.
reconcile_java_api_seed_mirror() {
    local remote_base_url="$1"
    local target_dir="$2"
    local documentation_source_name="$3"
    local cut_directories="$4"
    local seed_file="$target_dir/.oracle-javadoc-seed.txt"

    if [ ! -d "$target_dir" ] || [ ! -s "$seed_file" ]; then
        log "${RED}✗ Java API mirror reconciliation requires a nonempty current seed for $documentation_source_name${NC}"
        return 1
    fi

    local mirror_paths_file
    mirror_paths_file="$(mktemp "$target_dir/.java-api-seed-paths.XXXXXX")"
    if ! write_java_api_seed_mirror_paths "$remote_base_url" "$seed_file" "$cut_directories" "$mirror_paths_file"; then
        rm -f "$mirror_paths_file"
        return 1
    fi

    local quarantine_dir=""
    local quarantined_html_count=0
    local stale_html_file
    while IFS= read -r -d '' stale_html_file; do
        local stale_relative_path="${stale_html_file#"$target_dir"/}"
        if grep -Fqx -- "$stale_relative_path" "$mirror_paths_file"; then
            continue
        fi

        if [ -z "$quarantine_dir" ]; then
            local quarantine_root
            quarantine_root="$(dirname "$DOCS_ROOT")/.quarantine"
            local timestamp
            timestamp="$(date -u +%Y%m%d_%H%M%S)"
            local mirror_basename
            mirror_basename="$(basename "$target_dir")"
            mkdir -p "$quarantine_root"
            quarantine_dir="$(mktemp -d "$quarantine_root/${mirror_basename}.unseeded-java-api.${timestamp}.XXXXXX")"
        fi

        local quarantine_file="$quarantine_dir/$stale_relative_path"
        mkdir -p "$(dirname "$quarantine_file")"
        if ! mv "$stale_html_file" "$quarantine_file"; then
            rm -f "$mirror_paths_file"
            return 1
        fi
        quarantined_html_count=$((quarantined_html_count + 1))
    done < <(find "$target_dir" -type f \( -name "*.html" -o -name "*.htm" \) -print0)

    rm -f "$mirror_paths_file"
    if [ "$quarantined_html_count" -gt 0 ]; then
        log "${YELLOW}⚠ Quarantined $quarantined_html_count unseeded Java API HTML file(s) for $documentation_source_name -> $quarantine_dir${NC}"
    fi
}

# Generates the explicit Java API URL seed from the manifest-owned remote base.
generate_java_api_javadoc_seed() {
    local remote_base_url="$1"
    local target_dir="$2"
    local seed_file="$target_dir/.oracle-javadoc-seed.txt"

    log "${BLUE}ℹ Java API Javadoc source; generating explicit URL seed list...${NC}"
    python3 "$SCRIPT_DIR/oracle_javadoc_seed.py" --base-url "$remote_base_url" --output "$seed_file" 2>&1 | tee -a "$LOG_FILE"
    local seed_url_count
    seed_url_count="$(wc -l "$seed_file" | awk '{print $1}')"
    log "${BLUE}ℹ Java API Javadoc seed URLs: $seed_url_count${NC}"
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

# Fetches a manifest-governed Java API using the current explicit Javadoc seed.
# Reconciliation has already removed unseeded HTML files before this function runs.
#
# Arguments:
#   $1 - target directory
#   $2 - human-readable name
#   $3 - --cut-dirs value
#   $4 - minimum required HTML files
#   $5 - reject regex (optional)
#   $6 - whether a validated partial mirror is accepted
fetch_java_api_javadoc_seed() {
    local target_dir="$1"
    local name="$2"
    local cut_dirs="$3"
    local min_files="$4"
    local reject_regex="${5:-}"
    local partial_mirror_allowed="$6"

    local seed_file="$target_dir/.oracle-javadoc-seed.txt"

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
        generate_java_api_javadoc_seed "$url" "$target_dir"
        reconcile_java_api_seed_mirror "$url" "$target_dir" "$name" "$cut_dirs"
        existing_count="$(count_html_files "$target_dir")"
        if [ "$FORCE_REFRESH" != "true" ] && [ "$min_files" -gt 0 ] && [ "$existing_count" -ge "$min_files" ]; then
            log "${GREEN}✓ $name already fetched: $existing_count HTML files (minimum: $min_files)${NC}"
            return 0
        fi
        fetch_java_api_javadoc_seed "$target_dir" "$name" "$cut_dirs" "$min_files" "$reject_regex" "$partial_mirror_allowed"
    else
        fetch_docs_mirror "$url" "$target_dir" "$name" "$cut_dirs" "$min_files" "$reject_regex" "$partial_mirror_allowed"
    fi
}

run_documentation_fetch() {
set -euo pipefail
if [ -f "$RES_PROPS" ]; then
    preserve_process_env_then_source_file "$RES_PROPS"
fi
parse_fetch_arguments "$@"

load_java_api_documentation_sources "$JAVA_API_SOURCES_MANIFEST"
load_documentation_sources "$DOCUMENTATION_SOURCES_MANIFEST"

if [ "$LIST_JAVA_API_SOURCES" = "true" ]; then
    printf '%s\n' "${JAVA_API_SOURCE_PROJECTIONS[@]}"
    exit 0
fi
if [ "$LIST_DOCUMENTATION_SOURCES" = "true" ]; then
    printf '%s\n' "${DOCUMENTATION_SOURCE_PROJECTIONS[@]}"
    exit 0
fi

# Format: JAVA_RELEASE|URL|RELATIVE_MIRROR_PATH|NAME|CUT_DIRS|MIN_FILES|REJECT_REGEX|ALLOW_PARTIAL
# Java API rows are projected from their dedicated manifest; generic official sources are projected
# from documentation-sources.manifest; older specialized sources remain isolated in their legacy catalog.
DOC_SOURCES=()
append_legacy_documentation_fetch_sources
append_manifest_documentation_fetch_sources
append_java_api_fetch_sources

if [ "$INCLUDE_QUICK" = "true" ]; then
    append_quick_documentation_fetch_sources
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
