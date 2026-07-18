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
# shellcheck source=lib/documentation_seed_mirrors.sh
source "$SCRIPT_DIR/lib/documentation_seed_mirrors.sh"

DOCS_ROOT="$SCRIPT_DIR/../data/docs"
LOG_FILE="$SCRIPT_DIR/../fetch_all_docs.log"

# Options
INCLUDE_QUICK="${INCLUDE_QUICK:-false}"
CLEAN_INCOMPLETE="${CLEAN_INCOMPLETE:-true}"
FORCE_REFRESH="${FORCE_REFRESH:-false}"
DOCUMENTATION_SOURCE_SELECTOR_ENABLED="false"
DOCUMENTATION_SOURCE_SELECTOR=""
DOCUMENTATION_FETCH_PARTIAL_STATUS=2

JAVA25_RELEASE_NOTES_ISSUES_URL="${JAVA25_RELEASE_NOTES_ISSUES_URL:-https://www.oracle.com/java/technologies/javase/25-relnote-issues.html}"
IBM_JAVA25_ARTICLE_URL="${IBM_JAVA25_ARTICLE_URL:-https://developer.ibm.com/articles/java-whats-new-java25/}"
JETBRAINS_JAVA25_BLOG_URL="${JETBRAINS_JAVA25_BLOG_URL:-https://blog.jetbrains.com/idea/2025/09/java-25-lts-and-intellij-idea/}"
SPRING_FRAMEWORK_REFERENCE_BASE="${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}"
SPRING_FRAMEWORK_API_BASE="${SPRING_FRAMEWORK_API_BASE:-https://docs.spring.io/spring-framework/docs/current/javadoc-api/}"
SPRING_AI_REFERENCE_BASE="${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}"
SPRING_AI_REFERENCE_2_BASE="${SPRING_AI_REFERENCE_2_BASE:-https://docs.spring.io/spring-ai/reference/2.0/}"
SPRING_AI_API_STABLE_BASE="${SPRING_AI_API_STABLE_BASE:-https://docs.spring.io/spring-ai/docs/current/api/}"
SPRING_AI_API_2_BASE="${SPRING_AI_API_2_BASE:-https://docs.spring.io/spring-ai/docs/2.0.x/api/}"

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
            --doc-sets=*)
                if [ "$DOCUMENTATION_SOURCE_SELECTOR_ENABLED" = "true" ]; then
                    echo "--doc-sets can be specified only once"
                    exit 1
                fi
                DOCUMENTATION_SOURCE_SELECTOR_ENABLED="true"
                DOCUMENTATION_SOURCE_SELECTOR="${fetch_argument#--doc-sets=}"
                ;;
            --help|-h)
                echo "Usage: $0 [--include-quick] [--no-clean] [--force] [--doc-sets=SOURCE_IDENTIFIER,...]"
                echo "  --include-quick : Also refresh small 'quick' doc mirrors"
                echo "  --no-clean      : Do not quarantine incomplete mirrors before refetch"
                echo "  --force         : Refresh even when mirrors look complete"
                echo "  --doc-sets      : Fetch only named official documentation sets"
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

# Dispatches one explicitly named documentation source, then retires a superseded mirror only after
# the replacement has passed its strategy-specific validation.
fetch_source() {
    local java_release=""
    local url=""
    local relative_mirror_path=""
    local name=""
    local cut_dirs=""
    local min_files=""
    local reject_regex=""
    local partial_mirror_allowed="false"
    local seed_document_type=""
    local seed_discovery_url=""
    local seed_source_prefix=""
    local superseded_relative_mirror_path=""

    while [ "$#" -gt 0 ]; do
        case "$1" in
            --java-release) java_release="$2"; shift 2 ;;
            --url) url="$2"; shift 2 ;;
            --mirror-path) relative_mirror_path="$2"; shift 2 ;;
            --name) name="$2"; shift 2 ;;
            --cut-directories) cut_dirs="$2"; shift 2 ;;
            --minimum-html-files) min_files="$2"; shift 2 ;;
            --reject-regex) reject_regex="$2"; shift 2 ;;
            --allow-partial) partial_mirror_allowed="true"; shift ;;
            --seed-document-type) seed_document_type="$2"; shift 2 ;;
            --seed-discovery-url) seed_discovery_url="$2"; shift 2 ;;
            --seed-source-prefix) seed_source_prefix="$2"; shift 2 ;;
            --superseded-mirror-path) superseded_relative_mirror_path="$2"; shift 2 ;;
            *) echo "Unknown documentation fetch option: $1" >&2; return 1 ;;
        esac
    done
    if [ -z "$url" ] || [ -z "$relative_mirror_path" ] || [ -z "$name" ] \
        || [ -z "$cut_dirs" ] || [ -z "$min_files" ]; then
        echo "Documentation fetch requires URL, mirror path, name, cut directories, and minimum HTML files" >&2
        return 1
    fi

    echo ""
    log "Processing: $name"
    log "URL: $url"
    log "Target: $DOCS_ROOT/$relative_mirror_path"
    local target_dir="$DOCS_ROOT/$relative_mirror_path"
    local fetch_target_directory="$target_dir"
    local staging_directory=""

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
    shift
    if "$fetch_strategy" "$@"; then
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

