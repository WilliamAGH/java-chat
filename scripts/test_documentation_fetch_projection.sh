#!/bin/bash

# Verifies that non-Java documentation sources retain their manifest-owned fetch projections.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_SCRIPT_DIRECTORY/.." && pwd)"
FETCH_SCRIPT="$PROJECT_ROOT/scripts/fetch_all_docs.sh"
DOCUMENTATION_SOURCES_MANIFEST="$PROJECT_ROOT/src/main/resources/documentation-sources.manifest"

fail_documentation_fetch_projection_test() {
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

DOCUMENTATION_SOURCE_PROJECTIONS=()
load_documentation_sources "$DOCUMENTATION_SOURCES_MANIFEST"
DOC_SOURCES=()
append_manifest_documentation_fetch_sources

if [ "${#DOCUMENTATION_SOURCE_PROJECTIONS[@]}" -ne "${#DOC_SOURCES[@]}" ]; then
    fail_documentation_fetch_projection_test "fetch projections did not preserve the complete manifest order"
fi

for documentation_source_index in "${!DOCUMENTATION_SOURCE_PROJECTIONS[@]}"; do
    manifest_projection="${DOCUMENTATION_SOURCE_PROJECTIONS[$documentation_source_index]}"
    documentation_fetch_projection="${DOC_SOURCES[$documentation_source_index]}"
    IFS='|' read -r fetch_url citation_base_url relative_mirror_path display_name doc_set source_kind doc_type doc_version <<< "$manifest_projection"
    expected_cut_directories="$(documentation_fetch_cut_directories "$fetch_url")"
    expected_fetch_projection="|$fetch_url|$relative_mirror_path|$display_name|$expected_cut_directories|$DOCUMENTATION_FETCH_DEFAULT_MINIMUM_HTML_FILES|$DOCUMENTATION_FETCH_DEFAULT_REJECT_REGEX|$DOCUMENTATION_FETCH_DEFAULT_ALLOW_PARTIAL"

    if [ "$documentation_fetch_projection" != "$expected_fetch_projection" ]; then
        fail_documentation_fetch_projection_test "fetch projection at index $documentation_source_index diverged from the canonical manifest row"
    fi

    fetch_execution_arguments=()
    fetch_documentation_source "$documentation_fetch_projection" > /dev/null
    if [ "${#fetch_execution_arguments[@]}" -ne 8 ]; then
        fail_documentation_fetch_projection_test "fetch execution at index $documentation_source_index must receive exactly eight fields"
    fi

    actual_fetch_projection="$(IFS='|'; printf '%s' "${fetch_execution_arguments[*]}")"
    if [ "$actual_fetch_projection" != "$expected_fetch_projection" ]; then
        fail_documentation_fetch_projection_test "fetch execution at index $documentation_source_index diverged from the projected source"
    fi
    if [ -n "${fetch_execution_arguments[0]}" ]; then
        fail_documentation_fetch_projection_test "non-Java fetch execution at index $documentation_source_index received a Java release"
    fi
done

if [ "$(documentation_fetch_cut_directories "https://example.invalid/")" != "0" ]; then
    fail_documentation_fetch_projection_test "root documentation URLs must retain zero cut directories"
fi

printf 'PASS: Documentation source fetch projections preserve canonical manifest ownership.\n'
