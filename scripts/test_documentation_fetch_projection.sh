#!/bin/bash

# Verifies that non-Java documentation sources retain their manifest-owned fetch projections.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_SCRIPT_DIRECTORY/.." && pwd)"
FETCH_SCRIPT="$PROJECT_ROOT/scripts/fetch_all_docs.sh"
DOCUMENTATION_SOURCES_MANIFEST="$PROJECT_ROOT/src/main/resources/documentation-sources.manifest"
DOCUMENTATION_SEED_SCRIPT="$PROJECT_ROOT/scripts/documentation_seed.py"
DOCUMENTATION_SEED_FIXTURES="$PROJECT_ROOT/scripts/testdata/documentation_seed"
TEST_WORK_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_WORK_ROOT"' EXIT

fail_documentation_fetch_projection_test() {
    local failure_message="$1"
    printf 'FAIL: %s\n' "$failure_message" >&2
    exit 1
}

set --
# shellcheck source=fetch_all_docs.sh
source "$FETCH_SCRIPT"
DOCS_ROOT="$TEST_WORK_ROOT/docs"
mkdir -p "$DOCS_ROOT"

log() {
    :
}

fetch_execution_arguments=()
fetch_docs() {
    fetch_execution_arguments=("$@")
}

if [ -e "$PROJECT_ROOT/src/main/resources/documentation-source-fields.manifest" ]; then
    fail_documentation_fetch_projection_test "redundant documentation source field catalog must remain removed"
fi

canonical_seed_document_type_catalog="$DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG"
duplicate_seed_document_type_catalog="$TEST_WORK_ROOT/duplicate-seed-document-types.manifest"
malformed_seed_document_type_catalog="$TEST_WORK_ROOT/malformed-seed-document-types.manifest"
missing_reader_seed_document_type_catalog="$TEST_WORK_ROOT/missing-reader-seed-document-types.manifest"
printf 'html-links\nxml-sitemap\nhtml-links\n' > "$duplicate_seed_document_type_catalog"
printf 'html-links\nInvalid-Type\n' > "$malformed_seed_document_type_catalog"
printf 'html-links\nunsupported-discovery\n' > "$missing_reader_seed_document_type_catalog"
for invalid_seed_document_type_catalog in \
    "$duplicate_seed_document_type_catalog" \
    "$malformed_seed_document_type_catalog" \
    "$missing_reader_seed_document_type_catalog"; do
    DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG="$invalid_seed_document_type_catalog"
    if load_documentation_sources "$DOCUMENTATION_SOURCES_MANIFEST" > /dev/null 2>&1; then
        fail_documentation_fetch_projection_test \
            "shell manifest loading accepted an invalid canonical seed document type catalog"
    fi
done
DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG="$canonical_seed_document_type_catalog"

canonical_documentation_source_header="$(sed -n '1p' "$DOCUMENTATION_SOURCES_MANIFEST")"
overlapping_active_mirror_paths_manifest="$TEST_WORK_ROOT/overlapping-active-mirror-paths.manifest"
printf '%s\n' \
    "$canonical_documentation_source_header" \
    'https://docs.example.test/language/|https://docs.example.test/language/|language|Language|language|official|language-reference||1||false||||' \
    'https://docs.example.test/language/reference/|https://docs.example.test/language/reference/|language/reference|Language Reference|language-reference|official|language-reference||1||false||||' \
    > "$overlapping_active_mirror_paths_manifest"
if load_documentation_sources "$overlapping_active_mirror_paths_manifest" > /dev/null 2>&1; then
    fail_documentation_fetch_projection_test \
        "manifest loading accepted segment-boundary-overlapping active mirror paths"
fi

valid_sibling_mirror_paths_manifest="$TEST_WORK_ROOT/valid-sibling-mirror-paths.manifest"
printf '%s\n' \
    "$canonical_documentation_source_header" \
    'https://docs.example.test/language/|https://docs.example.test/language/|language|Language|language|official|language-reference||1||false||||' \
    'https://docs.example.test/language-reference/|https://docs.example.test/language-reference/|language-reference|Language Reference|language-reference|official|language-reference||1||false||||' \
    > "$valid_sibling_mirror_paths_manifest"
DOCUMENTATION_SOURCE_PROJECTIONS=()
if ! load_documentation_sources "$valid_sibling_mirror_paths_manifest"; then
    fail_documentation_fetch_projection_test \
        "manifest loading rejected segment-disjoint active mirror path siblings"