fetch_named_official_source() {
    local source_identifier="$1"
    local source_dispatch="${2:-record_documentation_fetch}"
    case "$source_identifier" in
        dev-java) "$source_dispatch" fetch_source --url "https://dev.java/learn/" --mirror-path "dev-java" --name "Dev.java Learning" --cut-directories 1 --minimum-html-files 40 ;;
        kotlin) "$source_dispatch" fetch_source --url "https://kotlinlang.org/docs/" --mirror-path "kotlin" --name "Kotlin Documentation" --cut-directories 1 --minimum-html-files 250 --seed-document-type xml-sitemap --seed-discovery-url "https://kotlinlang.org/sitemap.xml" --seed-source-prefix "https://kotlinlang.org/docs/" --superseded-mirror-path "kotlin/2.4.10" ;;
        scala) "$source_dispatch" fetch_source --url "https://docs.scala-lang.org/scala3/reference/" --mirror-path "scala" --name "Scala 3 Documentation" --cut-directories 2 --minimum-html-files 200 --superseded-mirror-path "scala/3.8.4" ;;
        groovy) "$source_dispatch" fetch_source --url "https://docs.groovy-lang.org/docs/groovy-5.0.7/html/documentation/" --mirror-path "groovy/5.0.7" --name "Groovy 5.0.7 Documentation" --cut-directories 4 --minimum-html-files 15 ;;
        clojure) "$source_dispatch" fetch_source --url "https://clojure.org/guides/" --mirror-path "clojure" --name "Clojure Guides" --cut-directories 1 --minimum-html-files 20 --reject-regex "/guides/guides$" --seed-document-type xml-sitemap --seed-discovery-url "https://clojure.org/sitemap.xml" --seed-source-prefix "https://clojure.org/guides/" --superseded-mirror-path "clojure/1.12.5" ;;
        spring-boot) "$source_dispatch" fetch_source --url "https://docs.spring.io/spring-boot/reference/" --mirror-path "spring-boot" --name "Spring Boot Reference" --cut-directories 2 --minimum-html-files 89 --seed-document-type html-links --seed-discovery-url "https://docs.spring.io/spring-boot/reference/index.html" --seed-source-prefix "https://docs.spring.io/spring-boot/reference/" --superseded-mirror-path "spring-boot/4.1.0" ;;
        quarkus) "$source_dispatch" fetch_source --url "https://quarkus.io/guides/" --mirror-path "quarkus" --name "Quarkus Guides" --cut-directories 1 --minimum-html-files 200 --reject-regex "%7[BbDd]" --superseded-mirror-path "quarkus/3.37.3" ;;
        java/java21-complete) "$source_dispatch" fetch_source --java-release 21 --url "https://docs.oracle.com/en/java/javase/21/docs/api/" --mirror-path "java/java21-complete" --name "Java 21 Complete API" --cut-directories 5 --minimum-html-files 5000 --allow-partial ;;
        java/java24-complete) "$source_dispatch" fetch_source --java-release 24 --url "https://docs.oracle.com/en/java/javase/24/docs/api/" --mirror-path "java/java24-complete" --name "Java 24 Complete API" --cut-directories 5 --minimum-html-files 5000 --allow-partial ;;
        java/java25-complete) "$source_dispatch" fetch_source --java-release 25 --url "https://docs.oracle.com/en/java/javase/25/docs/api/" --mirror-path "java/java25-complete" --name "Java 25 Complete API" --cut-directories 5 --minimum-html-files 5000 --allow-partial ;;
        *) echo "Unknown documentation source identifier: $source_identifier" >&2; return 1 ;;
    esac
}

fetch_selected_official_sources() {
    local requested_source_selector="$1"
    if [ -z "$requested_source_selector" ] \
        || [[ "$requested_source_selector" == ,* ]] \
        || [[ "$requested_source_selector" == *, ]] \
        || [[ "$requested_source_selector" == *,,* ]]; then
        echo "Documentation source selector contains a blank identifier" >&2
        return 1
    fi
    local -a requested_source_identifiers=()
    local IFS=','
    read -r -a requested_source_identifiers <<< "$requested_source_selector"
    local -a validated_source_identifiers=()
    local requested_source_identifier
    for requested_source_identifier in "${requested_source_identifiers[@]}"; do
        local validated_source_identifier
        for validated_source_identifier in "${validated_source_identifiers[@]+"${validated_source_identifiers[@]}"}"; do
            if [ "$validated_source_identifier" = "$requested_source_identifier" ]; then
                echo "Documentation source selector duplicates identifier: $requested_source_identifier" >&2
                return 1
            fi
        done
        fetch_named_official_source "$requested_source_identifier" true || return 1
        validated_source_identifiers+=("$requested_source_identifier")
    done
    for requested_source_identifier in "${requested_source_identifiers[@]}"; do
        fetch_named_official_source "$requested_source_identifier" || return 1
    done
}

