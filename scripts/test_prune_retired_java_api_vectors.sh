#!/bin/bash

# Exercises the strict retired Java API Qdrant vector prune without a live Qdrant service.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_SCRIPT_DIRECTORY/.." && pwd)"
PRUNE_SCRIPT="$PROJECT_ROOT/scripts/prune_retired_java_api_vectors.sh"
TEST_WORK_DIRECTORY="$(mktemp -d)"
MOCK_QDRANT_DIRECTORY="$TEST_WORK_DIRECTORY/mock-qdrant"
mkdir -p "$MOCK_QDRANT_DIRECTORY"
trap 'rm -rf "$TEST_WORK_DIRECTORY"' EXIT

fail_retired_java_api_vector_prune_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

assert_retired_java_api_vector_prune_test() {
    local assertion="$1"
    local failure_message="$2"
    if ! eval "$assertion"; then
        fail_retired_java_api_vector_prune_test "$failure_message"
    fi
}

# shellcheck source=prune_retired_java_api_vectors.sh
source "$PRUNE_SCRIPT"

reset_mock_qdrant() {
    rm -rf "$MOCK_QDRANT_DIRECTORY"
    mkdir -p "$MOCK_QDRANT_DIRECTORY"
}

mock_qdrant_reply() {
    local qdrant_json="$1"
    local http_status="$2"
    printf '%s\n%s\n' "$qdrant_json" "$http_status"
}

mock_qdrant_scroll_page_one() {
    jq -cn '
        {
            status: "ok",
            result: {
                points: [
                    {
                        id: 1,
                        payload: {
                            url: "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/class-use/List.html"
                        }
                    },
                    {
                        id: "root-list",
                        payload: {
                            url: "https://docs.oracle.com/en/java/javase/25/docs/api/List.html#of(E...)"
                        }
                    },
                    {
                        id: "canonical-list",
                        payload: {
                            url: "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html"
                        }
                    },
                    {
                        id: "non-oracle-class-use",
                        payload: {
                            url: "https://example.invalid/en/java/javase/25/docs/api/java.base/java/util/class-use/List.html"
                        }
                    },
                    {
                        id: "root-lowercase-page",
                        payload: {
                            url: "https://docs.oracle.com/en/java/javase/25/docs/api/package-summary.html"
                        }
                    }
                ],
                next_page_offset: "after-page-one"
            }
        }
    '
}

mock_qdrant_scroll_page_two() {
    jq -cn '
        {
            status: "ok",
            result: {
                points: [
                    range(2; 102)
                    | {
                        id: .,
                        payload: {
                            url: ("https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/class-use/Vector" + tostring + ".html")
                        }
                    }
                ],
                next_page_offset: null
            }
        }
    '
}

mock_qdrant_verification_page() {
    local verification_mode="$1"
    if [ "$verification_mode" = "verification-failure" ]; then
        jq -cn '
            {
                status: "ok",
                result: {
                    points: [
                        {
                            id: "still-retired",
                            payload: {
                                url: "https://docs.oracle.com/en/java/javase/25/docs/api/String.html"
                            }
                        }
                    ],
                    next_page_offset: null
                }
            }
        '
        return
    fi
    jq -cn '
        {
            status: "ok",
            result: {
                points: [
                    {
                        id: "canonical-list",
                        payload: {
                            url: "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html"
                        }
                    }
                ],
                next_page_offset: null
            }
        }
    '
}

load_env_file() {
    printf 'loaded\n' >> "$MOCK_QDRANT_DIRECTORY/environment-loads"
}

apply_pipeline_defaults() {
    printf 'applied\n' >> "$MOCK_QDRANT_DIRECTORY/default-applications"
}

check_qdrant_connection() {
    printf 'checked\n' >> "$MOCK_QDRANT_DIRECTORY/connection-checks"
    return 0
}

qdrant_rest_base_url() {
    printf 'http://mock-qdrant.invalid'
}

