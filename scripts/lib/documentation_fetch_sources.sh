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

# Validates crawler exit status and the source-specific completeness policy.
validate_fetch_result() {
    local wget_exit_code="$1"
    local target_dir="$2"
    local name="$3"
    local minimum_html_files="$4"
    local partial_mirror_allowed="$5"
    local fetched_html_count
    fetched_html_count="$(count_html_files "$target_dir")"

    if [ "$wget_exit_code" -ne 0 ]; then
        log "${RED}✗ Failed to fetch $name (exit code: $wget_exit_code)${NC}"
        return 1
    fi
    if [ "$fetched_html_count" -eq 0 ]; then
        log "${RED}✗ $name fetch produced no HTML files${NC}"
        return 1
    fi
    if [ "$minimum_html_files" -gt 0 ] && [ "$fetched_html_count" -lt "$minimum_html_files" ]; then
        log "${RED}✗ $name mirror is still incomplete after fetch: $fetched_html_count HTML files (expected $minimum_html_files+)${NC}"
        return 1
    fi
    log "${GREEN}✓ $name fetched successfully: $fetched_html_count HTML files${NC}"
}

# Fetches one governed article without recursively following unrelated page links.
fetch_single_documentation_page() {
    local url="$1"
    local target_dir="$2"
    local name="$3"
    local cut_directories="$4"
    local minimum_html_files="$5"
    local partial_mirror_allowed="$6"
    local projected_page_path
    if ! projected_page_path="$(python3 "$SCRIPT_DIR/documentation_seed.py" \
        --project-mirror-path "$url" "$cut_directories")"; then
        cd - > /dev/null
        log "${RED}✗ Could not project the governed page path for $name${NC}"
        return 1
    fi
    local mirror_paths_file
    if ! mirror_paths_file="$(mktemp "$target_dir/.single-page-path.XXXXXX")"; then
        cd - > /dev/null
        log "${RED}✗ Could not create the governed page inventory for $name${NC}"
        return 1
    fi
    printf '%s\n' "$projected_page_path" > "$mirror_paths_file"
    if ! reconcile_seeded_html_mirror \
        "$target_dir" "$name" "$mirror_paths_file" "unseeded-single-page"; then
        rm -f "$mirror_paths_file"
        cd - > /dev/null
        return 1
    fi
    local target_page="$target_dir/$projected_page_path"
    if ! mkdir -p "$(dirname "$target_page")"; then
        rm -f "$mirror_paths_file"
        cd - > /dev/null
        log "${RED}✗ Could not create the governed page directory for $name${NC}"
        return 1
    fi

    local wget_exit_code
    wget \
        --output-document="$target_page" \
        --max-redirect=0 \
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}" \
        --user-agent="java-chat-doc-fetcher/1.0" \
        "$url" 2>&1 | tee -a "$LOG_FILE"
    wget_exit_code="${PIPESTATUS[0]}"
    cd - > /dev/null
    if [ "$wget_exit_code" -eq 0 ] \
        && ! verify_seeded_html_mirror "$target_dir" "$name" "$mirror_paths_file"; then
        rm -f "$mirror_paths_file"
        return 1
    fi
    rm -f "$mirror_paths_file"
    validate_fetch_result \
        "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"
}

# Fetches a Java API using its explicit Javadoc seed.
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
        "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"
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
        --max-redirect=0
        --no-host-directories
        --cut-dirs="$cut_dirs"
        --ignore-tags=img,script,link,style
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
        "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"; then
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

# Fetches an explicit seed discovered from structured XML or HTML.
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
    local seed_reject_regex="${11:-}"
    if [ -z "$seed_reject_regex" ]; then
        seed_reject_regex="$reject_regex"
    fi
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
        --reject-regex "$seed_reject_regex" \
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
        "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"
}