fetch_all_official_sources() {
    local source_identifier
    for source_identifier in dev-java kotlin scala groovy clojure spring-boot quarkus \
        java/java21-complete java/java24-complete java/java25-complete; do
        fetch_named_official_source "$source_identifier"
    done
}

fetch_legacy_sources() {
    record_documentation_fetch fetch_source --url "$SPRING_AI_REFERENCE_BASE" --mirror-path "spring-ai-reference" --name "Spring AI Reference (stable)" --cut-directories 1 --minimum-html-files 80 --reject-regex "/spring-ai/reference/[0-9]|/spring-ai/reference/[^/]*SNAPSHOT"
    record_documentation_fetch fetch_source --url "$SPRING_AI_REFERENCE_2_BASE" --mirror-path "spring-ai-reference-2" --name "Spring AI Reference (2.0)" --cut-directories 1 --minimum-html-files 80 --reject-regex "/spring-ai/reference/[^/]*SNAPSHOT"
    record_documentation_fetch fetch_source --url "$SPRING_AI_API_STABLE_BASE" --mirror-path "spring-ai-api-stable" --name "Spring AI API (stable)" --cut-directories 1 --minimum-html-files 200
    record_documentation_fetch fetch_source --url "$SPRING_AI_API_2_BASE" --mirror-path "spring-ai-api-2" --name "Spring AI API (2.x)" --cut-directories 1 --minimum-html-files 200
    record_documentation_fetch fetch_source --url "$SPRING_FRAMEWORK_REFERENCE_BASE" --mirror-path "spring-framework-complete" --name "Spring Framework Reference (current)" --cut-directories 1 --minimum-html-files 3000 --reject-regex "/spring-framework/reference/[0-9]|/spring-framework/reference/[^/]*SNAPSHOT"
    record_documentation_fetch fetch_source --url "$SPRING_FRAMEWORK_API_BASE" --mirror-path "spring-framework-complete" --name "Spring Framework Javadoc (current)" --cut-directories 1 --minimum-html-files 7000
    record_documentation_fetch fetch_source --url "$JAVA25_RELEASE_NOTES_ISSUES_URL" --mirror-path "oracle/javase" --name "Java 25 Release Notes Issues" --cut-directories 3 --minimum-html-files 1
    record_documentation_fetch fetch_source --url "$IBM_JAVA25_ARTICLE_URL" --mirror-path "ibm/articles" --name "IBM Java 25 Overview" --cut-directories 1 --minimum-html-files 1
    record_documentation_fetch fetch_source --url "$JETBRAINS_JAVA25_BLOG_URL" --mirror-path "jetbrains/idea/2025/09" --name "JetBrains Java 25 Blog" --cut-directories 3 --minimum-html-files 1
}

fetch_quick_sources() {
    record_documentation_fetch fetch_source --url "$SPRING_FRAMEWORK_REFERENCE_BASE" --mirror-path "spring-framework" --name "Spring Framework Quick (reference landing)" --cut-directories 1 --minimum-html-files 1 --reject-regex "/spring-framework/reference/[0-9]|/spring-framework/reference/[^/]*SNAPSHOT"
    record_documentation_fetch fetch_source --url "$SPRING_AI_REFERENCE_BASE" --mirror-path "spring-ai" --name "Spring AI Quick (reference landing)" --cut-directories 1 --minimum-html-files 1 --reject-regex "/spring-ai/reference/[0-9]|/spring-ai/reference/[^/]*SNAPSHOT"
    record_documentation_fetch fetch_source --url "$SPRING_AI_REFERENCE_2_BASE" --mirror-path "spring-ai-2" --name "Spring AI Quick (2.0 landing)" --cut-directories 1 --minimum-html-files 1 --reject-regex "/spring-ai/reference/[^/]*SNAPSHOT"
}

run_documentation_fetch() {
set -euo pipefail
parse_fetch_arguments "$@"

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
    fetch_selected_official_sources "$DOCUMENTATION_SOURCE_SELECTOR" || return 1
else
    fetch_legacy_sources
    fetch_all_official_sources
    if [ "$INCLUDE_QUICK" = "true" ]; then
        fetch_quick_sources
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

echo ""
if [ "$TOTAL_FAILED" -gt 0 ] || [ "$TOTAL_PARTIAL" -gt 0 ]; then
    exit 1
fi
echo "Next step: Run 'make run' or './scripts/process_all_to_qdrant.sh' to process and upload to Qdrant"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    run_documentation_fetch "$@"
fi
