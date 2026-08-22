#!/bin/bash

# Verifies gateway embedding preflight tiers, batch shapes, and strict response validation.

set -euo pipefail

EMBEDDING_TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EMBEDDING_TEST_PROJECT_ROOT="$EMBEDDING_TEST_SCRIPT_DIRECTORY/.."
EMBEDDING_TEST_WORK_DIRECTORY="$(mktemp -d)"
EMBEDDING_TEST_CAPTURE="$EMBEDDING_TEST_WORK_DIRECTORY/curl-requests.jsonl"
EMBEDDING_TEST_SLEEP_CAPTURE="$EMBEDDING_TEST_WORK_DIRECTORY/sleep-delays.txt"
EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS="$EMBEDDING_TEST_WORK_DIRECTORY/rate-limit-attempts.txt"
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
    printf '%s\n' "$1" >> "$EMBEDDING_TEST_SLEEP_CAPTURE"
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
    if [ "$validation_mode" = "rate_limit_once" ] \
        || [ "$validation_mode" = "rate_limit_oversized" ] \
        || [ "$validation_mode" = "rate_limit_http_date" ] \
        || [ "$validation_mode" = "rate_limit_invalid" ] \
        || [ "$validation_mode" = "service_unavailable_once" ]; then
        local rate_limit_attempt_count=0
        if [ -f "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS" ]; then
            rate_limit_attempt_count="$(cat "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS")"
        fi
        rate_limit_attempt_count=$((rate_limit_attempt_count + 1))
        printf '%s\n' "$rate_limit_attempt_count" > "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS"
        if [ "$rate_limit_attempt_count" -eq 1 ]; then
            case "$validation_mode" in
                rate_limit_oversized)
                    printf 'Retry-After: 999999999999999999999999999999\r\n' > "$output_headers_file"
                    ;;
                rate_limit_http_date)
                    printf 'Retry-After: Wed, 21 Oct 2015 07:28:00 GMT\r\n' > "$output_headers_file"
                    ;;
                rate_limit_invalid)
                    printf 'Retry-After: not-an-http-date\r\n' > "$output_headers_file"
                    ;;
                *)
                    printf 'Retry-After: 7\r\n' > "$output_headers_file"
                    ;;
            esac
            printf '%s\n' '{"error":{"message":"batch capacity is queued"}}' > "$output_body_file"
            if [ "$validation_mode" = "service_unavailable_once" ]; then
                printf '503'
            else
                printf '429'
            fi
            return
        fi
        validation_mode="success"
    fi
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
if [ "$(cat "$EMBEDDING_TEST_SLEEP_CAPTURE")" != "1" ]; then
    fail_embedding_preflight_test "gateway probes were not paced at one request per second"
fi

: > "$EMBEDDING_TEST_SLEEP_CAPTURE"
rm -f "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS"
if ! EMBEDDING_TEST_MODE=rate_limit_once \
    validate_embedding_probe_payload \
        "https://gateway.test/v1/embeddings" \
        "test-gateway-key" \
        "qwen/qwen3-embedding-4b" \
        "rate-limited probe" \
        "rate-limit" \
        embedding_test_log \
        1 \
        2560; then
    fail_embedding_preflight_test "rate-limited embedding probe did not recover"
fi
if [ "$(cat "$EMBEDDING_TEST_SLEEP_CAPTURE")" != "7" ]; then
    fail_embedding_preflight_test "gateway Retry-After did not control the next request"
fi
if [ "$(cat "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS")" != "2" ]; then
    fail_embedding_preflight_test "rate-limited embedding probe did not make exactly two attempts"
fi

: > "$EMBEDDING_TEST_SLEEP_CAPTURE"
rm -f "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS"
if ! EMBEDDING_TEST_MODE=service_unavailable_once \
    validate_embedding_probe_payload \
        "https://gateway.test/v1/embeddings" \
        "test-gateway-key" \
        "qwen/qwen3-embedding-4b" \
        "service unavailable probe" \
        "service-unavailable" \
        embedding_test_log \
        1 \
        2560; then
    fail_embedding_preflight_test "service-unavailable embedding probe did not recover"
fi
if [ "$(cat "$EMBEDDING_TEST_SLEEP_CAPTURE")" != "7" ]; then
    fail_embedding_preflight_test "non-429 Retry-After did not control the next request"
