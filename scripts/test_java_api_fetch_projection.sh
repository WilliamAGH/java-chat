#!/bin/bash

# Verifies that canonical Java API manifest rows retain their eight-field fetch projection.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_SCRIPT_DIRECTORY/.." && pwd)"
FETCH_SCRIPT="$PROJECT_ROOT/scripts/fetch_all_docs.sh"
JAVA_API_SOURCES_MANIFEST="$PROJECT_ROOT/src/main/resources/java-api-documentation-sources.manifest"
TEST_WORK_ROOT="$(mktemp -d)"
TEST_DOCS_ROOT="$TEST_WORK_ROOT/docs"
mkdir -p "$TEST_DOCS_ROOT"
trap 'rm -rf "$TEST_WORK_ROOT"' EXIT

fail_java_api_fetch_projection_test() {
    local failure_message="$1"
    printf 'FAIL: %s\n' "$failure_message" >&2
    exit 1
}

set --
# shellcheck source=fetch_all_docs.sh
source "$FETCH_SCRIPT"

log() {
    :
}

fetch_execution_arguments=()
fetch_docs() {
    fetch_execution_arguments=("$@")
}

JAVA_API_SOURCE_PROJECTIONS=()
load_java_api_documentation_sources "$JAVA_API_SOURCES_MANIFEST"
DOC_SOURCES=()
append_java_api_fetch_sources

if [ "${#JAVA_API_SOURCE_PROJECTIONS[@]}" -ne "${#DOC_SOURCES[@]}" ]; then
    fail_java_api_fetch_projection_test "fetch projections did not preserve the complete manifest order"
fi

for java_api_source_index in "${!JAVA_API_SOURCE_PROJECTIONS[@]}"; do
    manifest_projection="${JAVA_API_SOURCE_PROJECTIONS[$java_api_source_index]}"
    documentation_fetch_projection="${DOC_SOURCES[$java_api_source_index]}"
    fetch_projection_delimiters="${documentation_fetch_projection//[^|]/}"
    if [ "${#fetch_projection_delimiters}" -ne 7 ]; then
        fail_java_api_fetch_projection_test "Java API fetch projection at index $java_api_source_index must contain exactly eight fields"
    fi

    if [ "$documentation_fetch_projection" != "$manifest_projection" ]; then
        fail_java_api_fetch_projection_test "Java API fetch projection at index $java_api_source_index diverged from the canonical manifest row"
    fi

    fetch_execution_arguments=()
    fetch_documentation_source "$documentation_fetch_projection" > /dev/null
    if [ "${#fetch_execution_arguments[@]}" -ne 8 ]; then
        fail_java_api_fetch_projection_test "Java API fetch execution at index $java_api_source_index must receive exactly eight fields"
    fi

    actual_fetch_projection="$(IFS='|'; printf '%s' "${fetch_execution_arguments[*]}")"
    if [ "$actual_fetch_projection" != "$manifest_projection" ]; then
        fail_java_api_fetch_projection_test "Java API fetch execution at index $java_api_source_index diverged from the appended projection"
    fi

    expected_allow_partial="${manifest_projection##*|}"
    manifest_projection_without_allow_partial="${manifest_projection%|*}"
    expected_reject_regex="${manifest_projection_without_allow_partial##*|}"
    if [ "${fetch_execution_arguments[6]}" != "$expected_reject_regex" ]; then
        fail_java_api_fetch_projection_test "Java API reject regex at index $java_api_source_index was not projected separately"
    fi
    if [ "${fetch_execution_arguments[7]}" != "$expected_allow_partial" ]; then
        fail_java_api_fetch_projection_test "Java API partial-mirror policy at index $java_api_source_index was not projected separately"
    fi
done

generic_fetch_projection="|https://example.invalid/reference.html|generic/reference|Generic Reference|1|1||false"
fetch_execution_arguments=()
fetch_documentation_source "$generic_fetch_projection" > /dev/null
if [ "${#fetch_execution_arguments[@]}" -ne 8 ] \
    || [ -n "${fetch_execution_arguments[0]}" ] \
    || [ -n "${fetch_execution_arguments[6]}" ]; then
    fail_java_api_fetch_projection_test "generic projection did not preserve empty Java-release and reject-regex fields"
fi

fetch_execution_arguments=()
if fetch_documentation_source "https://example.invalid/|generic|Generic|1|1||false" > /dev/null; then
    fail_java_api_fetch_projection_test "legacy seven-field projection was accepted"
fi
if [ "${#fetch_execution_arguments[@]}" -ne 0 ]; then
    fail_java_api_fetch_projection_test "invalid seven-field projection reached fetch execution"
fi

for invalid_relative_mirror_path in "../outside" "/absolute" 'backslash\path'; do
    fetch_execution_arguments=()
    if fetch_documentation_source "|https://example.invalid/|$invalid_relative_mirror_path|Invalid path|1|1||false" > /dev/null; then
        fail_java_api_fetch_projection_test "unsafe mirror path reached fetch execution: $invalid_relative_mirror_path"
    fi
    if [ "${#fetch_execution_arguments[@]}" -ne 0 ]; then
        fail_java_api_fetch_projection_test "unsafe mirror path invoked fetch execution: $invalid_relative_mirror_path"
    fi
