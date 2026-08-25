#!/bin/bash

DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS=(
    --timeout=120
    --dns-timeout=30
    --connect-timeout=30
    --read-timeout=120
    --tries=5
    --waitretry=1
    --retry-connrefused
    --max-threads=1
)
if [[ "${DOCUMENTATION_SINGLE_PAGE_BROWSER_USER_AGENT:-}" != "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" ]]; then
    DOCUMENTATION_SINGLE_PAGE_BROWSER_USER_AGENT="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
fi
readonly DOCUMENTATION_SINGLE_PAGE_BROWSER_USER_AGENT

# Validates one downloaded LLM-facing text resource without interpreting its Markdown structure.
validate_documentation_text_resource() {
    local documentation_text_path="$1"
    local documentation_source_name="$2"
    local minimum_documentation_bytes="$3"
    shift 3
    local -a required_documentation_fragments=("$@")
    if [ ! -s "$documentation_text_path" ]; then
        log "${RED}✗ $documentation_source_name text resource is missing or empty${NC}"
        return 1
    fi
    local documentation_byte_count
    documentation_byte_count="$(wc -c < "$documentation_text_path")"
    if [ "$documentation_byte_count" -lt "$minimum_documentation_bytes" ]; then
        log "${RED}✗ $documentation_source_name text resource is incomplete: $documentation_byte_count bytes (expected $minimum_documentation_bytes+)${NC}"
        return 1
    fi
    local required_documentation_fragment
    for required_documentation_fragment in "${required_documentation_fragments[@]}"; do
        if ! grep -Fq -- "$required_documentation_fragment" "$documentation_text_path"; then
            log "${RED}✗ $documentation_source_name text resource is missing required coverage: $required_documentation_fragment${NC}"
            return 1
        fi
    done
}

# Downloads one official LLM-facing text resource under the shared bounded retry policy.
fetch_documentation_text_resource() {
    local documentation_text_url="$1"
    local documentation_text_path="$2"
    local documentation_source_name="$3"
    if ! python3 "$SCRIPT_DIR/documentation_seed.py" --validate-remote-url "$documentation_text_url"; then
        log "${RED}✗ $documentation_source_name text resource URL is invalid${NC}"
        return 1
    fi
    if ! wget2 \
        --quiet \
        --output-document="$documentation_text_path" \
        --max-redirect=0 \
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}" \
        --user-agent="java-chat-doc-fetcher/1.0" \
        "$documentation_text_url"; then
        log "${RED}✗ Failed to fetch $documentation_source_name text resource${NC}"
        return 1
    fi
}

# Requires an independent LLM-facing sentinel to reject grossly truncated or topic-incomplete sources.
validate_documentation_llm_sentinel() {
    local sentinel_url="$1"
    local target_directory="$2"
    local documentation_source_name="$3"
    local minimum_sentinel_bytes="$4"
    shift 4
    local sentinel_path
    if ! sentinel_path="$(mktemp "$target_directory/.documentation-llm-sentinel.XXXXXX")"; then
        log "${RED}✗ Could not create the LLM sentinel for $documentation_source_name${NC}"
        return 1
    fi
    local sentinel_status=0
    fetch_documentation_text_resource \
        "$sentinel_url" "$sentinel_path" "$documentation_source_name LLM sentinel" \
        || sentinel_status=$?
    if [ "$sentinel_status" -eq 0 ]; then
        validate_documentation_text_resource \
            "$sentinel_path" "$documentation_source_name LLM sentinel" "$minimum_sentinel_bytes" "$@" \
            || sentinel_status=$?
    fi
    rm -f "$sentinel_path"
    return "$sentinel_status"
}

