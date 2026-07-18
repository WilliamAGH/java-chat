#!/bin/bash

DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS=(
    --timeout=120
    --dns-timeout=30
    --connect-timeout=30
    --read-timeout=120
    --tries=5
    --waitretry=1
    --retry-connrefused
)

LEGACY_DOCUMENTATION_FETCH_PROJECTIONS=()
QUICK_DOCUMENTATION_FETCH_PROJECTIONS=()
BUILTIN_DOCUMENTATION_FETCH_PROJECTIONS_LOADED="false"

load_builtin_documentation_fetch_projections() {
    LEGACY_DOCUMENTATION_FETCH_PROJECTIONS=(
        "|${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|spring-ai-reference|Spring AI Reference (stable)|1|80|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_REFERENCE_2_BASE:-https://docs.spring.io/spring-ai/reference/2.0/}|spring-ai-reference-2|Spring AI Reference (2.0)|1|80|/spring-ai/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_API_STABLE_BASE:-https://docs.spring.io/spring-ai/docs/current/api/}|spring-ai-api-stable|Spring AI API (stable)|1|200||false"
        "|${SPRING_AI_API_2_BASE:-https://docs.spring.io/spring-ai/docs/2.0.x/api/}|spring-ai-api-2|Spring AI API (2.x)|1|200||false"
        "|${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|spring-framework-complete|Spring Framework Reference (current)|1|3000|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_FRAMEWORK_API_BASE:-https://docs.spring.io/spring-framework/docs/current/javadoc-api/}|spring-framework-complete|Spring Framework Javadoc (current)|1|7000||false"
        "|${JAVA25_RELEASE_NOTES_ISSUES_URL:-https://www.oracle.com/java/technologies/javase/25-relnote-issues.html}|oracle/javase|Java 25 Release Notes Issues|3|1||false"
        "|${IBM_JAVA25_ARTICLE_URL:-https://developer.ibm.com/articles/java-whats-new-java25/}|ibm/articles|IBM Java 25 Overview|1|1||false"
        "|${JETBRAINS_JAVA25_BLOG_URL:-https://blog.jetbrains.com/idea/2025/09/java-25-lts-and-intellij-idea/}|jetbrains/idea/2025/09|JetBrains Java 25 Blog|3|1||false"
    )
    QUICK_DOCUMENTATION_FETCH_PROJECTIONS=(
        "|${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|spring-framework|Spring Framework Quick (reference landing)|1|1|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|spring-ai|Spring AI Quick (reference landing)|1|1|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_REFERENCE_2_BASE:-https://docs.spring.io/spring-ai/reference/2.0/}|spring-ai-2|Spring AI Quick (2.0 landing)|1|1|/spring-ai/reference/[^/]*SNAPSHOT|false"
    )
    BUILTIN_DOCUMENTATION_FETCH_PROJECTIONS_LOADED="true"
}

ensure_builtin_documentation_fetch_projections_loaded() {
    if [ "$BUILTIN_DOCUMENTATION_FETCH_PROJECTIONS_LOADED" != "true" ]; then
        load_builtin_documentation_fetch_projections
    fi
}

