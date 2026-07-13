#!/bin/bash

# Verifies that canonical Java API manifest rows retain their seven-field fetch projection.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_SCRIPT_DIRECTORY/.." && pwd)"
FETCH_SCRIPT="$PROJECT_ROOT/scripts/fetch_all_docs.sh"
JAVA_API_SOURCES_MANIFEST="$PROJECT_ROOT/src/main/resources/java-api-documentation-sources.manifest"
TEST_DOCS_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_DOCS_ROOT"' EXIT

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
append_java_api_fetch_sources "$TEST_DOCS_ROOT"

if [ "${#JAVA_API_SOURCE_PROJECTIONS[@]}" -ne "${#DOC_SOURCES[@]}" ]; then
    fail_java_api_fetch_projection_test "fetch projections did not preserve the complete manifest order"
fi

for java_api_source_index in "${!JAVA_API_SOURCE_PROJECTIONS[@]}"; do
    manifest_projection="${JAVA_API_SOURCE_PROJECTIONS[$java_api_source_index]}"
    documentation_fetch_projection="${DOC_SOURCES[$java_api_source_index]}"
    fetch_projection_delimiters="${documentation_fetch_projection//[^|]/}"
    if [ "${#fetch_projection_delimiters}" -ne 6 ]; then
        fail_java_api_fetch_projection_test "Java API fetch projection at index $java_api_source_index must contain exactly seven fields"
    fi

    manifest_projection_without_release="${manifest_projection#*|}"
    expected_remote_base_url="${manifest_projection_without_release%%|*}"
    manifest_projection_after_remote_base_url="${manifest_projection_without_release#*|}"
    expected_relative_mirror_path="${manifest_projection_after_remote_base_url%%|*}"
    expected_fetch_projection="$expected_remote_base_url|$TEST_DOCS_ROOT/$expected_relative_mirror_path|${manifest_projection_after_remote_base_url#*|}"
    if [ "$documentation_fetch_projection" != "$expected_fetch_projection" ]; then
        fail_java_api_fetch_projection_test "Java API fetch projection at index $java_api_source_index diverged from the canonical manifest row"
    fi

    fetch_execution_arguments=()
    fetch_documentation_source "$documentation_fetch_projection" > /dev/null
    if [ "${#fetch_execution_arguments[@]}" -ne 7 ]; then
        fail_java_api_fetch_projection_test "Java API fetch execution at index $java_api_source_index must receive exactly seven fields"
    fi

    actual_fetch_projection="$(IFS='|'; printf '%s' "${fetch_execution_arguments[*]}")"
    if [ "$actual_fetch_projection" != "$documentation_fetch_projection" ]; then
        fail_java_api_fetch_projection_test "Java API fetch execution at index $java_api_source_index diverged from the appended projection"
    fi

    expected_allow_partial="${manifest_projection##*|}"
    manifest_projection_without_allow_partial="${manifest_projection%|*}"
    expected_reject_regex="${manifest_projection_without_allow_partial##*|}"
    if [ "${fetch_execution_arguments[5]}" != "$expected_reject_regex" ]; then
        fail_java_api_fetch_projection_test "Java API reject regex at index $java_api_source_index was not projected separately"
    fi
    if [ "${fetch_execution_arguments[6]}" != "$expected_allow_partial" ]; then
        fail_java_api_fetch_projection_test "Java API partial-mirror policy at index $java_api_source_index was not projected separately"
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

if ! validate_fetch_result 0 "$TEST_DOCS_ROOT/partial" "Partial mirror" 10 true; then
    fail_java_api_fetch_projection_test "allowPartial=true rejected a validated below-minimum mirror"
fi
if validate_fetch_result 0 "$TEST_DOCS_ROOT/complete-required" "Complete mirror" 10 false; then
    fail_java_api_fetch_projection_test "allowPartial=false accepted a below-minimum mirror"
fi

quarantine_capture_file="$TEST_DOCS_ROOT/quarantine.log"
quarantine_incomplete_dir() {
    printf 'quarantined\n' >> "$quarantine_capture_file"
}
fetch_docs_mirror() {
    return 0
}

if ! (fetch_docs "https://example.com/api/" "$TEST_DOCS_ROOT/partial" "Partial mirror" 1 10 "" true); then
    fail_java_api_fetch_projection_test "allowPartial=true fetch dispatch failed"
fi
if [ -s "$quarantine_capture_file" ]; then
    fail_java_api_fetch_projection_test "allowPartial=true quarantined an incremental mirror"
fi
if ! (fetch_docs "https://example.com/api/" "$TEST_DOCS_ROOT/complete-required" "Complete mirror" 1 10 "" false); then
    fail_java_api_fetch_projection_test "allowPartial=false fetch dispatch failed"
fi
if [ ! -s "$quarantine_capture_file" ]; then
    fail_java_api_fetch_projection_test "allowPartial=false did not quarantine an incomplete mirror"
fi

oracle_dispatch_capture_file="$TEST_DOCS_ROOT/oracle-dispatch-policy"
fetch_oracle_javadoc_seed() {
    printf '%s\n' "$6" > "$oracle_dispatch_capture_file"
}
if ! (fetch_docs \
    "https://docs.oracle.com/en/java/javase/21/docs/api/" \
    "$TEST_DOCS_ROOT/oracle-dispatch" \
    "Java 21 API" \
    5 \
    10 \
    "" \
    true); then
    fail_java_api_fetch_projection_test "Oracle Javadoc fetch dispatch failed"
fi
if [ "$(cat "$oracle_dispatch_capture_file")" != "true" ]; then
    fail_java_api_fetch_projection_test "Oracle Javadoc dispatch dropped the partial-mirror policy"
fi

set --
# shellcheck source=fetch_all_docs.sh
source "$FETCH_SCRIPT"

LOG_FILE="$TEST_DOCS_ROOT/oracle-seed-fetch.log"
oracle_validation_capture_file="$TEST_DOCS_ROOT/oracle-validation-policy"
python3() {
    : > "$5"
}
wget() {
    return 0
}
validate_fetch_result() {
    printf '%s\n' "$5" > "$oracle_validation_capture_file"
}

mkdir -p "$TEST_DOCS_ROOT/oracle-validation"
if ! (cd "$TEST_DOCS_ROOT/oracle-validation" \
    && fetch_oracle_javadoc_seed \
        "https://docs.oracle.com/en/java/javase/21/docs/api/" \
        "$TEST_DOCS_ROOT/oracle-validation" \
        "Java 21 API" \
        5 \
        10 \
        true); then
    fail_java_api_fetch_projection_test "Oracle Javadoc validation boundary failed"
fi
if [ "$(cat "$oracle_validation_capture_file")" != "true" ]; then
    fail_java_api_fetch_projection_test "Oracle Javadoc validation dropped the partial-mirror policy"
fi

printf 'PASS: Java API fetch projections retain all fields and enforce partial-mirror policy.\n'