# Fails closed when a publisher adds or replaces sitemap shards outside the configured fetch inventory.
validate_documentation_sitemap_index() {
    local sitemap_index_url="$1"
    local expected_sitemap_url="$2"
    local target_directory="$3"
    local documentation_source_name="$4"
    local sitemap_index_path
    if ! sitemap_index_path="$(mktemp "$target_directory/.documentation-sitemap-index.XXXXXX")"; then
        log "${RED}✗ Could not create the sitemap-index validation file for $documentation_source_name${NC}"
        return 1
    fi
    local sitemap_index_status=0
    fetch_documentation_text_resource \
        "$sitemap_index_url" "$sitemap_index_path" "$documentation_source_name sitemap index" \
        || sitemap_index_status=$?
    if [ "$sitemap_index_status" -eq 0 ] \
        && ! python3 "$SCRIPT_DIR/documentation_seed.py" \
            --validate-sitemap-index \
            --input "$sitemap_index_path" \
            --expected-sitemap-url "$expected_sitemap_url"; then
        log "${RED}✗ $documentation_source_name sitemap topology changed${NC}"
        sitemap_index_status=1
    fi
    rm -f "$sitemap_index_path"
    return "$sitemap_index_status"
}

# Publishes a bounded complete Markdown reference as one HTML transport document for normal ingestion.
fetch_plain_text_documentation() {
    local documentation_text_url="$1"
    local target_directory="$2"
    local documentation_source_name="$3"
    local documentation_title="$4"
    local minimum_documentation_bytes="$5"
    local minimum_html_files="$6"
    local partial_mirror_allowed="$7"
    shift 7
    local source_text_path
    if ! source_text_path="$(mktemp "$target_directory/.plain-text-documentation.XXXXXX")"; then
        log "${RED}✗ Could not create the plain-text source for $documentation_source_name${NC}"
        return 1
    fi
    local documentation_fetch_status=0
    fetch_documentation_text_resource \
        "$documentation_text_url" "$source_text_path" "$documentation_source_name" \
        || documentation_fetch_status=$?
    if [ "$documentation_fetch_status" -eq 0 ]; then
        validate_documentation_text_resource \
            "$source_text_path" "$documentation_source_name" "$minimum_documentation_bytes" "$@" \
            || documentation_fetch_status=$?
    fi
    if [ "$documentation_fetch_status" -eq 0 ] \
        && ! python3 "$SCRIPT_DIR/documentation_seed.py" \
            --wrap-plain-text-html \
            --input "$source_text_path" \
            --output "$target_directory/index.html" \
            --title "$documentation_title"; then
        log "${RED}✗ Could not encode $documentation_source_name for HTML ingestion${NC}"
        documentation_fetch_status=1
    fi
    rm -f "$source_text_path"
    if [ "$documentation_fetch_status" -ne 0 ]; then
        return "$documentation_fetch_status"
    fi
    validate_fetch_result \
        0 "$target_directory" "$documentation_source_name" "$minimum_html_files" "$partial_mirror_allowed"
}

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

# Requires the single parser that verifies Java 25 specification PDFs are readable.
require_java25_specification_pdf_parser() {
    if ! command -v mutool >/dev/null 2>&1; then
        log "${RED}✗ Java 25 specification validation requires mutool. Install with: brew install mupdf (macOS), apt install mupdf-tools (Ubuntu), or dnf install mupdf (Fedora)${NC}"
        return 1
    fi
}