append_manifest_documentation_fetch_sources() {
    local selected_doc_sets="${1:-}"
    local selection_enabled="false"
    local -a requested_doc_sets=()
    local -a retained_requested_doc_sets=("")
    if [ "$#" -gt 0 ]; then
        selection_enabled="true"
        if [ -z "$selected_doc_sets" ] \
            || [[ "$selected_doc_sets" == ,* ]] \
            || [[ "$selected_doc_sets" == *, ]] \
            || [[ "$selected_doc_sets" == *,,* ]]; then
            echo "Documentation source selector contains a blank docSet" >&2
            return 1
        fi

        local IFS=','
        read -r -a requested_doc_sets <<< "$selected_doc_sets"
        local requested_doc_set
        local retained_requested_doc_set
        for requested_doc_set in "${requested_doc_sets[@]}"; do
            if has_boundary_whitespace "$requested_doc_set" \
                || has_manifest_control_character "$requested_doc_set"; then
                echo "Documentation source selector has an invalid docSet: $requested_doc_set" >&2
                return 1
            fi
            for retained_requested_doc_set in "${retained_requested_doc_sets[@]}"; do
                if [ "$retained_requested_doc_set" = "$requested_doc_set" ]; then
                    echo "Documentation source selector duplicates docSet: $requested_doc_set" >&2
                    return 1
                fi
            done
            retained_requested_doc_sets+=("$requested_doc_set")
        done
    fi

    local -a matched_doc_sets=("")
    local -a selected_fetch_projections=()
    local documentation_source_index
    for documentation_source_index in "${!DOCUMENTATION_SOURCE_PROJECTIONS[@]}"; do
        local documentation_fetch_projection="${DOCUMENTATION_SOURCE_PROJECTIONS[$documentation_source_index]}"
        local docSet
        docSet="$(documentation_source_manifest_field "$documentation_fetch_projection" "docSet")"

        if [ "$selection_enabled" = "true" ]; then
            local doc_set_requested="false"
            local requested_doc_set
            for requested_doc_set in "${requested_doc_sets[@]}"; do
                if [ "$requested_doc_set" = "$docSet" ]; then
                    doc_set_requested="true"
                    matched_doc_sets+=("$docSet")
                    break
                fi
            done
            if [ "$doc_set_requested" = "false" ]; then
                continue
            fi
        fi

        selected_fetch_projections+=("$documentation_fetch_projection")
    done

    if [ "$selection_enabled" = "true" ]; then
        local requested_doc_set
        local matched_doc_set
        for requested_doc_set in "${requested_doc_sets[@]}"; do
            local requested_doc_set_matched="false"
            for matched_doc_set in "${matched_doc_sets[@]}"; do
                if [ "$matched_doc_set" = "$requested_doc_set" ]; then
                    requested_doc_set_matched="true"
                    break
                fi
            done
            if [ "$requested_doc_set_matched" = "false" ]; then
                echo "Unknown documentation source docSet: $requested_doc_set" >&2
                return 1
            fi
        done
    fi
    DOC_SOURCES+=("${selected_fetch_projections[@]}")
}

documentation_fetch_projection_relative_mirror_path() {
    local documentation_fetch_projection="$1"
    local projection_after_java_release="${documentation_fetch_projection#*|}"
    local projection_after_documentation_url="${projection_after_java_release#*|}"
    printf '%s\n' "${projection_after_documentation_url%%|*}"
}

require_lifecycle_root_disjoint_from_external_fetch_roots() {
    local superseded_relative_mirror_path="$1"
    ensure_builtin_documentation_fetch_projections_loaded
    local -a external_fetch_projections=(
        "${JAVA_API_SOURCE_PROJECTIONS[@]+"${JAVA_API_SOURCE_PROJECTIONS[@]}"}"
        "${LEGACY_DOCUMENTATION_FETCH_PROJECTIONS[@]}"
        "${QUICK_DOCUMENTATION_FETCH_PROJECTIONS[@]}"
    )
    local external_fetch_projection
    for external_fetch_projection in "${external_fetch_projections[@]}"; do
        local external_relative_mirror_path
        external_relative_mirror_path="$(documentation_fetch_projection_relative_mirror_path \
            "$external_fetch_projection")"
        if documentation_mirror_roots_overlap \
            "$superseded_relative_mirror_path" \
            "$external_relative_mirror_path"; then
            printf 'Documentation source superseded mirror path overlaps external active mirror path: %s -> %s\n' \
                "$superseded_relative_mirror_path" \
                "$external_relative_mirror_path" >&2
            return 1
        fi
    done
}

validate_documentation_source_lifecycle_external_roots() {
    local documentation_source_projection
    for documentation_source_projection in "${DOCUMENTATION_SOURCE_PROJECTIONS[@]}"; do
        local superseded_relative_mirror_path
        superseded_relative_mirror_path="$(documentation_source_manifest_field \
            "$documentation_source_projection" \
            "supersededRelativeMirrorPath")"
        if [ -n "$superseded_relative_mirror_path" ] \
            && ! require_lifecycle_root_disjoint_from_external_fetch_roots \
                "$superseded_relative_mirror_path"; then
            return 1
        fi
    done
}

# Validates the crawler exit status and manifest-owned completeness policy.
validate_fetch_result() {
    local wget_exit_code="$1"
    local target_dir="$2"
    local name="$3"
    local minimum_html_files="$4"
    local partial_mirror_allowed="$5"
    local recursive_server_errors_allowed="${6:-false}"
    local fetched_html_count
    fetched_html_count="$(count_html_files "$target_dir")"

    if [ "$wget_exit_code" -ne 0 ] \
        && { [ "$recursive_server_errors_allowed" != "true" ] || [ "$wget_exit_code" -ne 8 ]; }; then
        log "${RED}✗ Failed to fetch $name (exit code: $wget_exit_code)${NC}"
        return 1
    fi
    if [ "$fetched_html_count" -eq 0 ]; then
        log "${RED}✗ $name fetch produced no HTML files${NC}"
        return 1
    fi
    if [ "$minimum_html_files" -gt 0 ] && [ "$fetched_html_count" -lt "$minimum_html_files" ]; then
        if [ "$partial_mirror_allowed" = "true" ]; then
            log "${YELLOW}⚠ $name mirror is still incomplete after fetch: $fetched_html_count HTML files (expected $minimum_html_files+); keeping partial mirror for incremental reruns${NC}"
            return "$DOCUMENTATION_FETCH_PARTIAL_STATUS"
        fi
        log "${RED}✗ $name mirror is still incomplete after fetch: $fetched_html_count HTML files (expected $minimum_html_files+)${NC}"
        return 1
    fi
    log "${GREEN}✓ $name fetched successfully: $fetched_html_count HTML files${NC}"
}

