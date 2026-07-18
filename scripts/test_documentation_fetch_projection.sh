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

if [ "${#DOCUMENTATION_SOURCE_FETCH_PROJECTIONS[@]}" -ne "${#DOC_SOURCES[@]}" ]; then
    fail_documentation_fetch_projection_test "fetch projections did not preserve the complete manifest order"
fi
if [ "${#DOCUMENTATION_SOURCE_DOC_SET_PROJECTIONS[@]}" -ne "${#DOC_SOURCES[@]}" ]; then
    fail_documentation_fetch_projection_test "docSet projections did not preserve manifest ownership"
fi

for documentation_source_index in "${!DOCUMENTATION_SOURCE_FETCH_PROJECTIONS[@]}"; do
    expected_fetch_projection="${DOCUMENTATION_SOURCE_FETCH_PROJECTIONS[$documentation_source_index]}"
    documentation_fetch_projection="${DOC_SOURCES[$documentation_source_index]}"

    if [ "$documentation_fetch_projection" != "$expected_fetch_projection" ]; then
        fail_documentation_fetch_projection_test "fetch projection at index $documentation_source_index diverged from the manifest parser projection"
    fi

    fetch_execution_arguments=()
    fetch_documentation_source "$documentation_fetch_projection" > /dev/null
    if [ "${#fetch_execution_arguments[@]}" -ne 11 ]; then
        fail_documentation_fetch_projection_test "fetch execution at index $documentation_source_index must receive exactly eleven fields"
    fi

    actual_fetch_projection="$(IFS='|'; printf '%s' "${fetch_execution_arguments[*]}")"
    if [ "$actual_fetch_projection" != "$expected_fetch_projection" ]; then
        fail_documentation_fetch_projection_test "fetch execution at index $documentation_source_index diverged from the projected source"
    fi
    if [ -n "${fetch_execution_arguments[0]}" ]; then
        fail_documentation_fetch_projection_test "non-Java fetch execution at index $documentation_source_index received a Java release"
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

load_java_api_documentation_sources "$JAVA_API_SOURCES_MANIFEST"
DOCUMENTATION_DOC_SET_SELECTOR_ENABLED="true"
DOCUMENTATION_DOC_SET_SELECTOR="spring-boot,kotlin,clojure"
INCLUDE_QUICK="true"
build_documentation_fetch_sources

if [ "${#DOC_SOURCES[@]}" -ne 3 ]; then
    fail_documentation_fetch_projection_test "selected fetch must contain exactly the requested generic rows"
fi

selected_projection_index=0
for documentation_source_index in "${!DOCUMENTATION_SOURCE_FETCH_PROJECTIONS[@]}"; do
    doc_set="${DOCUMENTATION_SOURCE_DOC_SET_PROJECTIONS[$documentation_source_index]}"
    if [ "$doc_set" != "kotlin" ] && [ "$doc_set" != "clojure" ] && [ "$doc_set" != "spring-boot" ]; then
        continue
    fi
    expected_fetch_projection="${DOCUMENTATION_SOURCE_FETCH_PROJECTIONS[$documentation_source_index]}"
    if [ "${DOC_SOURCES[$selected_projection_index]}" != "$expected_fetch_projection" ]; then
        fail_documentation_fetch_projection_test "selected fetch diverged from canonical manifest order"
    fi
    selected_projection_index=$((selected_projection_index + 1))
done

python3 "$DOCUMENTATION_SEED_SCRIPT" \
    --document-type xml-sitemap \
    --input "$DOCUMENTATION_SEED_FIXTURES/sitemap.xml" \
    --discovery-url "https://docs.example.test/sitemap.xml" \
    --source-prefix "https://docs.example.test/reference/" \
    --canonical-prefix "https://docs.example.test/reference/" \
    --output "$TEST_WORK_ROOT/sitemap-seed.txt"
expected_sitemap_seed=$'https://docs.example.test/reference/collections-overview.html\nhttps://docs.example.test/reference/getting-started'
actual_sitemap_seed="$(cat "$TEST_WORK_ROOT/sitemap-seed.txt")"
if [ "$actual_sitemap_seed" != "$expected_sitemap_seed" ]; then
    fail_documentation_fetch_projection_test "XML sitemap seed was not deterministic and prefix-bounded"
fi