fi
if [ "${#DOCUMENTATION_SOURCE_PROJECTIONS[@]}" -ne 2 ]; then
    fail_documentation_fetch_projection_test \
        "valid active mirror path siblings did not preserve both canonical projections"
fi

DOCUMENTATION_SOURCE_PROJECTIONS=()
load_documentation_sources "$DOCUMENTATION_SOURCES_MANIFEST"
seed_fetch_document_type="$(sed -n '1p' "$DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG")"
DOC_SOURCES=()
append_manifest_documentation_fetch_sources

if [ "${#DOCUMENTATION_SOURCE_PROJECTIONS[@]}" -ne "${#DOC_SOURCES[@]}" ]; then
    fail_documentation_fetch_projection_test "fetch projections did not preserve the complete manifest order"
fi

for documentation_source_index in "${!DOCUMENTATION_SOURCE_PROJECTIONS[@]}"; do
    expected_manifest_projection="${DOCUMENTATION_SOURCE_PROJECTIONS[$documentation_source_index]}"
    documentation_fetch_projection="${DOC_SOURCES[$documentation_source_index]}"

    if [ "$documentation_fetch_projection" != "$expected_manifest_projection" ]; then
        fail_documentation_fetch_projection_test "fetch projection at index $documentation_source_index diverged from the canonical manifest row"
    fi

    fetch_execution_arguments=()
    fetch_manifest_documentation_source "$documentation_fetch_projection" > /dev/null
    if [ "${#fetch_execution_arguments[@]}" -ne 12 ]; then
        fail_documentation_fetch_projection_test "fetch execution at index $documentation_source_index must receive exactly twelve arguments"
    fi

    expected_fetch_projection="|$(documentation_source_manifest_field "$expected_manifest_projection" "fetchUrl")"
    expected_fetch_projection+="|$(documentation_source_manifest_field "$expected_manifest_projection" "relativeMirrorPath")"
    expected_fetch_projection+="|$(documentation_source_manifest_field "$expected_manifest_projection" "displayName")"
    expected_fetch_projection+="|$(documentation_fetch_cut_directories "$(documentation_source_manifest_field "$expected_manifest_projection" "fetchUrl")")"
    expected_fetch_projection+="|$(documentation_source_manifest_field "$expected_manifest_projection" "minimumHtmlFiles")"
    expected_fetch_projection+="|$(documentation_source_manifest_field "$expected_manifest_projection" "rejectRegex")"
    expected_fetch_projection+="|$(documentation_source_manifest_field "$expected_manifest_projection" "allowPartial")"
    expected_fetch_projection+="|$(documentation_source_manifest_field "$expected_manifest_projection" "seedDocumentType")"
    expected_fetch_projection+="|$(documentation_source_manifest_field "$expected_manifest_projection" "seedDiscoveryUrl")"
    expected_fetch_projection+="|$(documentation_source_manifest_field "$expected_manifest_projection" "seedSourcePrefix")"
    expected_fetch_projection+="|$(documentation_source_manifest_field "$expected_manifest_projection" "supersededRelativeMirrorPath")"
    actual_fetch_projection="$(IFS='|'; printf '%s' "${fetch_execution_arguments[*]}")"
    if [ "$actual_fetch_projection" != "$expected_fetch_projection" ]; then
        fail_documentation_fetch_projection_test "fetch execution at index $documentation_source_index diverged from canonical named fields"
    fi
done

assert_documentation_selector_rejected() {
    local documentation_selector="$1"
    DOC_SOURCES=()
    if append_manifest_documentation_fetch_sources "$documentation_selector" > /dev/null 2>&1; then
        fail_documentation_fetch_projection_test "selector must be rejected: '$documentation_selector'"
    fi
    return 0
}

assert_documentation_selector_rejected ""
assert_documentation_selector_rejected ",dev-java"
assert_documentation_selector_rejected "dev-java,"
assert_documentation_selector_rejected "dev-java,,kotlin"
assert_documentation_selector_rejected "dev-java,dev-java"
assert_documentation_selector_rejected "dev-java, kotlin"
assert_documentation_selector_rejected "unknown-doc-set"

DOC_SOURCES=("sentinel-projection")
if append_manifest_documentation_fetch_sources "dev-java,unknown-doc-set" > /dev/null 2>&1; then
    fail_documentation_fetch_projection_test "mixed valid and unknown selectors must be rejected"
fi
if [ "${#DOC_SOURCES[@]}" -ne 1 ] || [ "${DOC_SOURCES[0]}" != "sentinel-projection" ]; then
    fail_documentation_fetch_projection_test "rejected selectors must not retain partial fetch projections"
