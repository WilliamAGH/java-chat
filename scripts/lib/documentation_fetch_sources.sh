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
    for documentation_source_index in "${!DOCUMENTATION_SOURCE_FETCH_PROJECTIONS[@]}"; do
        local docSet="${DOCUMENTATION_SOURCE_DOC_SET_PROJECTIONS[$documentation_source_index]}"
        local documentation_fetch_projection="${DOCUMENTATION_SOURCE_FETCH_PROJECTIONS[$documentation_source_index]}"

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

append_legacy_documentation_fetch_sources() {
    DOC_SOURCES+=(
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
}

append_quick_documentation_fetch_sources() {
    DOC_SOURCES+=(
        "|${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|spring-framework|Spring Framework Quick (reference landing)|1|1|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|spring-ai|Spring AI Quick (reference landing)|1|1|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_REFERENCE_2_BASE:-https://docs.spring.io/spring-ai/reference/2.0/}|spring-ai-2|Spring AI Quick (2.0 landing)|1|1|/spring-ai/reference/[^/]*SNAPSHOT|false"
    )
}

# Validates the crawler exit status and manifest-owned completeness policy.
validate_fetch_result() {
    local wget_exit_code="$1"
    local target_dir="$2"
    local name="$3"
    local minimum_html_files="$4"
    local partial_mirror_allowed="$5"
    local fetched_html_count
    fetched_html_count="$(count_html_files "$target_dir")"

    if [ "$wget_exit_code" -ne 0 ] && [ "$wget_exit_code" -ne 8 ]; then
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
    local seed_file="$target_dir/.oracle-javadoc-seed.txt"
    local wget_seed_args=(
        --timestamping
        --no-host-directories
        --force-directories
        --cut-dirs="$cut_dirs"
        --input-file="$seed_file"
        --directory-prefix="$target_dir"
        --show-progress
        --progress=bar:force
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}"
        --user-agent="java-chat-doc-fetcher/1.0"
    )
    if [ -n "$reject_regex" ]; then
        wget_seed_args+=(--reject-regex="$reject_regex")
    fi

    wget "${wget_seed_args[@]}" 2>&1 | tee -a "$LOG_FILE"
    local wget_exit_code=$?
    cd - > /dev/null
    validate_fetch_result "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"
}

# Fetches generic documentation recursively, retaining extensionless pages and their page requisites.
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
        --page-requisites
        --no-parent
        --no-host-directories
        --cut-dirs="$cut_dirs"
        --reject="index.html?*"
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

    wget "${wget_args[@]}" "$url" 2>&1 | tee -a "$LOG_FILE"
    local wget_exit_code=$?
    cd - > /dev/null
    validate_fetch_result "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"
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
    discovery_file="$(mktemp "$target_dir/.documentation-discovery.XXXXXX")"
    local seed_file="$target_dir/.documentation-seed.txt"
    local wget_discovery_arguments=(
        --quiet
        --output-document="$discovery_file"
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}"
    )

    if ! wget "${wget_discovery_arguments[@]}" "$seed_discovery_url"; then
        rm -f "$discovery_file"
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
        --output "$seed_file"; then
        rm -f "$discovery_file"
        cd - > /dev/null
        log "${RED}✗ Structured discovery failed for $name${NC}"
        return 1
    fi
    rm -f "$discovery_file"

    local wget_seed_arguments=(
        --timestamping
        --no-host-directories
        --force-directories
        --cut-dirs="$cut_directories"
        --input-file="$seed_file"
        --directory-prefix="$target_dir"
        --adjust-extension
        --convert-links
        --show-progress
        --progress=bar:force
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}"
        --user-agent="java-chat-doc-fetcher/1.0"
    )
    if [ -n "$reject_regex" ]; then
        wget_seed_arguments+=(--reject-regex="$reject_regex")
    fi
    wget "${wget_seed_arguments[@]}" 2>&1 | tee -a "$LOG_FILE"
    local wget_exit_code=$?
    cd - > /dev/null
    validate_fetch_result \
        "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"
}

fetch_documentation_source() {
    local documentation_source_projection="$1"
    local fetch_projection_delimiters="${documentation_source_projection//[^|]/}"
    if [ "${#fetch_projection_delimiters}" -ne 7 ] \
        && [ "${#fetch_projection_delimiters}" -ne 10 ]; then
        log "${RED}✗ Documentation source projection must contain exactly eight or eleven fields${NC}"
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
    local seed_document_type
    local seed_discovery_url
    local seed_source_prefix
    IFS='|' read -r java_release documentation_source_url relative_mirror_path documentation_source_name cut_directories minimum_html_files reject_regex partial_mirror_allowed seed_document_type seed_discovery_url seed_source_prefix <<< "$documentation_source_projection"

    if [ -z "$documentation_source_url" ] || [ -z "$relative_mirror_path" ] || [ -z "$documentation_source_name" ]; then
        log "${RED}✗ Documentation source projection has a blank required field${NC}"
        return 1
    fi
    if has_boundary_whitespace "$documentation_source_url" \
        || has_boundary_whitespace "$relative_mirror_path" \
        || has_boundary_whitespace "$documentation_source_name" \
        || has_boundary_whitespace "$reject_regex" \
        || has_boundary_whitespace "$seed_document_type" \
        || has_boundary_whitespace "$seed_discovery_url" \
        || has_boundary_whitespace "$seed_source_prefix" \
        || has_manifest_control_character "$documentation_source_url" \
        || has_manifest_control_character "$relative_mirror_path" \
        || has_manifest_control_character "$documentation_source_name" \
        || has_manifest_control_character "$reject_regex" \
        || has_manifest_control_character "$seed_document_type" \
        || has_manifest_control_character "$seed_discovery_url" \
        || has_manifest_control_character "$seed_source_prefix"; then
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
    if [ -n "$seed_discovery_url" ]; then
        if [ -n "$java_release" ] \
            || [ -z "$seed_document_type" ] \
            || [ -z "$seed_source_prefix" ] \
            || ! is_absolute_https_remote_url "$seed_discovery_url" \
            || ! is_absolute_http_remote_base_url "$seed_source_prefix"; then
            log "${RED}✗ Documentation source seed discovery fields are invalid${NC}"
            return 1
        fi
    elif [ -n "$seed_document_type" ] || [ -n "$seed_source_prefix" ]; then
        log "${RED}✗ Documentation source seed discovery fields are incomplete${NC}"
        return 1
    fi

    echo ""
    log "Processing: $documentation_source_name"
    log "URL: $documentation_source_url"
    log "Target: $DOCS_ROOT/$relative_mirror_path"

    if [ "${#fetch_projection_delimiters}" -eq 10 ]; then
        fetch_docs \
            "$java_release" \
            "$documentation_source_url" \
            "$relative_mirror_path" \
            "$documentation_source_name" \
            "$cut_directories" \
            "$minimum_html_files" \
            "$reject_regex" \
            "$partial_mirror_allowed" \
            "$seed_document_type" \
            "$seed_discovery_url" \
            "$seed_source_prefix"
        return
    fi
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