# Validates the binary, parseability, and source identity of one Java 25 specification PDF.
validate_java25_specification_pdf() {
    local specification_path="$1"
    local specification_name="$2"
    local required_title_fragment="$3"
    local required_parsed_title="$4"
    if [ ! -s "$specification_path" ]; then
        log "${RED}✗ $specification_name PDF is missing or empty: $specification_path${NC}"
        return 1
    fi

    local pdf_magic
    if ! pdf_magic="$(head -c 5 "$specification_path")" \
        || [ "$pdf_magic" != "%PDF-" ]; then
        log "${RED}✗ $specification_name did not return a PDF header${NC}"
        return 1
    fi

    local detected_mime_type
    if ! detected_mime_type="$(file --brief --mime-type "$specification_path")" \
        || [ "$detected_mime_type" != "application/pdf" ]; then
        log "${RED}✗ $specification_name has unexpected MIME type: ${detected_mime_type:-unknown}${NC}"
        return 1
    fi
    if ! grep -aFq -- "$required_title_fragment" "$specification_path"; then
        log "${RED}✗ $specification_name does not contain its required specification title${NC}"
        return 1
    fi
    if ! grep -aFq -- "%%EOF" "$specification_path"; then
        log "${RED}✗ $specification_name is missing its PDF EOF marker${NC}"
        return 1
    fi

    if ! require_java25_specification_pdf_parser; then
        return 1
    fi

    local parsed_first_page
    if ! parsed_first_page="$(mutool draw -q -F txt -o - "$specification_path" 1)"; then
        log "${RED}✗ $specification_name cannot be parsed by mutool${NC}"
        return 1
    fi
    if ! grep -Fxq -- "Java SE 25 Edition" <<< "$parsed_first_page"; then
        log "${RED}✗ $specification_name is not the exact Java SE 25 Edition${NC}"
        return 1
    fi

    local normalized_first_page
    normalized_first_page="$(tr '\n' ' ' <<< "$parsed_first_page")"
    if [[ "$normalized_first_page" != *"$required_parsed_title"* ]]; then
        log "${RED}✗ $specification_name does not match its parsed specification title${NC}"
        return 1
    fi
    if ! mutool draw -q -F txt -o /dev/null "$specification_path"; then
        log "${RED}✗ $specification_name cannot be fully parsed by mutool${NC}"
        return 1
    fi
}

# Fetches one Java 25 specification into the staged Java API mirror.
fetch_java25_specification_pdf() {
    local target_directory="$1"
    local specification_name="$2"
    local specification_url="$3"
    local relative_specification_path="$4"
    local required_title_fragment="$5"
    local required_parsed_title="$6"
    local specification_path="$target_directory/$relative_specification_path"
    if ! mkdir -p "$(dirname "$specification_path")"; then
        log "${RED}✗ Could not create the Java 25 specification directory for $specification_name${NC}"
        return 1
    fi

    local temporary_specification_path
    if ! temporary_specification_path="$(mktemp "$specification_path.XXXXXX")"; then
        log "${RED}✗ Could not create a temporary PDF for $specification_name${NC}"
        return 1
    fi
    if ! wget2 \
        --output-document="$temporary_specification_path" \
        --max-redirect=0 \
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}" \
        --user-agent="java-chat-doc-fetcher/1.0" \
        "$specification_url" 2>&1 | tee -a "$LOG_FILE"; then
        rm -f "$temporary_specification_path"
        log "${RED}✗ Failed to fetch $specification_name from its canonical URL${NC}"
        return 1
    fi
    if ! validate_java25_specification_pdf \
        "$temporary_specification_path" "$specification_name" "$required_title_fragment" "$required_parsed_title"; then
        rm -f "$temporary_specification_path"
        return 1
    fi
    if ! mv "$temporary_specification_path" "$specification_path"; then
        rm -f "$temporary_specification_path"
        log "${RED}✗ Could not install the validated PDF for $specification_name${NC}"
        return 1
    fi
    validate_java25_specification_pdf \
        "$specification_path" "$specification_name" "$required_title_fragment" "$required_parsed_title"
}

# Keeps the two language-specification PDFs in the same atomic Java 25 source publication.
fetch_java25_specification_pdfs() {
    local target_directory="$1"
    local source_name="$2"
    if ! require_java25_specification_pdf_parser; then
        return 1
    fi
    fetch_java25_specification_pdf \
        "$target_directory" \
        "$source_name JLS 25" \
        "https://docs.oracle.com/javase/specs/jls/se25/jls25.pdf" \
        "docs.oracle.com/javase/specs/jls/se25/jls25.pdf" \
        "Language Specification" \
        "The Java® Language Specification" \
        || return 1
    fetch_java25_specification_pdf \
        "$target_directory" \
        "$source_name JVMS 25" \
        "https://docs.oracle.com/javase/specs/jvms/se25/jvms25.pdf" \
        "docs.oracle.com/javase/specs/jvms/se25/jvms25.pdf" \
        "Virtual Machine Specification" \
        "The Java® Virtual Machine Specification"
}