# Fetches a manifest-governed Java API using its explicit Javadoc seed.
fetch_java_api_javadoc_seed() {
    local target_dir="$1"
    local name="$2"
    local cut_dirs="$3"
    local minimum_html_files="$4"
    local reject_regex="${5:-}"
    local partial_mirror_allowed="$6"
    local remote_base_url="$7"
    local seed_file="$target_dir/.oracle-javadoc-seed.txt"
    local wget_seed_args=(
        --timestamping
        --no-host-directories
        --force-directories
        --cut-dirs="$cut_dirs"
        --input-file="$seed_file"
        --directory-prefix="$target_dir"
        --max-redirect=0
        --show-progress
        --progress=bar:force
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}"
        --user-agent="java-chat-doc-fetcher/1.0"
    )
    if [ -n "$reject_regex" ]; then
        wget_seed_args+=(--reject-regex="$reject_regex")
    fi

    local wget_exit_code
    wget "${wget_seed_args[@]}" 2>&1 | tee -a "$LOG_FILE"
    wget_exit_code="${PIPESTATUS[0]}"
    cd - > /dev/null
    local fetched_html_count
    fetched_html_count="$(count_html_files "$target_dir")"
    if [ "$wget_exit_code" -eq 0 ] \
        && [ "$fetched_html_count" -gt 0 ] \
        && { [ "$minimum_html_files" -le 0 ] || [ "$fetched_html_count" -ge "$minimum_html_files" ]; } \
        && ! verify_java_api_seed_mirror "$remote_base_url" "$target_dir" "$name" "$cut_dirs"; then
        return 1
    fi
    validate_fetch_result \
        "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed" false
}

# Removes artifacts that GNU Wget writes outside the generic HTML mirror contract.
# Generic recursive roots have no governed hidden seed marker, so only HTML files remain.
remove_generic_mirror_non_html_artifacts() {
    local target_dir="$1"
    local non_html_file
    while IFS= read -r -d '' non_html_file; do
        if ! rm -f -- "$non_html_file"; then
            return 1
        fi
    done < <(find "$target_dir" -type f ! \( -name "*.html" -o -name "*.htm" \) -print0)
}

# Fetches generic HTML documentation recursively, retaining extensionless pages without binary requisites.
fetch_docs_mirror() {
    local url="$1"
    local target_dir="$2"
    local name="$3"
    local cut_dirs="$4"
    local minimum_html_files="$5"
    local reject_regex="${6:-}"
    local partial_mirror_allowed="$7"
    local wget_args=(
        --mirror
        --convert-links
        --adjust-extension
        --no-parent
        --no-host-directories
        --cut-dirs="$cut_dirs"
        --reject="index.html?*,css,js,mjs,png,jpg,jpeg,gif,svg,webp,ico,woff,woff2,ttf,eot,map,pdf,zip,gz,tgz,tar,jar"
        --quiet
        --show-progress
        --progress=bar:force
        --timeout=30
        --dns-timeout=30
        --connect-timeout=30
        --read-timeout=30
        --tries=3
        --waitretry=1
        --retry-connrefused
        --no-verbose
    )
    if [ -n "$reject_regex" ]; then
        wget_args+=(--reject-regex="$reject_regex")
    fi

    local wget_exit_code
    wget "${wget_args[@]}" "$url" 2>&1 | tee -a "$LOG_FILE"
    wget_exit_code="${PIPESTATUS[0]}"
    cd - > /dev/null
    local validation_status
    if validate_fetch_result \
        "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed" true; then
        validation_status=0
    else
        validation_status="$?"
    fi
    if { [ "$validation_status" -eq 0 ] \
            || [ "$validation_status" -eq "$DOCUMENTATION_FETCH_PARTIAL_STATUS" ]; } \
        && ! remove_generic_mirror_non_html_artifacts "$target_dir"; then
        log "${RED}✗ Could not remove non-HTML fetch artifacts for $name${NC}"
        return 1
    fi
    return "$validation_status"
}

