#!/bin/bash

# Verifies gateway embedding preflight tiers, batch shapes, and strict response validation.

set -euo pipefail

EMBEDDING_TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EMBEDDING_TEST_PROJECT_ROOT="$EMBEDDING_TEST_SCRIPT_DIRECTORY/.."
EMBEDDING_TEST_WORK_DIRECTORY="$(mktemp -d)"
EMBEDDING_TEST_CAPTURE="$EMBEDDING_TEST_WORK_DIRECTORY/curl-requests.jsonl"
trap 'rm -rf -- "$EMBEDDING_TEST_WORK_DIRECTORY"' EXIT

fail_embedding_preflight_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

PROJECT_ROOT="$EMBEDDING_TEST_PROJECT_ROOT"
RED=""
GREEN=""
YELLOW=""
BLUE=""
NC=""
# shellcheck source=lib/embedding_preflight.sh
source "$EMBEDDING_TEST_SCRIPT_DIRECTORY/lib/embedding_preflight.sh"

embedding_test_log() {
    :
}

sleep() {
    :
}

curl() {
    local output_body_file=""
    local output_headers_file=""
    local request_body=""
    local request_url=""
    local batch_tier_seen="false"
    while [ "$#" -gt 0 ]; do
        case "$1" in
            -o)
                output_body_file="$2"
                shift 2
                ;;
            -D)
                output_headers_file="$2"
                shift 2
                ;;
            -H)
                if [ "$2" = "X-Tier: batch" ]; then
                    batch_tier_seen="true"
                fi
                shift 2
                ;;
            --data)
                request_body="$2"
                shift 2
                ;;
            http*)
                request_url="$1"
                shift
                ;;
            *)
                shift
                ;;
        esac
    done
    if [ -n "$output_headers_file" ]; then
        : > "$output_headers_file"
    fi
    jq -cn \
        --arg url "$request_url" \
        --argjson batchTier "$batch_tier_seen" \
        --argjson body "${request_body:-null}" \
        '{url: $url, batchTier: $batchTier, body: $body}' \
        >> "$EMBEDDING_TEST_CAPTURE"

    if [[ "$request_url" == */models ]]; then
        printf '%s\n' \
            '{"data":[{"id":"qwen/qwen3-embedding-4b"},{"id":"later-nonmatching-model"}]}' \
            > "$output_body_file"
        printf '200'
        return
    fi

    local requested_count
    requested_count="$(jq -r '.input | length' <<< "$request_body")"
    local expected_dimensions="${EMBEDDING_TEST_DIMENSIONS:-2560}"
    local validation_mode="${EMBEDDING_TEST_MODE:-success}"
    jq -n \
        --argjson count "$requested_count" \
        --argjson dimensions "$expected_dimensions" \
        --arg mode "$validation_mode" '
        def embedding($index):
            if $mode == "null" then [null] + [range(1; $dimensions) | 0]
            elif $mode == "nonnumeric" then ["invalid"] + [range(1; $dimensions) | 0]
            elif $mode == "dimension" then [range(0; $dimensions - 1) | 0]
            else [range(0; $dimensions) | if . == $index then 1 else 0 end]
            end;
        {
            data: [range(0; (if $mode == "count" then 0 else $count end)) | {
                index: (if $mode == "order" then . + 1 else . end),
                embedding: embedding(.)
            }]
        }
    ' > "$output_body_file"
    printf '200'
}

export APP_LOCAL_EMBEDDING_ENABLED=false
export OPENAI_BASE_URL=https://gateway.test/v1
export OPENAI_API_KEY=test-gateway-key

EMBEDDING_TEST_MODE=success check_embedding_server embedding_test_log

if ! jq -e -s '
    length == 3
    and all(.[]; .batchTier == true)
    and any(.[]; .url == "https://gateway.test/v1/models")
    and ([.[] | select(.url | endswith("/embeddings")) | .body.input | length] == [1, 32])
    and all(.[] | select(.url | endswith("/embeddings")); .body.model == "qwen/qwen3-embedding-4b")
' "$EMBEDDING_TEST_CAPTURE" >/dev/null; then
    fail_embedding_preflight_test "gateway probes did not use the batch tier, model list, and batches 1 and 32"
fi

for rejected_mode in count order null nonnumeric dimension; do
    if EMBEDDING_TEST_MODE="$rejected_mode" EMBEDDING_TEST_DIMENSIONS=3 \
        validate_embedding_probe_payload \
            "https://gateway.test/v1/embeddings" \
            "test-gateway-key" \
            "qwen/qwen3-embedding-4b" \
            "strict probe" \
            "$rejected_mode" \
            embedding_test_log \
            1 \
            3; then
        fail_embedding_preflight_test "$rejected_mode embedding state was accepted"
    fi
done

printf 'PASS: gateway preflight requires X-Tier batch, batches 1/32, ordering, numeric values, and exact dimensions.\n'