# Revalidates the published Java 25 specifications before an incremental mirror is reused.
validate_java25_specification_pdfs() {
    local target_directory="$1"
    local source_name="$2"
    if ! require_java25_specification_pdf_parser; then
        return 1
    fi
    validate_java25_specification_pdf \
        "$target_directory/docs.oracle.com/javase/specs/jls/se25/jls25.pdf" \
        "$source_name JLS 25" \
        "Language Specification" \
        "The Java® Language Specification" \
        && validate_java25_specification_pdf \
            "$target_directory/docs.oracle.com/javase/specs/jvms/se25/jvms25.pdf" \
            "$source_name JVMS 25" \
            "Virtual Machine Specification" \
            "The Java® Virtual Machine Specification"
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
    wget2 \
        --output-document="$target_page" \
        --max-redirect=0 \
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}" \
        --user-agent="$DOCUMENTATION_SINGLE_PAGE_BROWSER_USER_AGENT" \
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

# Fetches an API reference using its explicit Javadoc seed.
fetch_javadoc_seed() {
    local target_dir="$1"
    local name="$2"
    local cut_dirs="$3"
    local minimum_html_files="$4"
    local reject_regex="${5:-}"
    local partial_mirror_allowed="$6"
    local remote_base_url="$7"
    local seed_file="$target_dir/.javadoc-seed.txt"
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
    wget2 "${wget_seed_args[@]}" 2>&1 | tee -a "$LOG_FILE"
    wget_exit_code="${PIPESTATUS[0]}"
    cd - > /dev/null
    local fetched_html_count
    fetched_html_count="$(count_html_files "$target_dir")"
    if [ "$wget_exit_code" -eq 0 ] \
        && [ "$fetched_html_count" -gt 0 ] \
        && { [ "$minimum_html_files" -le 0 ] || [ "$fetched_html_count" -ge "$minimum_html_files" ]; } \
        && ! verify_javadoc_seed_mirror "$remote_base_url" "$target_dir" "$name" "$cut_dirs"; then
        return 1
    fi
    validate_fetch_result \
        "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"
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
    wget2 "${wget_args[@]}" "$url" 2>&1 | tee -a "$LOG_FILE"
    wget_exit_code="${PIPESTATUS[0]}"
    cd - > /dev/null
    local validation_status
    if validate_fetch_result \
        "$wget_exit_code" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"; then
        validation_status=0
    else
        validation_status="$?"
    fi
    return "$validation_status"
}

# Rejects publication when structured discovery changes during a fetch attempt.
validate_stable_documentation_seed() {
    local current_seed_file=""
    local target_directory=""
    local seed_document_type=""
    local seed_discovery_url=""
    local seed_source_prefix=""
    local canonical_prefix=""
    local seed_reject_regex=""
    local cut_directories=""
    local sitemap_index_url=""
    local validate_cloudflare_aliases="false"
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --current-seed-file) current_seed_file="$2"; shift 2 ;;
            --target-directory) target_directory="$2"; shift 2 ;;
            --seed-document-type) seed_document_type="$2"; shift 2 ;;
            --seed-discovery-url) seed_discovery_url="$2"; shift 2 ;;
            --seed-source-prefix) seed_source_prefix="$2"; shift 2 ;;
            --canonical-prefix) canonical_prefix="$2"; shift 2 ;;
            --seed-reject-regex) seed_reject_regex="$2"; shift 2 ;;
            --cut-directories) cut_directories="$2"; shift 2 ;;
            --sitemap-index-url) sitemap_index_url="$2"; shift 2 ;;
            --validate-cloudflare-seed-aliases) validate_cloudflare_aliases="true"; shift ;;
            *) echo "Unknown stable documentation seed option: $1" >&2; return 1 ;;
        esac
    done
    local current_discovery_file
    local current_generated_seed_file
    current_discovery_file="$(mktemp "$target_directory/.documentation-current-discovery.XXXXXX")"
    current_generated_seed_file="$(mktemp "$target_directory/.documentation-current-seed.XXXXXX")"
    local -a current_projection_arguments=(
        --document-type "$seed_document_type"
        --input "$current_discovery_file"
        --discovery-url "$seed_discovery_url"
        --source-prefix "$seed_source_prefix"
        --canonical-prefix "$canonical_prefix"
        --reject-regex "$seed_reject_regex"
        --output "$current_generated_seed_file"
        --mirror-path-output /dev/null
        --cut-directories "$cut_directories"
    )
    local stable_seed_status=0
    if { [ -n "$sitemap_index_url" ] \
            && ! validate_documentation_sitemap_index \
                "$sitemap_index_url" "$seed_discovery_url" "$target_directory" "Current documentation"; } \
        || ! wget2 \
        --quiet \
        --output-document="$current_discovery_file" \
        --max-redirect=0 \
            "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}" \
            "$seed_discovery_url" \
        || { [ "$validate_cloudflare_aliases" = "true" ] \
            && ! validate_cloudflare_seed_aliases \
                "$current_discovery_file" \
                "$target_directory" \
                "$seed_reject_regex" \
                "$canonical_prefix"; } \
        || ! python3 "$SCRIPT_DIR/documentation_seed.py" "${current_projection_arguments[@]}" \
        || ! cmp --silent "$current_seed_file" "$current_generated_seed_file"; then
        log "${RED}✗ Structured documentation discovery changed during fetch${NC}"
        stable_seed_status=1
    fi
    rm -f "$current_discovery_file" "$current_generated_seed_file"
    return "$stable_seed_status"
}

