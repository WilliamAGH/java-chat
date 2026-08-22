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
    --retry-on-http-error=429,500,502,503,504
)
if [[ "${DOCUMENTATION_SINGLE_PAGE_BROWSER_USER_AGENT:-}" != "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" ]]; then
    DOCUMENTATION_SINGLE_PAGE_BROWSER_USER_AGENT="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
fi
readonly DOCUMENTATION_SINGLE_PAGE_BROWSER_USER_AGENT

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
        log "${RED}✗ Java 25 specification validation requires mutool. Install with: brew install mupdf (macOS) or apt install mupdf-tools (Ubuntu)${NC}"
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
                --discovery-url "$seed_discovery_url" \
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
    if ! reconcile_seeded_html_mirror \
        "$target_dir" "$name" "$mirror_paths_file" "unseeded-documentation"; then
        rm -f "$mirror_paths_file"
        cd - > /dev/null
        return 1
    fi
    if [ "${FORCE_REFRESH:-false}" != "true" ] \
        && verify_seeded_html_mirror "$target_dir" "$name" "$mirror_paths_file"; then
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
    local wget_exit_code
    wget2 "${wget_seed_arguments[@]}" 2>&1 | tee -a "$LOG_FILE"
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