fi

seeded_doc_set_selector=""
seeded_doc_set_count=0
for documentation_source_projection in "${DOCUMENTATION_SOURCE_PROJECTIONS[@]}"; do
    seed_document_type="$(documentation_source_manifest_field "$documentation_source_projection" "seedDocumentType")"
    if [ -z "$seed_document_type" ]; then
        continue
    fi
    doc_set="$(documentation_source_manifest_field "$documentation_source_projection" "docSet")"
    if [ -n "$seeded_doc_set_selector" ]; then
        seeded_doc_set_selector+=","
    fi
    seeded_doc_set_selector+="$doc_set"
    seeded_doc_set_count=$((seeded_doc_set_count + 1))
done
if [ "$seeded_doc_set_count" -eq 0 ]; then
    fail_documentation_fetch_projection_test "canonical manifest must retain at least one structured seed source"
fi

DOC_SOURCES=()
append_manifest_documentation_fetch_sources "$seeded_doc_set_selector"

if [ "${#DOC_SOURCES[@]}" -ne "$seeded_doc_set_count" ]; then
    fail_documentation_fetch_projection_test "selected fetch must contain every manifest-owned structured seed row"
fi

selected_projection_index=0
for documentation_source_index in "${!DOCUMENTATION_SOURCE_PROJECTIONS[@]}"; do
    documentation_source_projection="${DOCUMENTATION_SOURCE_PROJECTIONS[$documentation_source_index]}"
    seed_document_type="$(documentation_source_manifest_field "$documentation_source_projection" "seedDocumentType")"
    if [ -z "$seed_document_type" ]; then
        continue
    fi
    if [ "${DOC_SOURCES[$selected_projection_index]}" != "$documentation_source_projection" ]; then
        fail_documentation_fetch_projection_test "selected fetch diverged from canonical manifest order"
    fi
    selected_projection_index=$((selected_projection_index + 1))
done