validate_cloudflare_seed_aliases() {
    local discovery_file="$1"
    local target_directory="$2"
    local seed_reject_regex="$3"
    local source_prefix="$4"
    local alias_seed_file
    alias_seed_file="$(mktemp "$target_directory/.cloudflare-alias-seed.XXXXXX")"
    if ! python3 "$SCRIPT_DIR/documentation_seed.py" \
        --document-type xml-sitemap \
        --input "$discovery_file" \
        --discovery-url "${source_prefix}sitemap-0.xml" \
        --source-prefix "$source_prefix" \
        --canonical-prefix "$source_prefix" \
        --output "$alias_seed_file" \
        --mirror-path-output /dev/null \
        --cut-directories 0; then
        rm -f "$alias_seed_file"
        return 1
    fi
    local alias_validation_status=0
    local alias_url
    while IFS= read -r alias_url || [ -n "$alias_url" ]; do
        local alias_regex_match_status=0
        python3 "$SCRIPT_DIR/documentation_seed.py" \
            --matches-regex \
            --regex "$seed_reject_regex" \
            --candidate "$alias_url" \
            || alias_regex_match_status=$?
        case "$alias_regex_match_status" in
            0) ;;
            1) continue ;;
            *)
                log "${RED}✗ Cloudflare seed alias regex validation failed${NC}"
                rm -f "$alias_seed_file"
                return "$alias_regex_match_status"
                ;;
        esac
        local alias_page_file
        alias_page_file="$(mktemp "$target_directory/.cloudflare-alias-page.XXXXXX")"
        local alias_response_metadata
        alias_response_metadata="$(curl \
            --silent --show-error --max-redirs 0 --proto '=https' \
            --retry 5 --retry-all-errors \
            --connect-timeout 30 --max-time 120 \
            --output "$alias_page_file" \
            --write-out '%{http_code}\n%{redirect_url}' "$alias_url")"
        local alias_status="${alias_response_metadata%%$'\n'*}"
        if [ "$alias_status" = "301" ] || [ "$alias_status" = "308" ]; then
            local redirect_target="${alias_response_metadata##*$'\n'}"
            if ! grep -Fxq -- "$redirect_target" "$alias_seed_file"; then
                log "${RED}✗ Cloudflare redirect alias lost its seeded canonical target: $alias_url${NC}"
                alias_validation_status=1
            fi
        elif [ "$alias_status" = "200" ] \
            && [[ "$alias_url" == */ai/models/* ]]; then
            local model_slug="${alias_url%/}"
            model_slug="${model_slug##*/}"
            local model_target_url="${source_prefix}workers-ai/models/$model_slug/"
            local model_target_path
            model_target_path="$(mktemp "$target_directory/.cloudflare-model-target.XXXXXX")"
            if ! grep -Fxq -- "$model_target_url" "$alias_seed_file" \
                || ! curl \
                    --fail --silent --show-error --max-redirs 0 --proto '=https' \
                    --retry 5 --retry-all-errors \
                    --connect-timeout 30 --max-time 120 \
                    --output "$model_target_path" "$model_target_url"; then
                log "${RED}✗ Cloudflare model alias lost its seeded Workers AI target: $alias_url${NC}"
                alias_validation_status=1
            fi
            local alias_article_text_file
            local target_article_text_file
            alias_article_text_file="$(mktemp "$target_directory/.cloudflare-alias-text.XXXXXX")"
            target_article_text_file="$(mktemp "$target_directory/.cloudflare-target-text.XXXXXX")"
            if ! python3 "$SCRIPT_DIR/documentation_seed.py" \
                    --extract-article-text --input "$alias_page_file" > "$alias_article_text_file" \
                || ! python3 "$SCRIPT_DIR/documentation_seed.py" \
                    --extract-article-text --input "$model_target_path" > "$target_article_text_file" \
                || ! cmp --silent "$alias_article_text_file" "$target_article_text_file"; then
                log "${RED}✗ Cloudflare model alias diverged from its Workers AI target: $alias_url${NC}"
                alias_validation_status=1
            fi
            rm -f "$alias_article_text_file" "$target_article_text_file" "$model_target_path"
        else
            log "${RED}✗ Cloudflare seed alias no longer matches a verified duplicate: $alias_url${NC}"
            alias_validation_status=1
        fi
        rm -f "$alias_page_file"
        if [ "$alias_validation_status" -ne 0 ]; then
            break
        fi
    done < "$alias_seed_file"
    rm -f "$alias_seed_file"
    return "$alias_validation_status"
}

