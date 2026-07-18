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
# shellcheck source=lib/documentation_seed_mirrors.sh
source "$SCRIPT_DIR/lib/documentation_seed_mirrors.sh"

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
DOCUMENTATION_SOURCE_SELECTOR_ENABLED="false"
DOCUMENTATION_SOURCE_SELECTOR=""
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
            --doc-sets=*)
                if [ "$DOCUMENTATION_SOURCE_SELECTOR_ENABLED" = "true" ]; then
                    echo "--doc-sets can be specified only once"
                    exit 1
                fi
                DOCUMENTATION_SOURCE_SELECTOR_ENABLED="true"
                DOCUMENTATION_SOURCE_SELECTOR="${fetch_argument#--doc-sets=}"
                ;;
            --help|-h)
                echo "Usage: $0 [--include-quick] [--no-clean] [--force] [--doc-sets=SOURCE_IDENTIFIER,...] [--list-java-api-sources] [--list-documentation-sources]"
                echo "  --include-quick : Also refresh small 'quick' doc mirrors"
                echo "  --no-clean      : Do not quarantine incomplete mirrors before refetch"
                echo "  --force         : Refresh even when mirrors look complete"
                echo "  --doc-sets      : Fetch only canonical non-Java docSet or Java API relativeMirrorPath rows"
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