# Fetches an explicit manifest-governed seed discovered from structured XML or HTML.
fetch_discovered_documentation_seed() {
    local canonical_prefix="$1"
    local target_dir="$2"
    local name="$3"
    local cut_directories="$4"
    local minimum_html_files="$5"
    local reject_regex="${6:-}"
    local partial_mirror_allowed="$7"
    local seed_document_type="$8"
    local seed_discovery_url="$9"
    local seed_source_prefix="${10}"
    local discovery_file
    if ! discovery_file="$(mktemp "$target_dir/.documentation-discovery.XXXXXX")"; then
        cd - > /dev/null
        log "${RED}✗ Could not create a structured discovery file for $name${NC}"
        return 1
    fi
    local seed_file="$target_dir/.documentation-seed.txt"
    local generated_seed_file
    if ! generated_seed_file="$(mktemp "$target_dir/.documentation-seed.XXXXXX")"; then
        rm -f "$discovery_file"
        cd - > /dev/null
        log "${RED}✗ Could not create a generated seed file for $name${NC}"
        return 1
    fi
    local mirror_paths_file
    if ! mirror_paths_file="$(mktemp "$target_dir/.documentation-seed-paths.XXXXXX")"; then
        rm -f "$discovery_file" "$generated_seed_file"
        cd - > /dev/null
        log "${RED}✗ Could not create generated mirror paths for $name${NC}"
        return 1
    fi
    local wget_discovery_arguments=(
        --quiet
        --output-document="$discovery_file"
        --max-redirect=0
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}"
    )

    if ! wget "${wget_discovery_arguments[@]}" "$seed_discovery_url"; then
        rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file"
        cd - > /dev/null
        log "${RED}✗ Failed to fetch structured discovery document for $name${NC}"
        return 1
    fi
    if ! python3 "$SCRIPT_DIR/documentation_seed.py" \
        --document-type "$seed_document_type" \
        --input "$discovery_file" \
        --discovery-url "$seed_discovery_url" \
        --source-prefix "$seed_source_prefix" \
        --canonical-prefix "$canonical_prefix" \
        --reject-regex "$reject_regex" \
        --output "$generated_seed_file" \
        --mirror-path-output "$mirror_paths_file" \
        --cut-directories "$cut_directories"; then
        rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file"
        cd - > /dev/null
        log "${RED}✗ Structured discovery failed for $name${NC}"
        return 1
    fi
    if [ ! -s "$generated_seed_file" ] || [ ! -s "$mirror_paths_file" ]; then
        rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file"
        cd - > /dev/null
        log "${RED}✗ Structured discovery produced incomplete seed outputs for $name${NC}"
        return 1
    fi
    if ! mv "$generated_seed_file" "$seed_file"; then
        rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file"
        cd - > /dev/null
        log "${RED}✗ Structured discovery could not replace the active seed for $name${NC}"
        return 1
    fi
    rm -f "$discovery_file"
    if ! reconcile_seeded_html_mirror \
        "$target_dir" "$name" "$mirror_paths_file" "unseeded-documentation"; then
        rm -f "$mirror_paths_file"
        cd - > /dev/null
        return 1
    fi

    local wget_seed_arguments=(
        --timestamping
        --no-host-directories
        --force-directories
        --cut-dirs="$cut_directories"
        --input-file="$seed_file"
        --directory-prefix="$target_dir"
        --adjust-extension
        --convert-links
        --max-redirect=0
        --show-progress
        --progress=bar:force
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}"
        --user-agent="java-chat-doc-fetcher/1.0"
    )
    if [ -n "$reject_regex" ]; then
        wget_seed_arguments+=(--reject-regex="$reject_regex")
    fi
    local wget_exit_code
    wget "${wget_seed_arguments[@]}" 2>&1 | tee -a "$LOG_FILE"
    wget_exit_code="${PIPESTATUS[0]}"
    cd - > /dev/null
    if [ "$wget_exit_code" -ne 0 ]; then
        rm -f "$mirror_paths_file"
        log "${RED}✗ Failed to fetch $name seed URLs (exit code: $wget_exit_code)${NC}"
        return 1
    fi
    if ! verify_seeded_html_mirror "$target_dir" "$name" "$mirror_paths_file"; then
        rm -f "$mirror_paths_file"
        return 1
    fi
    rm -f "$mirror_paths_file"
    validate_fetch_result \
        "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed" false
}