# Fetches an explicit seed discovered from structured XML or HTML.
fetch_discovered_documentation_seed() {
    local canonical_prefix=""
    local target_dir=""
    local name=""
    local cut_directories=""
    local minimum_html_files=""
    local reject_regex=""
    local partial_mirror_allowed="false"
    local seed_document_type=""
    local seed_discovery_url=""
    local seed_source_prefix=""
    local seed_reject_regex=""
    local request_delay_seconds="0"
    local seed_additional_discovery_url=""
    local require_stable_seed_discovery="false"
    local validate_cloudflare_aliases="false"
    local canonicalize_extensionless_directory_urls="false"
    local sitemap_index_url=""
    local -a supplemental_seed_urls=()
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --canonical-prefix) canonical_prefix="$2"; shift 2 ;;
            --target-dir) target_dir="$2"; shift 2 ;;
            --name) name="$2"; shift 2 ;;
            --cut-directories) cut_directories="$2"; shift 2 ;;
            --minimum-html-files) minimum_html_files="$2"; shift 2 ;;
            --reject-regex) reject_regex="$2"; shift 2 ;;
            --partial-mirror-allowed) partial_mirror_allowed="$2"; shift 2 ;;
            --seed-document-type) seed_document_type="$2"; shift 2 ;;
            --seed-discovery-url) seed_discovery_url="$2"; shift 2 ;;
            --seed-source-prefix) seed_source_prefix="$2"; shift 2 ;;
            --seed-reject-regex) seed_reject_regex="$2"; shift 2 ;;
            --request-delay-seconds) request_delay_seconds="$2"; shift 2 ;;
            --seed-additional-discovery-url) seed_additional_discovery_url="$2"; shift 2 ;;
            --require-stable-seed-discovery) require_stable_seed_discovery="true"; shift ;;
            --validate-cloudflare-seed-aliases) validate_cloudflare_aliases="true"; shift ;;
            --canonicalize-extensionless-directory-urls) canonicalize_extensionless_directory_urls="true"; shift ;;
            --sitemap-index-url) sitemap_index_url="$2"; shift 2 ;;
            --supplemental-seed-url) supplemental_seed_urls+=("$2"); shift 2 ;;
            *) echo "Unknown discovered documentation option: $1" >&2; return 1 ;;
        esac
    done
    if [ -z "$canonical_prefix" ] || [ -z "$target_dir" ] || [ -z "$name" ] \
        || [ -z "$cut_directories" ] || [ -z "$minimum_html_files" ] \
        || [ -z "$seed_document_type" ] || [ -z "$seed_discovery_url" ] \
        || [ -z "$seed_source_prefix" ]; then
        echo "Discovered documentation fetch requires canonical source, target, identity, and discovery options" >&2
        return 1
    fi
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

    if ! wget2 "${wget_discovery_arguments[@]}" "$seed_discovery_url"; then
        rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file"
        cd - > /dev/null
        log "${RED}✗ Failed to fetch structured discovery document for $name${NC}"
        return 1
    fi
    if [ "$validate_cloudflare_aliases" = "true" ] \
        && ! validate_cloudflare_seed_aliases \
            "$discovery_file" "$target_dir" "$seed_reject_regex" "$canonical_prefix"; then
        rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file"
        cd - > /dev/null
        return 1
    fi
    if [ -n "$sitemap_index_url" ] \
        && ! validate_documentation_sitemap_index \
            "$sitemap_index_url" "$seed_discovery_url" "$target_dir" "$name"; then
        rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file"
        cd - > /dev/null
        return 1
    fi
    local -a seed_projection_arguments=(
        --document-type "$seed_document_type" \
        --input "$discovery_file" \
        --discovery-url "$seed_discovery_url" \
        --source-prefix "$seed_source_prefix" \
        --canonical-prefix "$canonical_prefix" \
        --reject-regex "$seed_reject_regex" \
        --output "$generated_seed_file" \
        --mirror-path-output "$mirror_paths_file" \
        --cut-directories "$cut_directories"
    )
    if [ "$canonicalize_extensionless_directory_urls" = "true" ]; then
        seed_projection_arguments+=(--canonicalize-extensionless-directory-urls)
    fi
    if ! python3 "$SCRIPT_DIR/documentation_seed.py" "${seed_projection_arguments[@]}"; then
        rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file"
        cd - > /dev/null
        log "${RED}✗ Structured discovery failed for $name${NC}"
        return 1
    fi
    if [ "${#supplemental_seed_urls[@]}" -gt 0 ]; then
        if ! printf '%s\n' "${supplemental_seed_urls[@]}" >> "$generated_seed_file" \
            || ! sort -u -o "$generated_seed_file" "$generated_seed_file"; then
            rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file"
            cd - > /dev/null
            log "${RED}✗ Supplemental documentation seeds could not be recorded for $name${NC}"
            return 1
        fi
        if ! write_documentation_seed_mirror_paths \
            "$canonical_prefix" "$generated_seed_file" "$cut_directories" "$mirror_paths_file" "$name"; then
            rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file"
            cd - > /dev/null
            log "${RED}✗ Supplemental documentation seeds are invalid for $name${NC}"
            return 1
        fi
    fi
    if [ -n "$seed_additional_discovery_url" ]; then
        local additional_discovery_file
        local additional_seed_file
        local additional_mirror_paths_file
        if ! additional_discovery_file="$(mktemp "$target_dir/.documentation-additional-discovery.XXXXXX")" \
            || ! additional_seed_file="$(mktemp "$target_dir/.documentation-additional-seed.XXXXXX")" \
            || ! additional_mirror_paths_file="$(mktemp "$target_dir/.documentation-additional-paths.XXXXXX")"; then
            rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file" \
                "${additional_discovery_file:-}" "${additional_seed_file:-}" "${additional_mirror_paths_file:-}"
            cd - > /dev/null
            log "${RED}✗ Could not create additional discovery files for $name${NC}"
            return 1
        fi
        local additional_wget_discovery_arguments=(
            --quiet
            --output-document="$additional_discovery_file"
            --max-redirect=0
            "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}"
        )
        if ! wget2 "${additional_wget_discovery_arguments[@]}" "$seed_additional_discovery_url" \
            || ! python3 "$SCRIPT_DIR/documentation_seed.py" \
                --document-type "$seed_document_type" \
                --input "$additional_discovery_file" \
                --discovery-url "$seed_additional_discovery_url" \
                --source-prefix "$seed_source_prefix" \
                --canonical-prefix "$canonical_prefix" \
                --reject-regex "$seed_reject_regex" \
                --output "$additional_seed_file" \
                --mirror-path-output "$additional_mirror_paths_file" \
                --cut-directories "$cut_directories"; then
            rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file" \
                "$additional_discovery_file" "$additional_seed_file" "$additional_mirror_paths_file"
            cd - > /dev/null
            log "${RED}✗ Additional structured discovery failed for $name${NC}"
            return 1
        fi
        cat "$additional_seed_file" >> "$generated_seed_file"
        LC_ALL=C sort -u -o "$generated_seed_file" "$generated_seed_file"
        if ! write_documentation_seed_mirror_paths \
            "$canonical_prefix" "$generated_seed_file" "$cut_directories" "$mirror_paths_file" "$name"; then
            rm -f "$discovery_file" "$generated_seed_file" "$mirror_paths_file" \
                "$additional_discovery_file" "$additional_seed_file" "$additional_mirror_paths_file"
            cd - > /dev/null
            log "${RED}✗ Combined structured discovery failed for $name${NC}"
            return 1
        fi
        rm -f "$additional_discovery_file" "$additional_seed_file" "$additional_mirror_paths_file"
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
    local -a stable_seed_arguments=(
        --current-seed-file "$seed_file"
        --target-directory "$target_dir"
        --seed-document-type "$seed_document_type"
        --seed-discovery-url "$seed_discovery_url"
        --seed-source-prefix "$seed_source_prefix"
        --canonical-prefix "$canonical_prefix"
        --seed-reject-regex "$seed_reject_regex"
        --cut-directories "$cut_directories"
    )
    if [ -n "$sitemap_index_url" ]; then
        stable_seed_arguments+=(--sitemap-index-url "$sitemap_index_url")
    fi
    if [ "$validate_cloudflare_aliases" = "true" ]; then
        stable_seed_arguments+=(--validate-cloudflare-seed-aliases)
    fi
    if ! reconcile_seeded_html_mirror \
        "$target_dir" "$name" "$mirror_paths_file" "unseeded-documentation"; then
        rm -f "$mirror_paths_file"
        cd - > /dev/null
        return 1
    fi
    if [ "${FORCE_REFRESH:-false}" != "true" ] \
        && verify_seeded_html_mirror "$target_dir" "$name" "$mirror_paths_file"; then
        if [ "$require_stable_seed_discovery" = "true" ] \
            && ! validate_stable_documentation_seed "${stable_seed_arguments[@]}"; then
            rm -f "$mirror_paths_file"
            cd - > /dev/null
            return 1
        fi
        rm -f "$mirror_paths_file"
        cd - > /dev/null
        if validate_fetch_result 0 "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"; then
            DOCUMENTATION_SOURCE_ALREADY_COMPLETE="true"
            return 0
        fi
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
    if [ "$request_delay_seconds" -gt 0 ]; then
        wget_seed_arguments+=(--wait="$request_delay_seconds")
    fi
    if [ -n "$reject_regex" ]; then
        wget_seed_arguments+=(--reject-regex="$reject_regex")
    fi
    local seed_fetch_status
    wget2 "${wget_seed_arguments[@]}" 2>&1 | tee -a "$LOG_FILE"
    seed_fetch_status="${PIPESTATUS[0]}"
    cd - > /dev/null
    if [ "$seed_fetch_status" -ne 0 ]; then
        rm -f "$mirror_paths_file"
        log "${RED}✗ Failed to fetch $name seed URLs (exit code: $seed_fetch_status)${NC}"
        return 1
    fi
    if [ "$require_stable_seed_discovery" = "true" ] \
        && ! validate_stable_documentation_seed "${stable_seed_arguments[@]}"; then
        rm -f "$mirror_paths_file"
        return 1
    fi
    if ! verify_seeded_html_mirror "$target_dir" "$name" "$mirror_paths_file"; then
        rm -f "$mirror_paths_file"
        return 1
    fi
    rm -f "$mirror_paths_file"
    validate_fetch_result \
        "$seed_fetch_status" "$target_dir" "$name" "$minimum_html_files" "$partial_mirror_allowed"
}