create_documentation_quarantine_directory() {
    local quarantine_basename="$1"
    local quarantine_category="$2"
    local quarantine_root
    quarantine_root="$(dirname "$DOCS_ROOT")/.quarantine"
    local timestamp
    timestamp="$(date -u +%Y%m%d_%H%M%S)"

    if ! mkdir -p "$quarantine_root"; then
        return 1
    fi

    local quarantine_directory
    if ! quarantine_directory="$(mktemp -d "$quarantine_root/${quarantine_basename}.${quarantine_category}.${timestamp}.XXXXXX")" \
        || [[ "$quarantine_directory" != "$quarantine_root/"* ]]; then
        return 1
    fi

    printf '%s\n' "$quarantine_directory"
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

    local base
    base="$(basename "$dir")"
    local quarantine_directory
    if ! quarantine_directory="$(create_documentation_quarantine_directory "$base" "incomplete")"; then
        log "${RED}✗ Could not create incomplete-mirror quarantine for $name${NC}"
        return 1
    fi
    local quarantine_path="$quarantine_directory/$base"
    log "${YELLOW}⚠ Quarantining incomplete mirror: $name ($html_count HTML files; expected $min_files+) -> $quarantine_path${NC}"
    if ! mv "$dir" "$quarantine_path"; then
        log "${RED}✗ Could not quarantine incomplete mirror: $name${NC}"
        return 1
    fi
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

    local base
    base="$(basename "$dir")"
    local quarantine_directory
    if ! quarantine_directory="$(create_documentation_quarantine_directory "$base" "legacy")"; then
        log "${RED}✗ Could not create legacy-mirror quarantine for $name${NC}"
        return 1
    fi
    local quarantine_path="$quarantine_directory/$base"
    log "${YELLOW}⚠ Quarantining legacy mirror path: $name -> $quarantine_path${NC}"
    if ! mv "$dir" "$quarantine_path"; then
        log "${RED}✗ Could not quarantine legacy mirror path: $name${NC}"
        return 1
    fi
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

# Dispatches documentation fetching, then retires a superseded mirror only after
# the canonical replacement has passed its strategy-specific validation.
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
#   $9 - structured seed document type, blank for recursive mirroring
#   $10 - structured seed discovery URL
#   $11 - exact source prefix mapped onto the canonical URL
#   $12 - exact superseded mirror path quarantined after successful replacement
fetch_docs() {
    local java_release="$1"
    local url="$2"
    local relative_mirror_path="$3"
    local name="$4"
    local cut_dirs="$5"
    local min_files="$6"
    local reject_regex="${7:-}"
    local partial_mirror_allowed="$8"
    local seed_document_type="${9:-}"
    local seed_discovery_url="${10:-}"
    local seed_source_prefix="${11:-}"
    local superseded_relative_mirror_path="${12:-}"
    local target_dir="$DOCS_ROOT/$relative_mirror_path"
    local fetch_target_directory="$target_dir"
    local staging_directory=""

    # Allow config-friendly placeholder for regex alternation without breaking our field delimiter.
    reject_regex="${reject_regex//__OR__/|}"

    if [ -n "$superseded_relative_mirror_path" ] \
        && ! require_lifecycle_root_disjoint_from_external_fetch_roots \
            "$superseded_relative_mirror_path"; then
        log "${RED}✗ Superseded mirror overlaps an external active mirror: $superseded_relative_mirror_path${NC}"
        return 1
    fi

    if [ -n "$superseded_relative_mirror_path" ] \
        && [ -e "$DOCS_ROOT/$superseded_relative_mirror_path" ] \
        && [ "$CLEAN_INCOMPLETE" != "true" ]; then
        log "${RED}✗ Superseded mirror remains while cleanup is disabled: $superseded_relative_mirror_path${NC}"
        return 1
    fi

    if [ -n "$superseded_relative_mirror_path" ]; then
        if ! staging_directory="$(create_documentation_fetch_staging_directory "$relative_mirror_path")"; then
            log "${RED}✗ Could not create a replacement staging directory for $name${NC}"
            return 1
        fi
        fetch_target_directory="$staging_directory"
    fi

    # ── Pre-fetch: check existing mirror and quarantine if incomplete ──
    local existing_count
    existing_count="$(count_html_files "$fetch_target_directory")"
    if [ "$existing_count" -gt 0 ]; then
        log "${BLUE}ℹ Existing mirror: $existing_count HTML files${NC}"
    fi
    if [ "$partial_mirror_allowed" != "true" ] && [ "$min_files" -gt 0 ] && [ "$existing_count" -gt 0 ] && [ "$existing_count" -lt "$min_files" ]; then
        quarantine_incomplete_dir "$fetch_target_directory" "$name" "$existing_count" "$min_files"
    fi

    # Proactive cleanup for known legacy Spring mirror layouts that otherwise mask incomplete fetches.
    if [[ "$name" == *"Spring Framework Javadoc"* ]]; then
        quarantine_path "$fetch_target_directory/api/current" "$name legacy api/current"
    fi
    if [[ "$name" == *"Spring Framework Reference"* ]]; then
        quarantine_versioned_reference_subdirs "$fetch_target_directory" "$name" ""
    fi
    if [[ "$name" == *"Spring AI Reference"* ]]; then
        quarantine_versioned_reference_subdirs "$fetch_target_directory" "$name" "^2\\."
    fi

    log "${YELLOW}Fetching $name...${NC}"
    if ! mkdir -p "$fetch_target_directory" || ! cd "$fetch_target_directory"; then
        if [ -n "$staging_directory" ] \
            && ! discard_documentation_fetch_staging_directory "$staging_directory"; then
            log "${RED}✗ Could not discard inaccessible replacement staging for $name${NC}"
        fi
        log "${RED}✗ Could not enter the fetch directory for $name${NC}"
        return 1
    fi

    # ── Dispatch to strategy ──
    local documentation_fetch_status=0
    if [ -n "$java_release" ]; then
        local java_api_fetch_required="true"
        if ! generate_java_api_javadoc_seed "$url" "$fetch_target_directory" \
            || ! reconcile_java_api_seed_mirror "$url" "$fetch_target_directory" "$name" "$cut_dirs"; then
            cd - > /dev/null
            return 1
        fi
        existing_count="$(count_html_files "$fetch_target_directory")"
        if [ "$FORCE_REFRESH" != "true" ] && [ "$min_files" -gt 0 ] && [ "$existing_count" -ge "$min_files" ]; then
            if verify_java_api_seed_mirror "$url" "$fetch_target_directory" "$name" "$cut_dirs"; then
                log "${GREEN}✓ $name already fetched: $existing_count HTML files (minimum: $min_files)${NC}"
                if ! cd - > /dev/null; then
                    log "${RED}✗ Could not restore the working directory after checking $name${NC}"
                    return 1
                fi
                java_api_fetch_required="false"
            else
                log "${YELLOW}⚠ $name cached mirror is missing canonical seed paths; refetching${NC}"
            fi
        fi
        if [ "$java_api_fetch_required" = "true" ]; then
            fetch_java_api_javadoc_seed \
                "$fetch_target_directory" \
                "$name" \
                "$cut_dirs" \
                "$min_files" \
                "$reject_regex" \
                "$partial_mirror_allowed" \
                "$url" || documentation_fetch_status=$?
        fi
    elif [ -n "$seed_discovery_url" ]; then
        fetch_discovered_documentation_seed \
            "$url" \
            "$fetch_target_directory" \
            "$name" \
            "$cut_dirs" \
            "$min_files" \
            "$reject_regex" \
            "$partial_mirror_allowed" \
            "$seed_document_type" \
            "$seed_discovery_url" \
            "$seed_source_prefix" || documentation_fetch_status=$?
    else
        fetch_docs_mirror \
            "$url" \
            "$fetch_target_directory" \
            "$name" \
            "$cut_dirs" \
            "$min_files" \
            "$reject_regex" \
            "$partial_mirror_allowed" || documentation_fetch_status=$?
    fi

    if [ "$documentation_fetch_status" -ne 0 ]; then
        if [ -n "$staging_directory" ] \
            && ! discard_documentation_fetch_staging_directory "$staging_directory"; then
            log "${RED}✗ Could not discard failed replacement staging for $name${NC}"
            return 1
        fi
        return "$documentation_fetch_status"
    fi

    if [ -n "$staging_directory" ]; then
        if ! publish_staged_documentation_mirror \
            "$staging_directory" \
            "$relative_mirror_path" \
            "$superseded_relative_mirror_path" \
            "$name"; then
            if [ -d "$staging_directory" ]; then
                if ! discard_documentation_fetch_staging_directory "$staging_directory"; then
                    log "${RED}✗ Could not discard unpublished replacement staging for $name${NC}"
                fi
            fi
            return 1
        fi
    fi
}

record_documentation_fetch() {
    local fetch_strategy="$1"
    local documentation_source_projection="$2"
    if "$fetch_strategy" "$documentation_source_projection"; then
        TOTAL_FETCHED=$((TOTAL_FETCHED + 1))
        return
    else
        local documentation_fetch_status=$?
    fi

    if [ "$documentation_fetch_status" -eq "$DOCUMENTATION_FETCH_PARTIAL_STATUS" ]; then
        TOTAL_PARTIAL=$((TOTAL_PARTIAL + 1))
        log "${YELLOW}Partial documentation source preserved; completion remains blocked${NC}"
        return
    fi
    TOTAL_FAILED=$((TOTAL_FAILED + 1))
    log "${RED}Error: Failed to fetch documentation source; auditing the remaining sources before exit${NC}"
}

# Selects exact canonical identities from both source manifests before any fetch begins.
# Non-Java documentation retains its manifest-owned docSet identifier; Java APIs use
# their manifest-owned relativeMirrorPath identifier. This keeps the CLI scoped while
# preserving each source family's distinct fetch projection contract.
select_canonical_documentation_fetch_sources() {
    local requested_source_selector="$1"
    local -a requested_source_identifiers=()
    local -a retained_source_identifiers=("")
    local -a selected_documentation_source_projections=()
    local -a selected_java_api_source_projections=()

    if [ -z "$requested_source_selector" ] \
        || [[ "$requested_source_selector" == ,* ]] \
        || [[ "$requested_source_selector" == *, ]] \
        || [[ "$requested_source_selector" == *,,* ]]; then
        echo "Documentation source selector contains a blank canonical identifier" >&2
        return 1
    fi

    local IFS=','
    read -r -a requested_source_identifiers <<< "$requested_source_selector"

    local requested_source_identifier
    local retained_source_identifier
    for requested_source_identifier in "${requested_source_identifiers[@]}"; do
        if has_boundary_whitespace "$requested_source_identifier" \
            || has_manifest_control_character "$requested_source_identifier"; then
            echo "Documentation source selector has an invalid canonical identifier: $requested_source_identifier" >&2
            return 1
        fi
        for retained_source_identifier in "${retained_source_identifiers[@]}"; do
            if [ "$retained_source_identifier" = "$requested_source_identifier" ]; then
                echo "Documentation source selector duplicates canonical identifier: $requested_source_identifier" >&2
                return 1
            fi
        done
        retained_source_identifiers+=("$requested_source_identifier")
    done

    for requested_source_identifier in "${requested_source_identifiers[@]}"; do
        local source_identifier_match_count=0
        local documentation_source_projection
        for documentation_source_projection in "${DOCUMENTATION_SOURCE_PROJECTIONS[@]}"; do
            local documentation_doc_set
            documentation_doc_set="$(documentation_source_manifest_field \
                "$documentation_source_projection" \
                "docSet")"
            if [ "$documentation_doc_set" = "$requested_source_identifier" ]; then
                source_identifier_match_count=$((source_identifier_match_count + 1))
            fi
        done

        local java_api_source_projection
        for java_api_source_projection in "${JAVA_API_SOURCE_PROJECTIONS[@]}"; do
            local java_api_relative_mirror_path
            java_api_relative_mirror_path="$(documentation_fetch_projection_relative_mirror_path \
                "$java_api_source_projection")"
            if [ "$java_api_relative_mirror_path" = "$requested_source_identifier" ]; then
                source_identifier_match_count=$((source_identifier_match_count + 1))
            fi
        done

        if [ "$source_identifier_match_count" -eq 0 ]; then
            echo "Unknown documentation source identifier: $requested_source_identifier" >&2
            return 1
        fi
        if [ "$source_identifier_match_count" -gt 1 ]; then
            echo "Canonical documentation source identifier is ambiguous: $requested_source_identifier" >&2
            return 1
        fi
    done

    local documentation_source_projection
    for documentation_source_projection in "${DOCUMENTATION_SOURCE_PROJECTIONS[@]}"; do
        local documentation_doc_set
        documentation_doc_set="$(documentation_source_manifest_field \
            "$documentation_source_projection" \
            "docSet")"
        for requested_source_identifier in "${requested_source_identifiers[@]}"; do
            if [ "$documentation_doc_set" = "$requested_source_identifier" ]; then
                selected_documentation_source_projections+=("$documentation_source_projection")
                break
            fi
        done
    done

    local java_api_source_projection
    for java_api_source_projection in "${JAVA_API_SOURCE_PROJECTIONS[@]}"; do
        local java_api_relative_mirror_path
        java_api_relative_mirror_path="$(documentation_fetch_projection_relative_mirror_path \
            "$java_api_source_projection")"
        for requested_source_identifier in "${requested_source_identifiers[@]}"; do
            if [ "$java_api_relative_mirror_path" = "$requested_source_identifier" ]; then
                selected_java_api_source_projections+=("$java_api_source_projection")
                break
            fi
        done
    done

    SELECTED_DOCUMENTATION_SOURCE_PROJECTIONS=(
        "${selected_documentation_source_projections[@]+"${selected_documentation_source_projections[@]}"}"
    )
    SELECTED_JAVA_API_SOURCE_PROJECTIONS=(
        "${selected_java_api_source_projections[@]+"${selected_java_api_source_projections[@]}"}"
    )
}

run_documentation_fetch() {
set -euo pipefail
if [ -f "$RES_PROPS" ]; then
    preserve_process_env_then_source_file "$RES_PROPS"
fi
parse_fetch_arguments "$@"

load_java_api_documentation_sources "$JAVA_API_SOURCES_MANIFEST" || return 1
load_documentation_sources "$DOCUMENTATION_SOURCES_MANIFEST" || return 1
load_builtin_documentation_fetch_projections
validate_documentation_source_lifecycle_external_roots || return 1

if [ "$LIST_JAVA_API_SOURCES" = "true" ]; then
    printf '%s\n' "${JAVA_API_SOURCE_PROJECTIONS[@]}"
    exit 0
fi
if [ "$LIST_DOCUMENTATION_SOURCES" = "true" ]; then
    printf '%s\n' "${DOCUMENTATION_SOURCE_PROJECTIONS[@]}"
    exit 0
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

if [ "$DOCUMENTATION_SOURCE_SELECTOR_ENABLED" = "true" ]; then
    SELECTED_DOCUMENTATION_SOURCE_PROJECTIONS=()
    SELECTED_JAVA_API_SOURCE_PROJECTIONS=()
    select_canonical_documentation_fetch_sources "$DOCUMENTATION_SOURCE_SELECTOR" || return 1
    for documentation_source_projection in "${SELECTED_DOCUMENTATION_SOURCE_PROJECTIONS[@]+"${SELECTED_DOCUMENTATION_SOURCE_PROJECTIONS[@]}"}"; do
        record_documentation_fetch fetch_manifest_documentation_source "$documentation_source_projection"
    done
    for documentation_source_projection in "${SELECTED_JAVA_API_SOURCE_PROJECTIONS[@]+"${SELECTED_JAVA_API_SOURCE_PROJECTIONS[@]}"}"; do
        record_documentation_fetch fetch_projection_documentation_source "$documentation_source_projection"
    done
else
    for documentation_source_projection in "${LEGACY_DOCUMENTATION_FETCH_PROJECTIONS[@]}"; do
        record_documentation_fetch fetch_projection_documentation_source "$documentation_source_projection"
    done
    for documentation_source_projection in "${DOCUMENTATION_SOURCE_PROJECTIONS[@]}"; do
        record_documentation_fetch fetch_manifest_documentation_source "$documentation_source_projection"
    done
    for documentation_source_projection in "${JAVA_API_SOURCE_PROJECTIONS[@]}"; do
        record_documentation_fetch fetch_projection_documentation_source "$documentation_source_projection"
    done
    if [ "$INCLUDE_QUICK" = "true" ]; then
        for documentation_source_projection in "${QUICK_DOCUMENTATION_FETCH_PROJECTIONS[@]}"; do
            record_documentation_fetch fetch_projection_documentation_source "$documentation_source_projection"
        done
    fi
fi

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