fetch_projection_documentation_source() {
    local documentation_source_projection="$1"
    local projection_delimiters="${documentation_source_projection//[^|]/}"
    if [ "${#projection_delimiters}" -ne 7 ]; then
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

    if [ -z "$documentation_source_url" ] \
        || [ -z "$relative_mirror_path" ] \
        || [ -z "$documentation_source_name" ]; then
        log "${RED}✗ Documentation source projection has a blank required field${NC}"
        return 1
    fi
    local projection_text
    for projection_text in \
        "$documentation_source_url" \
        "$relative_mirror_path" \
        "$documentation_source_name" \
        "$reject_regex"; do
        if has_boundary_whitespace "$projection_text" \
            || has_manifest_control_character "$projection_text"; then
            log "${RED}✗ Documentation source projection has invalid text fields${NC}"
            return 1
        fi
    done
    if [ -n "$java_release" ] \
        && { ! is_canonical_manifest_integer "$java_release" || [ "$java_release" = "0" ]; }; then
        log "${RED}✗ Documentation source Java release must be blank or a positive canonical integer${NC}"
        return 1
    fi
    if [ -n "$java_release" ]; then
        if ! is_absolute_https_remote_base_url "$documentation_source_url"; then
            log "${RED}✗ Java API documentation source URL must be an absolute HTTPS base URL${NC}"
            return 1
        fi
    elif ! is_absolute_https_remote_url "$documentation_source_url"; then
        log "${RED}✗ Documentation source URL must be an absolute HTTPS URL${NC}"
        return 1
    fi
    if ! is_normalized_relative_mirror_path "$relative_mirror_path"; then
        log "${RED}✗ Documentation source mirror path must be normalized and relative${NC}"
        return 1
    fi
    if ! is_canonical_manifest_integer "$cut_directories"; then
        log "${RED}✗ Documentation source cut-directories value must be a canonical integer${NC}"
        return 1
    fi
    if ! is_canonical_manifest_integer "$minimum_html_files" \
        || [ "$minimum_html_files" = "0" ]; then
        log "${RED}✗ Documentation source minimum HTML files must be a positive canonical integer${NC}"
        return 1
    fi
    if [ "$partial_mirror_allowed" != "true" ] \
        && [ "$partial_mirror_allowed" != "false" ]; then
        log "${RED}✗ Documentation source partial-mirror policy must be true or false${NC}"
        return 1
    fi

    echo ""
    log "Processing: $documentation_source_name"
    log "URL: $documentation_source_url"
    log "Target: $DOCS_ROOT/$relative_mirror_path"

    fetch_docs \
        "$java_release" \
        "$documentation_source_url" \
        "$relative_mirror_path" \
        "$documentation_source_name" \
        "$cut_directories" \
        "$minimum_html_files" \
        "$reject_regex" \
        "$partial_mirror_allowed"
}

fetch_manifest_documentation_source() {
    local documentation_source_projection="$1"
    local fetch_url
    local relative_mirror_path
    local display_name
    local minimum_html_files
    local reject_regex
    local allow_partial
    local seed_document_type
    local seed_discovery_url
    local seed_source_prefix
    local superseded_relative_mirror_path
    fetch_url="$(documentation_source_manifest_field "$documentation_source_projection" "fetchUrl")"
    relative_mirror_path="$(documentation_source_manifest_field "$documentation_source_projection" "relativeMirrorPath")"
    display_name="$(documentation_source_manifest_field "$documentation_source_projection" "displayName")"
    minimum_html_files="$(documentation_source_manifest_field "$documentation_source_projection" "minimumHtmlFiles")"
    reject_regex="$(documentation_source_manifest_field "$documentation_source_projection" "rejectRegex")"
    allow_partial="$(documentation_source_manifest_field "$documentation_source_projection" "allowPartial")"
    seed_document_type="$(documentation_source_manifest_field "$documentation_source_projection" "seedDocumentType")"
    seed_discovery_url="$(documentation_source_manifest_field "$documentation_source_projection" "seedDiscoveryUrl")"
    seed_source_prefix="$(documentation_source_manifest_field "$documentation_source_projection" "seedSourcePrefix")"
    superseded_relative_mirror_path="$(documentation_source_manifest_field \
        "$documentation_source_projection" \
        "supersededRelativeMirrorPath")"

    echo ""
    log "Processing: $display_name"
    log "URL: $fetch_url"
    log "Target: $DOCS_ROOT/$relative_mirror_path"
    fetch_docs \
        "" \
        "$fetch_url" \
        "$relative_mirror_path" \
        "$display_name" \
        "$(documentation_fetch_cut_directories "$fetch_url")" \
        "$minimum_html_files" \
        "$reject_regex" \
        "$allow_partial" \
        "$seed_document_type" \
        "$seed_discovery_url" \
        "$seed_source_prefix" \
        "$superseded_relative_mirror_path"
}