qdrant_curl() {
    local qdrant_argument
    local request_body=""
    local request_target=""
    local expect_request_body=false
    for qdrant_argument in "$@"; do
        if [ "$expect_request_body" = true ]; then
            request_body="$qdrant_argument"
            expect_request_body=false
            continue
        fi
        if [ "$qdrant_argument" = "-d" ]; then
            expect_request_body=true
            continue
        fi
        request_target="$qdrant_argument"
    done

    case "$request_target" in
        */points/scroll)
            local scroll_call_count
            scroll_call_count="$(( $(find "$MOCK_QDRANT_DIRECTORY" -maxdepth 1 -name 'scroll-target-*' | wc -l | tr -d ' ') + 1 ))"
            printf '%s\n' "$request_target" > "$MOCK_QDRANT_DIRECTORY/scroll-target-$scroll_call_count"
            printf '%s' "$request_body" > "$MOCK_QDRANT_DIRECTORY/scroll-body-$scroll_call_count.json"
            if [ "${MOCK_QDRANT_MODE:-success}" = "scroll-http-failure" ]; then
                mock_qdrant_reply '{"status":"error"}' '503'
                return
            fi
            case "$scroll_call_count" in
                1)
                    mock_qdrant_reply "$(mock_qdrant_scroll_page_one)" '200'
                    ;;
                2)
                    mock_qdrant_reply "$(mock_qdrant_scroll_page_two)" '200'
                    ;;
                3)
                    mock_qdrant_reply "$(mock_qdrant_verification_page "${MOCK_QDRANT_MODE:-success}")" '200'
                    ;;
                *)
                    mock_qdrant_reply '{"status":"error"}' '500'
                    ;;
            esac
            ;;
        */points/delete\?wait=true)
            local delete_call_count
            delete_call_count="$(( $(find "$MOCK_QDRANT_DIRECTORY" -maxdepth 1 -name 'delete-target-*' | wc -l | tr -d ' ') + 1 ))"
            printf '%s\n' "$request_target" > "$MOCK_QDRANT_DIRECTORY/delete-target-$delete_call_count"
            printf '%s' "$request_body" > "$MOCK_QDRANT_DIRECTORY/delete-body-$delete_call_count.json"
            if [ "${MOCK_QDRANT_MODE:-success}" = "delete-http-failure" ]; then
                mock_qdrant_reply '{"status":"error"}' '503'
            else
                mock_qdrant_reply '{"status":"ok","result":{"operation_id":1}}' '200'
            fi
            ;;
        *)
            mock_qdrant_reply '{"status":"error"}' '404'
            ;;
    esac
}

if ! java_api_vector_prune_required "java/java25-complete"; then
    fail_retired_java_api_vector_prune_test "canonical Java API mirror selector did not require pruning"
fi
if ! java_api_vector_prune_required "all"; then
    fail_retired_java_api_vector_prune_test "all selector did not require pruning"
fi
if ! java_api_vector_prune_required "kotlin, java/java24-complete"; then
    fail_retired_java_api_vector_prune_test "mixed Java API selector did not require pruning"
fi
if java_api_vector_prune_required "kotlin,spring-boot"; then
    fail_retired_java_api_vector_prune_test "non-Java selector unexpectedly required pruning"
else
    non_java_selection_status=$?
fi
if [ "$non_java_selection_status" -ne 1 ]; then
    fail_retired_java_api_vector_prune_test "non-Java selector did not return the skip status"
fi

canonical_java_api_sources_manifest="$JAVA_API_SOURCES_MANIFEST"
JAVA_API_SOURCES_MANIFEST="$TEST_WORK_DIRECTORY/missing-java-api-sources.manifest"
if java_api_vector_prune_required "kotlin" 2>/dev/null; then
    fail_retired_java_api_vector_prune_test "unreadable Java API manifest unexpectedly selected a mirror"
else
    manifest_selection_status=$?
fi
if [ "$manifest_selection_status" -ne 2 ]; then
    fail_retired_java_api_vector_prune_test "unreadable Java API manifest did not return the hard-failure status"
fi
if java_api_vector_prune_required "" 2>/dev/null; then
    fail_retired_java_api_vector_prune_test "blank selector accepted an unreadable Java API manifest"
else
    blank_manifest_selection_status=$?
fi
if [ "$blank_manifest_selection_status" -ne 2 ]; then
    fail_retired_java_api_vector_prune_test "blank selector did not fail hard for an unreadable Java API manifest"
fi
malformed_java_api_sources_manifest="$TEST_WORK_DIRECTORY/malformed-java-api-sources.manifest"
printf 'javaRelease|remoteBaseUrl|unexpectedColumn\n25|https://docs.oracle.com/|java/java25-complete\n' \
    > "$malformed_java_api_sources_manifest"
JAVA_API_SOURCES_MANIFEST="$malformed_java_api_sources_manifest"
if java_api_vector_prune_required "java/java25-complete" 2>/dev/null; then
    fail_retired_java_api_vector_prune_test "malformed Java API manifest unexpectedly selected a mirror"
else
    malformed_manifest_selection_status=$?
fi
if [ "$malformed_manifest_selection_status" -ne 2 ]; then
    fail_retired_java_api_vector_prune_test "malformed Java API manifest did not return the hard-failure status"
fi
JAVA_API_SOURCES_MANIFEST="$canonical_java_api_sources_manifest"