python3 "$DOCUMENTATION_SEED_SCRIPT" \
    --document-type html-links \
    --input "$DOCUMENTATION_SEED_FIXTURES/navigation.html" \
    --discovery-url "https://unversioned.example.test/4.1/index.html" \
    --source-prefix "https://unversioned.example.test/4.1/reference/" \
    --canonical-prefix "https://pinned.example.test/4.1.0/reference/" \
    --output "$TEST_WORK_ROOT/navigation-seed.txt"
expected_navigation_seed=$'https://pinned.example.test/4.1.0/reference/features/profiles.html\nhttps://pinned.example.test/4.1.0/reference/index.html'
actual_navigation_seed="$(cat "$TEST_WORK_ROOT/navigation-seed.txt")"
if [ "$actual_navigation_seed" != "$expected_navigation_seed" ]; then
    fail_documentation_fetch_projection_test "HTML navigation seed was not pinned, deterministic, and prefix-bounded"
fi

if python3 "$DOCUMENTATION_SEED_SCRIPT" \
    --document-type xml-sitemap \
    --input "$DOCUMENTATION_SEED_FIXTURES/sitemap-line-injection.xml" \
    --discovery-url "https://docs.example.test/sitemap.xml" \
    --source-prefix "https://docs.example.test/reference/" \
    --canonical-prefix "https://docs.example.test/reference/" \
    --output "$TEST_WORK_ROOT/injected-seed.txt" > /dev/null 2>&1; then
    fail_documentation_fetch_projection_test "structured discovery must reject line-breaking seed injection"
fi

if python3 "$DOCUMENTATION_SEED_SCRIPT" \
    --document-type unsupported-discovery \
    --input "$DOCUMENTATION_SEED_FIXTURES/sitemap.xml" \
    --discovery-url "https://docs.example.test/sitemap.xml" \
    --source-prefix "https://docs.example.test/reference/" \
    --canonical-prefix "https://docs.example.test/reference/" \
    --output "$TEST_WORK_ROOT/unsupported-seed.txt" > /dev/null 2>&1; then
    fail_documentation_fetch_projection_test "structured discovery must reject seed types outside the canonical catalog"
fi

generic_mirror_target_directory="$TEST_WORK_ROOT/generic-mirror"
generic_mirror_wget_arguments_file="$TEST_WORK_ROOT/generic-mirror-wget-arguments"
mkdir -p "$generic_mirror_target_directory"
LOG_FILE="$TEST_WORK_ROOT/documentation-fetch.log"
wget() {
    printf '%s\n' "$@" > "$generic_mirror_wget_arguments_file"
    return 0
}
validate_fetch_result() {
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
if ! grep -Fxq -- '--page-requisites' "$generic_mirror_wget_arguments_file"; then
    fail_documentation_fetch_projection_test "generic mirrors must retain linked page requisites"
fi
if grep -Eq '^--accept=' "$generic_mirror_wget_arguments_file"; then
    fail_documentation_fetch_projection_test "generic mirrors must not restrict extensionless documentation URLs"
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
wget() {
    seed_discovery_wget_call_count=$((seed_discovery_wget_call_count + 1))
    local wget_arguments_file="$seed_discovery_wget_capture_prefix-$seed_discovery_wget_call_count"
    printf '%s\n' "$@" > "$wget_arguments_file"
    if [ "$seed_discovery_wget_call_count" -eq 1 ]; then
        local wget_argument
        for wget_argument in "$@"; do
            case "$wget_argument" in
                --output-document=*)
                    cp "$DOCUMENTATION_SEED_FIXTURES/sitemap.xml" "${wget_argument#--output-document=}"
                    ;;
            esac
        done
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
        xml-sitemap \
        "https://docs.example.test/sitemap.xml" \
        "https://docs.example.test/reference/"); then
    fail_documentation_fetch_projection_test "structured discovery fetch policy failed"
fi
if ! grep -Eq '^--output-document=' "$seed_discovery_wget_capture_prefix-1"; then
    fail_documentation_fetch_projection_test "structured discovery did not write a local discovery file"
fi
assert_seed_network_policy "$seed_discovery_wget_capture_prefix-1"
assert_seed_network_policy "$seed_discovery_wget_capture_prefix-2"

if (parse_fetch_arguments --doc-sets=dev-java --doc-sets=kotlin) > /dev/null 2>&1; then
    fail_documentation_fetch_projection_test "repeated --doc-sets options must be rejected"
fi

if [ "$(documentation_fetch_cut_directories "https://example.invalid/")" != "0" ]; then
    fail_documentation_fetch_projection_test "root documentation URLs must retain zero cut directories"
fi

printf 'PASS: Documentation source fetch projections and selectors preserve canonical manifest ownership.\n'