fi
if [ "$(cat "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS")" != "2" ]; then
    fail_embedding_preflight_test "service-unavailable embedding probe did not make exactly two attempts"
fi

: > "$EMBEDDING_TEST_SLEEP_CAPTURE"
rm -f "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS"
if ! EMBEDDING_TEST_MODE=rate_limit_http_date \
    validate_embedding_probe_payload \
        "https://gateway.test/v1/embeddings" \
        "test-gateway-key" \
        "qwen/qwen3-embedding-4b" \
        "HTTP-date rate limit probe" \
        "http-date-rate-limit" \
        embedding_test_log \
        1 \
        2560; then
    fail_embedding_preflight_test "HTTP-date Retry-After was rejected"
fi
if [ "$(cat "$EMBEDDING_TEST_SLEEP_CAPTURE")" != "1" ]; then
    fail_embedding_preflight_test "past HTTP-date Retry-After did not retain the local retry floor"
fi
if [ "$(cat "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS")" != "2" ]; then
    fail_embedding_preflight_test "HTTP-date embedding probe did not make exactly two attempts"
fi

: > "$EMBEDDING_TEST_SLEEP_CAPTURE"
rm -f "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS"
if EMBEDDING_TEST_MODE=rate_limit_invalid \
    validate_embedding_probe_payload \
        "https://gateway.test/v1/embeddings" \
        "test-gateway-key" \
        "qwen/qwen3-embedding-4b" \
        "invalid rate limit probe" \
        "invalid-rate-limit" \
        embedding_test_log \
        1 \
        2560; then
    fail_embedding_preflight_test "invalid Retry-After was accepted"
fi
if [ -s "$EMBEDDING_TEST_SLEEP_CAPTURE" ]; then
    fail_embedding_preflight_test "invalid Retry-After caused an early retry"
fi
if [ "$(cat "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS")" != "1" ]; then
    fail_embedding_preflight_test "invalid Retry-After was retried"
fi

: > "$EMBEDDING_TEST_SLEEP_CAPTURE"
rm -f "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS"
if EMBEDDING_TEST_MODE=rate_limit_oversized \
    validate_embedding_probe_payload \
        "https://gateway.test/v1/embeddings" \
        "test-gateway-key" \
        "qwen/qwen3-embedding-4b" \
        "oversized rate limit probe" \
        "oversized-rate-limit" \
        embedding_test_log \
        1 \
        2560; then
    fail_embedding_preflight_test "oversized Retry-After was accepted"
fi
if [ -s "$EMBEDDING_TEST_SLEEP_CAPTURE" ]; then
    fail_embedding_preflight_test "oversized Retry-After caused an early retry"
fi
if [ "$(cat "$EMBEDDING_TEST_RATE_LIMIT_ATTEMPTS")" != "1" ]; then
    fail_embedding_preflight_test "oversized Retry-After was retried"
fi

for drifted_model_and_dimensions in "qwen/qwen3-embedding-8b 2560" "qwen/qwen3-embedding-4b 4096"; do
    read -r EMBEDDING_TEST_CONFIG_MODEL EMBEDDING_TEST_CONFIG_DIMENSIONS <<< "$drifted_model_and_dimensions"
    EMBEDDING_TEST_DRIFT_CURL_CAPTURE="$EMBEDDING_TEST_WORK_DIRECTORY/drift-curl-${EMBEDDING_TEST_CONFIG_DIMENSIONS}.txt"
    if (
        read_embedding_application_property() {
            case "$1" in
                app.embeddings.model) printf '%s\n' "$EMBEDDING_TEST_CONFIG_MODEL" ;;
                app.embeddings.dimensions) printf '%s\n' "$EMBEDDING_TEST_CONFIG_DIMENSIONS" ;;
                *) return 1 ;;
            esac
        }
        curl() {
            : > "$EMBEDDING_TEST_DRIFT_CURL_CAPTURE"
            printf '200'
        }
        check_embedding_server embedding_test_log
    ); then
        fail_embedding_preflight_test "drifted embedding generation was accepted"
    fi
    if [ -e "$EMBEDDING_TEST_DRIFT_CURL_CAPTURE" ]; then
        fail_embedding_preflight_test "drifted embedding generation made a network request"
    fi
done

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

printf 'PASS: gateway preflight requires the canonical model/dimensions, batch tier, paced batches 1/32, Retry-After, ordering, and numeric values.\n'