reset_mock_qdrant
unset QDRANT_COLLECTION_DOCS
export MOCK_QDRANT_MODE="success"
if ! prune_retired_java_api_vectors "kotlin" > /dev/null; then
    fail_retired_java_api_vector_prune_test "non-Java-only prune invocation failed"
fi
if find "$MOCK_QDRANT_DIRECTORY" -maxdepth 1 -name '*target-*' | grep -q .; then
    fail_retired_java_api_vector_prune_test "non-Java-only prune invocation contacted Qdrant"
fi
if [ -e "$MOCK_QDRANT_DIRECTORY/environment-loads" ]; then
    fail_retired_java_api_vector_prune_test "non-Java-only prune invocation loaded the environment"
fi

reset_mock_qdrant
if ! prune_retired_java_api_vectors "java/java25-complete" > /dev/null; then
    fail_retired_java_api_vector_prune_test "Java API prune invocation failed"
fi
if [ "$(find "$MOCK_QDRANT_DIRECTORY" -maxdepth 1 -name 'scroll-target-*' | wc -l | tr -d ' ')" -ne 3 ]; then
    fail_retired_java_api_vector_prune_test "prune did not perform two-page scan plus verification scan"
fi
if [ "$(find "$MOCK_QDRANT_DIRECTORY" -maxdepth 1 -name 'delete-target-*' | wc -l | tr -d ' ')" -ne 2 ]; then
    fail_retired_java_api_vector_prune_test "prune did not split retired vectors into bounded delete batches"
fi
if ! jq -e '
    .limit == 256 and .with_payload == ["url"] and .with_vector == false and (has("offset") | not)
' "$MOCK_QDRANT_DIRECTORY/scroll-body-1.json" >/dev/null; then
    fail_retired_java_api_vector_prune_test "initial scroll request did not request URL payloads without vectors"
fi
if ! jq -e '.offset == "after-page-one"' "$MOCK_QDRANT_DIRECTORY/scroll-body-2.json" >/dev/null; then
    fail_retired_java_api_vector_prune_test "second scroll request did not carry Qdrant's page offset"
fi
if ! jq -e '(has("offset") | not)' "$MOCK_QDRANT_DIRECTORY/scroll-body-3.json" >/dev/null; then
    fail_retired_java_api_vector_prune_test "verification scan reused a stale pagination offset"
fi
if ! jq -e '.points | length == 100' "$MOCK_QDRANT_DIRECTORY/delete-body-1.json" >/dev/null \
    || ! jq -e '.points | length == 2' "$MOCK_QDRANT_DIRECTORY/delete-body-2.json" >/dev/null; then
    fail_retired_java_api_vector_prune_test "delete batches did not preserve the configured 100-point bound"
fi
for delete_request_target_file in "$MOCK_QDRANT_DIRECTORY"/delete-target-*; do
    if ! grep -q -- '/points/delete?wait=true$' "$delete_request_target_file"; then
        fail_retired_java_api_vector_prune_test "delete requests did not use wait=true"
    fi
done
if ! jq -s -e '
    ([.[].points[]] | length == 102)
    and ([.[].points[]] | index("root-list") != null)
    and ([.[].points[]] | index("canonical-list") == null)
    and ([.[].points[]] | index("non-oracle-class-use") == null)
    and ([.[].points[]] | index("root-lowercase-page") == null)
' "$MOCK_QDRANT_DIRECTORY"/delete-body-*.json >/dev/null; then
    fail_retired_java_api_vector_prune_test "prune selected a URL outside its two retired Java API patterns"
fi
for qdrant_request_target_file in "$MOCK_QDRANT_DIRECTORY"/*target-*; do
    if ! grep -q -- '/collections/java-docs/' "$qdrant_request_target_file"; then
        fail_retired_java_api_vector_prune_test "default docs collection was not java-docs"
    fi
done

reset_mock_qdrant
export MOCK_QDRANT_MODE="verification-failure"
if prune_retired_java_api_vectors "java/java25-complete" > /dev/null 2>&1; then
    fail_retired_java_api_vector_prune_test "prune accepted a verification scan that still contained retired vectors"
fi

reset_mock_qdrant
export MOCK_QDRANT_MODE="delete-http-failure"
if prune_retired_java_api_vectors "java/java25-complete" > /dev/null 2>&1; then
    fail_retired_java_api_vector_prune_test "prune accepted an unsuccessful delete response"
fi

reset_mock_qdrant
export MOCK_QDRANT_MODE="scroll-http-failure"
if prune_retired_java_api_vectors "java/java25-complete" > /dev/null 2>&1; then
    fail_retired_java_api_vector_prune_test "prune accepted an unsuccessful scroll response"
fi

printf 'PASS: retired Java API vector prune tests\n'