while IFS= read -r seed_document_type || [ -n "$seed_document_type" ]; do
    seed_output_file="$TEST_WORK_ROOT/$seed_document_type.seed"
    mirror_path_output_file="$TEST_WORK_ROOT/$seed_document_type.paths"
    python3 "$DOCUMENTATION_SEED_SCRIPT" \
        --document-type "$seed_document_type" \
        --input "$DOCUMENTATION_SEED_FIXTURES/$seed_document_type.input" \
        --discovery-url "https://docs.example.test/navigation" \
        --source-prefix "https://docs.example.test/reference/" \
        --canonical-prefix "https://docs.example.test/reference/" \
        --output "$seed_output_file" \
        --mirror-path-output "$mirror_path_output_file" \
        --cut-directories 1
    if ! diff -u "$DOCUMENTATION_SEED_FIXTURES/$seed_document_type.expected" "$seed_output_file"; then
        fail_documentation_fetch_projection_test "seed output diverged for catalog type $seed_document_type"
    fi
    if ! diff -u "$DOCUMENTATION_SEED_FIXTURES/$seed_document_type.paths.expected" "$mirror_path_output_file"; then
        fail_documentation_fetch_projection_test "seed mirror paths diverged from GNU Wget projection for catalog type $seed_document_type"
    fi
    while IFS= read -r mirror_path || [ -n "$mirror_path" ]; do
        if [ -z "$mirror_path" ] \
            || [[ "$mirror_path" == /* ]] \
            || { [[ "$mirror_path" != *.html ]] && [[ "$mirror_path" != *.htm ]]; }; then
            fail_documentation_fetch_projection_test "seed mirror path was unsafe for catalog type $seed_document_type"
        fi
    done < "$mirror_path_output_file"
done < "$DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG"

rejected_seed_output_file="$TEST_WORK_ROOT/rejected.seed"
rejected_seed_paths_file="$TEST_WORK_ROOT/rejected.paths"
python3 "$DOCUMENTATION_SEED_SCRIPT" \
    --document-type html-links \
    --input "$DOCUMENTATION_SEED_FIXTURES/html-links.input" \
    --discovery-url "https://docs.example.test/navigation" \
    --source-prefix "https://docs.example.test/reference/" \
    --canonical-prefix "https://docs.example.test/reference/" \
    --reject-regex '/archive[.]htm$' \
    --output "$rejected_seed_output_file" \
    --mirror-path-output "$rejected_seed_paths_file" \
    --cut-directories 1
if grep -Fq '/archive.htm' "$rejected_seed_output_file" \
    || grep -Fxq 'archive.htm' "$rejected_seed_paths_file"; then
    fail_documentation_fetch_projection_test "structured discovery retained a manifest-rejected URL"
fi

if python3 "$DOCUMENTATION_SEED_SCRIPT" \
    --document-type xml-sitemap \
    --input "$DOCUMENTATION_SEED_FIXTURES/sitemap-line-injection.xml" \
    --discovery-url "https://docs.example.test/sitemap.xml" \
    --source-prefix "https://docs.example.test/reference/" \
    --canonical-prefix "https://docs.example.test/reference/" \
    --output "$TEST_WORK_ROOT/injected-seed.txt" \
    --mirror-path-output "$TEST_WORK_ROOT/injected-paths.txt" \
    --cut-directories 1 > /dev/null 2>&1; then
    fail_documentation_fetch_projection_test "structured discovery must reject line-breaking seed injection"
fi

if python3 "$DOCUMENTATION_SEED_SCRIPT" \
    --document-type xml-sitemap \
    --input "$DOCUMENTATION_SEED_FIXTURES/sitemap-doctype-entity.xml" \
    --discovery-url "https://docs.example.test/sitemap.xml" \
    --source-prefix "https://docs.example.test/reference/" \
    --canonical-prefix "https://docs.example.test/reference/" \
    --output "$TEST_WORK_ROOT/doctype-entity-seed.txt" \
    --mirror-path-output "$TEST_WORK_ROOT/doctype-entity-paths.txt" \
    --cut-directories 1 > /dev/null 2>&1; then
    fail_documentation_fetch_projection_test "structured discovery must reject XML sitemap DOCTYPE entity expansion"
fi
if [ -e "$TEST_WORK_ROOT/doctype-entity-seed.txt" ]; then
    fail_documentation_fetch_projection_test "rejected XML sitemap DOCTYPE must not write a seed output"
fi

if python3 "$DOCUMENTATION_SEED_SCRIPT" \
    --document-type unsupported-discovery \
    --input "$DOCUMENTATION_SEED_FIXTURES/$seed_fetch_document_type.input" \
    --discovery-url "https://docs.example.test/sitemap.xml" \
    --source-prefix "https://docs.example.test/reference/" \
    --canonical-prefix "https://docs.example.test/reference/" \
    --output "$TEST_WORK_ROOT/unsupported-seed.txt" \
    --mirror-path-output "$TEST_WORK_ROOT/unsupported-paths.txt" \
    --cut-directories 1 > /dev/null 2>&1; then
    fail_documentation_fetch_projection_test "structured discovery must reject seed types outside the canonical catalog"
fi

assert_seed_manifest_url_rejected() {
    local discovery_url="$1"
    local source_prefix="$2"
    local canonical_prefix="$3"
    if python3 "$DOCUMENTATION_SEED_SCRIPT" \
        --document-type "$seed_fetch_document_type" \
        --input "$DOCUMENTATION_SEED_FIXTURES/$seed_fetch_document_type.input" \
        --discovery-url "$discovery_url" \
        --source-prefix "$source_prefix" \
        --canonical-prefix "$canonical_prefix" \
        --output "$TEST_WORK_ROOT/invalid-url-seed.txt" \
        --mirror-path-output "$TEST_WORK_ROOT/invalid-url-paths.txt" \
        --cut-directories 1 > /dev/null 2>&1; then
        fail_documentation_fetch_projection_test "structured discovery accepted invalid manifest URL input"
    fi
}

while IFS= read -r invalid_remote_url || [ -n "$invalid_remote_url" ]; do
    assert_seed_manifest_url_rejected \
        "$invalid_remote_url" \
        "https://docs.example.test/reference/" \
        "https://docs.example.test/reference/"
    assert_seed_manifest_url_rejected \
        "https://docs.example.test/navigation" \
        "$invalid_remote_url" \
        "https://docs.example.test/reference/"
    assert_seed_manifest_url_rejected \
        "https://docs.example.test/navigation" \
        "https://docs.example.test/reference/" \
        "$invalid_remote_url"
done < "$DOCUMENTATION_SEED_FIXTURES/invalid-remote-urls.txt"

for valid_remote_port in 1 65535; do
    python3 "$DOCUMENTATION_SEED_SCRIPT" \
        --document-type "$seed_fetch_document_type" \
        --input "$DOCUMENTATION_SEED_FIXTURES/$seed_fetch_document_type.input" \
        --discovery-url "https://docs.example.test:$valid_remote_port/navigation" \
        --source-prefix "https://docs.example.test:$valid_remote_port/reference/" \
        --canonical-prefix "https://docs.example.test:$valid_remote_port/reference/" \
        --output "$TEST_WORK_ROOT/valid-port-$valid_remote_port-seed.txt" \
        --mirror-path-output "$TEST_WORK_ROOT/valid-port-$valid_remote_port-paths.txt" \
        --cut-directories 1
done

generic_mirror_target_directory="$TEST_WORK_ROOT/generic-mirror"
generic_mirror_wget_arguments_file="$TEST_WORK_ROOT/generic-mirror-wget-arguments"
mkdir -p "$generic_mirror_target_directory"
LOG_FILE="$TEST_WORK_ROOT/documentation-fetch.log"
wget() {
    printf '%s\n' "$@" > "$generic_mirror_wget_arguments_file"
    printf '<html><body>Fetched</body></html>\n' > "$generic_mirror_target_directory/index.html"
    mkdir -p "$generic_mirror_target_directory/nested"
    printf '<html><body>Nested</body></html>\n' > "$generic_mirror_target_directory/nested/guide.htm"
    printf 'User-agent: *\n' > "$generic_mirror_target_directory/robots.txt"
    printf 'User-agent: *\n' > "$generic_mirror_target_directory/nested/robots.txt"
    printf 'body {}\n' > "$generic_mirror_target_directory/site.css"
    printf 'wget state\n' > "$generic_mirror_target_directory/.wget-state"
    return 0
}
if ! (cd "$generic_mirror_target_directory" \
    && fetch_docs_mirror \
        "https://docs.example.test/reference/" \
        "$generic_mirror_target_directory" \
        "Generic Reference" \
        1 \
        1 \
        "" \
        false); then
    fail_documentation_fetch_projection_test "generic mirror fetch policy failed"
fi
if grep -Fxq -- '--page-requisites' "$generic_mirror_wget_arguments_file"; then
    fail_documentation_fetch_projection_test "generic mirrors must not fetch non-HTML page requisites"
fi
if grep -Eq '^--accept=' "$generic_mirror_wget_arguments_file"; then
    fail_documentation_fetch_projection_test "generic mirrors must not restrict extensionless documentation URLs"
fi
if ! grep -Eq '^--reject=.*png' "$generic_mirror_wget_arguments_file"; then
    fail_documentation_fetch_projection_test "generic mirrors must reject binary asset extensions"
fi
if [ ! -f "$generic_mirror_target_directory/index.html" ] \
    || [ ! -f "$generic_mirror_target_directory/nested/guide.htm" ]; then
    fail_documentation_fetch_projection_test "generic mirror cleanup removed governed HTML files"
fi
if find "$generic_mirror_target_directory" -type f ! \( -name '*.html' -o -name '*.htm' \) \
    -print -quit | grep -q .; then
    fail_documentation_fetch_projection_test "generic mirror cleanup retained non-HTML fetch artifacts"
fi

assert_seed_network_policy() {
    local wget_arguments_file="$1"
    local seed_network_argument
    for seed_network_argument in "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}"; do
        if ! grep -Fxq -- "$seed_network_argument" "$wget_arguments_file"; then
            fail_documentation_fetch_projection_test "seed fetch omitted canonical network policy: $seed_network_argument"
        fi
    done
}

seed_discovery_target_directory="$TEST_WORK_ROOT/seed-discovery"
seed_discovery_wget_capture_prefix="$TEST_WORK_ROOT/seed-discovery-wget"
seed_discovery_wget_call_count=0
mkdir -p "$seed_discovery_target_directory"
printf '<html><body>Stale</body></html>\n' > "$seed_discovery_target_directory/stale.html"
wget() {
    seed_discovery_wget_call_count=$((seed_discovery_wget_call_count + 1))
    local wget_arguments_file="$seed_discovery_wget_capture_prefix-$seed_discovery_wget_call_count"
    printf '%s\n' "$@" > "$wget_arguments_file"
    if [ "$seed_discovery_wget_call_count" -eq 1 ]; then
        local wget_argument
        for wget_argument in "$@"; do
            case "$wget_argument" in
                --output-document=*)
                    cp "$DOCUMENTATION_SEED_FIXTURES/$seed_fetch_document_type.input" "${wget_argument#--output-document=}"
                    ;;
            esac
        done
    else
        local mirror_paths_file
        mirror_paths_file="$(find "$seed_discovery_target_directory" -maxdepth 1 -name '.documentation-seed-paths.*' -print -quit)"
        local mirror_path
        while IFS= read -r mirror_path || [ -n "$mirror_path" ]; do
            mkdir -p "$(dirname "$seed_discovery_target_directory/$mirror_path")"
            printf '<html><body>Seeded</body></html>\n' > "$seed_discovery_target_directory/$mirror_path"
        done < "$mirror_paths_file"
    fi
    return 0
}
if ! (cd "$seed_discovery_target_directory" \
    && fetch_discovered_documentation_seed \
        "https://docs.example.test/reference/" \
        "$seed_discovery_target_directory" \
        "Seed Discovery" \
        1 \
        1 \
        "" \
        false \
        "$seed_fetch_document_type" \
        "https://docs.example.test/sitemap.xml" \
        "https://docs.example.test/reference/"); then
    fail_documentation_fetch_projection_test "structured discovery fetch policy failed"
fi
if ! grep -Eq '^--output-document=' "$seed_discovery_wget_capture_prefix-1"; then
    fail_documentation_fetch_projection_test "structured discovery did not write a local discovery file"
fi
assert_seed_network_policy "$seed_discovery_wget_capture_prefix-1"
assert_seed_network_policy "$seed_discovery_wget_capture_prefix-2"
if ! grep -Fxq -- '--max-redirect=0' "$seed_discovery_wget_capture_prefix-1" \
    || ! grep -Fxq -- '--max-redirect=0' "$seed_discovery_wget_capture_prefix-2"; then
    fail_documentation_fetch_projection_test "structured seed discovery must reject redirects"
fi
if [ -e "$seed_discovery_target_directory/stale.html" ]; then
    fail_documentation_fetch_projection_test "structured seed reconciliation retained stale HTML"
fi

seed_http_error_target_directory="$TEST_WORK_ROOT/seed-http-error"
seed_http_error_call_count=0
mkdir -p "$seed_http_error_target_directory"
wget() {
    seed_http_error_call_count=$((seed_http_error_call_count + 1))
    if [ "$seed_http_error_call_count" -eq 1 ]; then
        local wget_argument
        for wget_argument in "$@"; do
            case "$wget_argument" in
                --output-document=*)
                    cp "$DOCUMENTATION_SEED_FIXTURES/$seed_fetch_document_type.input" "${wget_argument#--output-document=}"
                    ;;
            esac
        done
        return 0
    fi
    local mirror_paths_file
    mirror_paths_file="$(find "$seed_http_error_target_directory" -maxdepth 1 -name '.documentation-seed-paths.*' -print -quit)"
    local mirror_path
    while IFS= read -r mirror_path || [ -n "$mirror_path" ]; do
        mkdir -p "$(dirname "$seed_http_error_target_directory/$mirror_path")"
        printf '<html><body>HTTP error body</body></html>\n' > "$seed_http_error_target_directory/$mirror_path"
    done < "$mirror_paths_file"
    return 8
}
if (cd "$seed_http_error_target_directory" \
    && fetch_discovered_documentation_seed \
        "https://docs.example.test/reference/" \
        "$seed_http_error_target_directory" \
        "Seed HTTP Error" \
        1 \
        1 \
        "" \
        false \
        "$seed_fetch_document_type" \
        "https://docs.example.test/navigation" \
        "https://docs.example.test/reference/") > /dev/null 2>&1; then
    fail_documentation_fetch_projection_test "structured seed fetch accepted wget HTTP error exit status"
fi

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() { :; }
    atomic_seed_target_directory="$TEST_WORK_ROOT/atomic-documentation-seed"
    mkdir -p "$atomic_seed_target_directory"
    printf 'retained-seed\n' > "$atomic_seed_target_directory/.documentation-seed.txt"
    wget() {
        local wget_argument
        for wget_argument in "$@"; do
            case "$wget_argument" in
                --output-document=*)
                    cp "$DOCUMENTATION_SEED_FIXTURES/$seed_fetch_document_type.input" \
                        "${wget_argument#--output-document=}"
                    return 0
                    ;;
            esac
        done
        return 1
    }
    python3() {
        local generated_seed_output_path=""
        local generated_mirror_paths_output_path=""
        while [ "$#" -gt 0 ]; do
            case "$1" in
                --output)
                    generated_seed_output_path="$2"
                    shift 2
                    ;;
                --mirror-path-output)
                    generated_mirror_paths_output_path="$2"
                    shift 2
                    ;;
                *)
                    shift
                    ;;
            esac
        done
        printf 'incomplete-generated-seed\n' > "$generated_seed_output_path"
        printf 'incomplete-generated-path.html\n' > "$generated_mirror_paths_output_path"
        return 42
    }
    if (cd "$atomic_seed_target_directory" \
        && fetch_discovered_documentation_seed \
            "https://docs.example.test/reference/" \
            "$atomic_seed_target_directory" \
            "Atomic Seed Discovery" \
            1 \
            1 \
            "" \
            false \
            "$seed_fetch_document_type" \
            "https://docs.example.test/navigation" \
            "https://docs.example.test/reference/"); then
        exit 1
    fi
    if [ "$(< "$atomic_seed_target_directory/.documentation-seed.txt")" != "retained-seed" ]; then
        exit 1
    fi
    if find "$atomic_seed_target_directory" -maxdepth 1 \
        \( -name '.documentation-discovery.??????' \
            -o -name '.documentation-seed.??????' \
            -o -name '.documentation-seed-paths.??????' \) \
        -print -quit | grep -q .; then
        exit 1
    fi
); then
    fail_documentation_fetch_projection_test "failed paired seed generation replaced active non-Java seed state"
fi

if (parse_fetch_arguments --doc-sets=dev-java --doc-sets=kotlin) > /dev/null 2>&1; then
    fail_documentation_fetch_projection_test "repeated --doc-sets options must be rejected"
fi

for external_active_mirror_root in java oracle; do
    external_overlap_quarantine_capture="$TEST_WORK_ROOT/external-overlap-$external_active_mirror_root"
    if (
        set --
        # shellcheck source=fetch_all_docs.sh
        source "$FETCH_SCRIPT"
        log() { :; }
        DOCS_ROOT="$TEST_WORK_ROOT/external-overlap-docs"
        mkdir -p "$DOCS_ROOT/$external_active_mirror_root"
        load_java_api_documentation_sources "$JAVA_API_SOURCES_MANIFEST"
        create_documentation_fetch_staging_directory() {
            printf 'staging-invoked\n' > "$external_overlap_quarantine_capture"
            return 1
        }
        fetch_docs \
            "" \
            "https://docs.example.test/reference/" \
            "rolling-reference" \
            "Rolling Reference" \
            1 \
            1 \
            "" \
            false \
            "" \
            "" \
            "" \
            "$external_active_mirror_root"
    ) > /dev/null 2>&1; then
        fail_documentation_fetch_projection_test \
            "external active mirror overlap was accepted: $external_active_mirror_root"
    fi
    if [ -e "$external_overlap_quarantine_capture" ]; then
        fail_documentation_fetch_projection_test \
            "external active mirror overlap reached replacement staging: $external_active_mirror_root"
    fi
done

for documentation_list_option in --list-java-api-sources --list-documentation-sources; do
    set +e
    (
        set --
        # shellcheck source=fetch_all_docs.sh
        source "$FETCH_SCRIPT"
        load_builtin_documentation_fetch_projections() {
            LEGACY_DOCUMENTATION_FETCH_PROJECTIONS=(
                "|https://example.invalid/|kotlin/2.4.10|Lifecycle collision|0|1||false"
            )
            QUICK_DOCUMENTATION_FETCH_PROJECTIONS=()
            BUILTIN_DOCUMENTATION_FETCH_PROJECTIONS_LOADED="true"
        }
        run_documentation_fetch "$documentation_list_option"
    ) > /dev/null 2>&1
    documentation_list_status=$?
    set -e
    if [ "$documentation_list_status" -eq 0 ]; then
        fail_documentation_fetch_projection_test \
            "list mode bypassed external lifecycle-root validation: $documentation_list_option"
    fi
done

generic_failure_docs_root="$TEST_WORK_ROOT/generic-failure-docs"
mkdir -p "$generic_failure_docs_root/language/1.0"
for superseded_page_name in one two three four; do
    printf '<html><body>Superseded</body></html>\n' \
        > "$generic_failure_docs_root/language/1.0/$superseded_page_name.html"
done
if (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() { :; }
    DOCS_ROOT="$generic_failure_docs_root"
    LOG_FILE="$TEST_WORK_ROOT/generic-failed-fetch.log"
    CLEAN_INCOMPLETE="true"
    wget() {
        printf '<html><body>Incomplete replacement</body></html>\n' > replacement.html
        return 0
    }
    fetch_docs \
        "" \
        "https://docs.example.test/reference/" \
        "language" \
        "Rolling Language Reference" \
        1 \
        4 \
        "" \
        false \
        "" \
        "" \
        "" \
        "language/1.0"
); then
    fail_documentation_fetch_projection_test \
        "generic replacement counted superseded HTML toward staged completeness"
fi
if [ "$(count_html_files "$generic_failure_docs_root/language/1.0")" -ne 4 ] \
    || [ -e "$generic_failure_docs_root/language/replacement.html" ]; then
    fail_documentation_fetch_projection_test "failed generic replacement mutated the prior valid mirror"
fi

seeded_failure_docs_root="$TEST_WORK_ROOT/seeded-failure-docs"
mkdir -p "$seeded_failure_docs_root/language/1.0"
printf '<html><body>Superseded</body></html>\n' \
    > "$seeded_failure_docs_root/language/1.0/index.html"
printf 'retained-seed\n' > "$seeded_failure_docs_root/language/.documentation-seed.txt"
if (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() { :; }
    DOCS_ROOT="$seeded_failure_docs_root"
    LOG_FILE="$TEST_WORK_ROOT/seeded-failed-fetch.log"
    CLEAN_INCOMPLETE="true"
    seeded_failure_wget_call_count=0
    wget() {
        seeded_failure_wget_call_count=$((seeded_failure_wget_call_count + 1))
        if [ "$seeded_failure_wget_call_count" -eq 1 ]; then
            local wget_argument
            for wget_argument in "$@"; do
                case "$wget_argument" in
                    --output-document=*)
                        cp "$DOCUMENTATION_SEED_FIXTURES/html-links.input" \
                            "${wget_argument#--output-document=}"
                        ;;
                esac
            done
            return 0
        fi
        return 8
    }
    fetch_docs \
        "" \
        "https://docs.example.test/reference/" \
        "language" \
        "Rolling Seeded Language Reference" \
        1 \
        1 \
        "" \
        false \
        html-links \
        "https://docs.example.test/navigation" \
        "https://docs.example.test/reference/" \
        "language/1.0"
); then
    fail_documentation_fetch_projection_test "failed seeded replacement fetch was accepted"
fi
if [ ! -f "$seeded_failure_docs_root/language/1.0/index.html" ] \
    || [ "$(< "$seeded_failure_docs_root/language/.documentation-seed.txt")" != "retained-seed" ]; then
    fail_documentation_fetch_projection_test "failed seeded replacement mutated the prior mirror or seed"
fi

superseded_mirror_docs_root="$TEST_WORK_ROOT/superseded-mirror-docs"
mkdir -p "$superseded_mirror_docs_root/language/1.0"
printf '<html><body>Superseded</body></html>\n' \
    > "$superseded_mirror_docs_root/language/1.0/index.html"
if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() { :; }
    DOCS_ROOT="$superseded_mirror_docs_root"
    LOG_FILE="$TEST_WORK_ROOT/superseded-fetch.log"
    CLEAN_INCOMPLETE="true"
    wget() {
        printf '<html><body>Replacement one</body></html>\n' > replacement-one.html
        printf '<html><body>Replacement two</body></html>\n' > replacement-two.html
        return 0
    }
    fetch_docs \
        "" \
        "https://docs.example.test/reference/" \
        "language" \
        "Rolling Language Reference" \
        1 \
        2 \
        "" \
        false \
        "" \
        "" \
        "" \
        "language/1.0"
); then
    fail_documentation_fetch_projection_test "superseded mirror migration failed"
fi
if [ -e "$superseded_mirror_docs_root/language/1.0" ]; then
    fail_documentation_fetch_projection_test "superseded mirror migration retained the prior local root"
fi
if [ "$(count_html_files "$superseded_mirror_docs_root/language")" -ne 2 ]; then
    fail_documentation_fetch_projection_test "published generic replacement failed post-quarantine completeness"
fi
if ! find "$TEST_WORK_ROOT/.quarantine" -path '*/language/1.0/index.html' -type f -print -quit \
    | grep -q .; then
    fail_documentation_fetch_projection_test "superseded mirror migration did not preserve the prior root outside data/docs"
fi

if [ "$(documentation_fetch_cut_directories "https://example.invalid/")" != "0" ]; then
    fail_documentation_fetch_projection_test "root documentation URLs must retain zero cut directories"
fi

printf 'PASS: Documentation source fetch projections and selectors preserve canonical manifest ownership.\n'