done

set --
# shellcheck source=fetch_all_docs.sh
source "$FETCH_SCRIPT"

log() {
    :
}

count_html_files() {
    printf '0\n'
}

if validate_fetch_result 0 "$TEST_DOCS_ROOT/empty-partial" "Empty partial mirror" 10 true; then
    fail_java_api_fetch_projection_test "allowPartial=true accepted an empty mirror"
fi

count_html_files() {
    printf '5\n'
}

partial_validation_status=0
if validate_fetch_result 0 "$TEST_DOCS_ROOT/partial" "Partial mirror" 10 true; then
    fail_java_api_fetch_projection_test "allowPartial=true reported a below-minimum mirror as complete"
else
    partial_validation_status=$?
fi
if [ "$partial_validation_status" -ne "$DOCUMENTATION_FETCH_PARTIAL_STATUS" ]; then
    fail_java_api_fetch_projection_test "allowPartial=true did not return the partial fetch status"
fi
if validate_fetch_result 0 "$TEST_DOCS_ROOT/complete-required" "Complete mirror" 10 false; then
    fail_java_api_fetch_projection_test "allowPartial=false accepted a below-minimum mirror"
fi

count_html_files() {
    printf '10\n'
}

if ! validate_fetch_result 0 "$TEST_DOCS_ROOT/complete-partial-allowed" "Complete partial-allowed mirror" 10 true; then
    fail_java_api_fetch_projection_test "allowPartial=true rejected a complete mirror"
fi

count_html_files() {
    printf '5\n'
}

quarantine_capture_file="$TEST_DOCS_ROOT/quarantine.log"
quarantine_incomplete_dir() {
    printf 'quarantined\n' >> "$quarantine_capture_file"
}
generic_dispatch_capture_file="$TEST_DOCS_ROOT/generic-dispatch"
fetch_docs_mirror() {
    printf '%s|%s|%s\n' "$1" "$2" "$7" > "$generic_dispatch_capture_file"
    return 0
}

DOCS_ROOT="$TEST_DOCS_ROOT"
if ! (fetch_docs "" "https://example.com/api/" "partial" "Partial mirror" 1 10 "" true); then
    fail_java_api_fetch_projection_test "allowPartial=true fetch dispatch failed"
fi
if [ -s "$quarantine_capture_file" ]; then
    fail_java_api_fetch_projection_test "allowPartial=true quarantined an incremental mirror"
fi
if ! (fetch_docs "" "https://example.com/api/" "complete-required" "Complete mirror" 1 10 "" false); then
    fail_java_api_fetch_projection_test "allowPartial=false fetch dispatch failed"
fi
if [ ! -s "$quarantine_capture_file" ]; then
    fail_java_api_fetch_projection_test "allowPartial=false did not quarantine an incomplete mirror"
fi

java_api_dispatch_capture_file="$TEST_DOCS_ROOT/java-api-dispatch"
fetch_java_api_javadoc_seed() {
    printf '%s|%s|%s\n' "$1" "$5" "$6" > "$java_api_dispatch_capture_file"
}
generate_java_api_javadoc_seed() {
    : > "$2/.oracle-javadoc-seed.txt"
}
reconcile_java_api_seed_mirror() {
    :
}
if ! (fetch_docs \
    "21" \
    "https://example.invalid/javadoc/" \
    "java/java21-complete" \
    "Java 21 API" \
    5 \
    10 \
    "" \
    true); then
    fail_java_api_fetch_projection_test "manifest-governed Java API fetch dispatch failed"
fi
if [ "$(cat "$java_api_dispatch_capture_file")" != "$TEST_DOCS_ROOT/java/java21-complete||true" ]; then
    fail_java_api_fetch_projection_test "Java API dispatch did not preserve manifest identity independently of URL host"
fi

if ! (fetch_docs \
    "" \
    "https://docs.oracle.com/en/java/javase/21/docs/api/" \
    "generic/oracle-shaped-url" \
    "Generic Oracle-shaped URL" \
    5 \
    10 \
    "" \
    true); then
    fail_java_api_fetch_projection_test "generic source with an Oracle-shaped URL failed mirror dispatch"
fi
if [ "$(cat "$generic_dispatch_capture_file")" != "https://docs.oracle.com/en/java/javase/21/docs/api/|$TEST_DOCS_ROOT/generic/oracle-shaped-url|true" ]; then
    fail_java_api_fetch_projection_test "URL text overrode blank Java API identity"
fi

set --
# shellcheck source=fetch_all_docs.sh
source "$FETCH_SCRIPT"

LOG_FILE="$TEST_DOCS_ROOT/java-api-seed-fetch.log"
java_api_validation_capture_file="$TEST_DOCS_ROOT/java-api-validation-policy"
java_api_wget_arguments_capture_file="$TEST_DOCS_ROOT/java-api-wget-arguments"
wget() {
    printf '%s\n' "$@" > "$java_api_wget_arguments_capture_file"
    return 0
}
validate_fetch_result() {
    printf '%s\n' "$5" > "$java_api_validation_capture_file"
}

