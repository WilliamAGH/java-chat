#!/bin/bash

# Verifies that canonical Java API manifest rows retain their seven-field fetch projection.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_SCRIPT_DIRECTORY/.." && pwd)"
FETCH_SCRIPT="$PROJECT_ROOT/scripts/fetch_all_docs.sh"
JAVA_API_SOURCES_MANIFEST="$PROJECT_ROOT/src/main/resources/java-api-documentation-sources.manifest"
TEST_DOCS_ROOT="/canonical-test-docs"

fail_java_api_fetch_projection_test() {
    local failure_message="$1"
    printf 'FAIL: %s\n' "$failure_message" >&2
    exit 1
}

test_script_arguments=("$@")
set --
# shellcheck source=fetch_all_docs.sh
source "$FETCH_SCRIPT"
set -- "${test_script_arguments[@]}"

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

printf 'PASS: Java API fetch projections retain seven distinct fields in manifest order.\n'