mkdir -p "$TEST_DOCS_ROOT/java-api-validation"
if ! (cd "$TEST_DOCS_ROOT/java-api-validation" \
    && fetch_java_api_javadoc_seed \
        "$TEST_DOCS_ROOT/java-api-validation" \
        "Java 21 API" \
        5 \
        10 \
        "excluded-path" \
        true); then
    fail_java_api_fetch_projection_test "Java API Javadoc validation boundary failed"
fi
if [ "$(cat "$java_api_validation_capture_file")" != "true" ]; then
    fail_java_api_fetch_projection_test "Java API Javadoc validation dropped the partial-mirror policy"
fi
if ! grep -Fxq -- '--reject-regex=excluded-path' "$java_api_wget_arguments_capture_file"; then
    fail_java_api_fetch_projection_test "Java API seed fetch dropped the manifest reject regex"
fi

set --
# shellcheck source=fetch_all_docs.sh
source "$FETCH_SCRIPT"

log() {
    :
}

DOCS_ROOT="$TEST_DOCS_ROOT"
load_java_api_documentation_sources "$JAVA_API_SOURCES_MANIFEST"
JAVA_API_RECORD_RELATIVE_PATH="java.base/java/lang/Record.html"
java_api_refetch_capture_file="$TEST_DOCS_ROOT/java-api-refetch"
generate_java_api_javadoc_seed() {
    local remote_base_url="$1"
    local target_dir="$2"
    printf '%s%s\n' "$remote_base_url" "$JAVA_API_RECORD_RELATIVE_PATH" > "$target_dir/.oracle-javadoc-seed.txt"
}
fetch_java_api_javadoc_seed() {
    printf 'refetch-required\n' > "$java_api_refetch_capture_file"
}

for java_api_source_projection in "${JAVA_API_SOURCE_PROJECTIONS[@]}"; do
    IFS='|' read -r java_release remote_base_url relative_mirror_path display_name cut_directories minimum_html_files reject_regex allow_partial <<< "$java_api_source_projection"
    java_api_target_dir="$DOCS_ROOT/$relative_mirror_path"
    mkdir -p "$java_api_target_dir/api/java.base/java/lang"
    : > "$java_api_target_dir/api/$JAVA_API_RECORD_RELATIVE_PATH"
    : > "$java_api_target_dir/Record.html"

    rm -f "$java_api_refetch_capture_file"
    if ! (fetch_docs \
        "$java_release" \
        "$remote_base_url" \
        "$relative_mirror_path" \
        "$display_name" \
        "$cut_directories" \
        2 \
        "$reject_regex" \
        "$allow_partial"); then
        fail_java_api_fetch_projection_test "Java API seed reconciliation fetch dispatch failed"
    fi
    if [ -e "$java_api_target_dir/Record.html" ]; then
        fail_java_api_fetch_projection_test "unseeded flat Record.html remained in the active Java API mirror"
    fi
    if [ ! -f "$java_api_target_dir/api/$JAVA_API_RECORD_RELATIVE_PATH" ]; then
        fail_java_api_fetch_projection_test "canonical modular Record.html was removed during Java API seed reconciliation"
    fi
    java_api_quarantine_pattern="*/$(basename "$java_api_target_dir").unseeded-java-api.*/Record.html"
    quarantined_record_file="$(find "$TEST_WORK_ROOT/.quarantine" -type f -path "$java_api_quarantine_pattern" -print -quit)"
    if [ -z "$quarantined_record_file" ]; then
        fail_java_api_fetch_projection_test "unseeded flat Record.html was not quarantined outside data/docs"
    fi
    if [ ! -f "$java_api_refetch_capture_file" ]; then
        fail_java_api_fetch_projection_test "Java API mirror was declared complete before stale files were excluded from its count"
    fi
done

run_summary_capture_file="$TEST_DOCS_ROOT/run-summary"
LOG_FILE="$TEST_DOCS_ROOT/full-run.log"
fetch_documentation_source() {
    return "$DOCUMENTATION_FETCH_PARTIAL_STATUS"
}
write_documentation_fetch_metadata() {
    printf '%s|%s|%s\n' "$TOTAL_FETCHED" "$TOTAL_PARTIAL" "$TOTAL_FAILED" > "$run_summary_capture_file"
}

if (run_documentation_fetch > /dev/null 2>&1); then
    fail_java_api_fetch_projection_test "a full fetch run reported success while every source remained partial"
fi

IFS='|' read -r aggregated_fetched_count aggregated_partial_count aggregated_failed_count < "$run_summary_capture_file"
if [ "$aggregated_fetched_count" -ne 0 ] || [ "$aggregated_partial_count" -le 0 ] || [ "$aggregated_failed_count" -ne 0 ]; then
    fail_java_api_fetch_projection_test "a full fetch run did not aggregate retained partial mirrors separately from failures"
fi

printf 'PASS: Java API fetch projections preserve canonical manifest ownership, partial-mirror semantics, and seed-bound Java API mirrors.\n'
