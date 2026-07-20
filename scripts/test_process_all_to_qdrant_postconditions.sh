#!/bin/bash

# Verifies exact per-docSet Qdrant completion checks without an external service.

set -euo pipefail

POSTCONDITION_TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POSTCONDITION_TEST_WORK_DIRECTORY="$(mktemp -d)"
POSTCONDITION_TEST_CAPTURE="$POSTCONDITION_TEST_WORK_DIRECTORY/qdrant-requests.jsonl"
trap 'rm -rf -- "$POSTCONDITION_TEST_WORK_DIRECTORY"' EXIT

fail_postcondition_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

set --
# shellcheck source=process_all_to_qdrant.sh
source "$POSTCONDITION_TEST_SCRIPT_DIRECTORY/process_all_to_qdrant.sh"

qdrant_rest_base_url() {
    printf '%s\n' "http://qdrant.test"
}

log() {
    :
}

qdrant_curl() {
    local request_body=""
    local request_url=""
    while [ "$#" -gt 0 ]; do
        case "$1" in
            -d)
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
    jq -cn --arg url "$request_url" --argjson body "$request_body" '{url: $url, body: $body}' \
        >> "$POSTCONDITION_TEST_CAPTURE"

    case "${POSTCONDITION_TEST_MODE:-success}:$request_url" in
        malformed:*) printf '%s\n' '{"status":"ok"}' ;;
        zero:*) printf '%s\n' '{"result":{"count":0}}' ;;
        success:*/java-chat-local-qwen3-embedding-4b-2560-docs/*)
            printf '%s\n' '{"result":{"count":1}}'
            ;;
        success:*) printf '%s\n' '{"result":{"count":0}}' ;;
    esac
}

export QDRANT_COLLECTION_BOOKS=java-chat-local-qwen3-embedding-4b-2560-books
export QDRANT_COLLECTION_DOCS=java-chat-local-qwen3-embedding-4b-2560-docs
export QDRANT_COLLECTION_ARTICLES=java-chat-local-qwen3-embedding-4b-2560-articles
export QDRANT_COLLECTION_PDFS=java-chat-local-qwen3-embedding-4b-2560-pdfs

SUCCESS_LOG="$POSTCONDITION_TEST_WORK_DIRECTORY/success.log"
printf '%s\n' \
    'Qdrant postcondition required for docSet: kotlin   ' \
    'Qdrant postcondition required for docSet: java/java25-complete' \
    > "$SUCCESS_LOG"
POSTCONDITION_TEST_MODE=success verify_doc_set_postconditions "$SUCCESS_LOG"

if [ "$(wc -l < "$POSTCONDITION_TEST_CAPTURE" | tr -d ' ')" -ne 8 ]; then
    fail_postcondition_test "every emitted docSet was not checked across all four active collections"
fi
if ! jq -e -s '
    all(.[]; .body.exact == true)
    and all(.[]; .body.filter.must[0].key == "docSet")
    and ((map(.body.filter.must[0].match.value) | unique | sort) == ["java/java25-complete", "kotlin"])
' "$POSTCONDITION_TEST_CAPTURE" >/dev/null; then
    fail_postcondition_test "Qdrant count requests did not use exact docSet filters"
fi

EMPTY_LOG="$POSTCONDITION_TEST_WORK_DIRECTORY/empty.log"
: > "$EMPTY_LOG"
if POSTCONDITION_TEST_MODE=success verify_doc_set_postconditions "$EMPTY_LOG" >/dev/null 2>&1; then
    fail_postcondition_test "missing processor postconditions were accepted"
fi
if POSTCONDITION_TEST_MODE=zero verify_doc_set_postconditions "$SUCCESS_LOG" >/dev/null 2>&1; then
    fail_postcondition_test "zero exact docSet count was accepted"
fi
if POSTCONDITION_TEST_MODE=malformed verify_doc_set_postconditions "$SUCCESS_LOG" >/dev/null 2>&1; then
    fail_postcondition_test "malformed Qdrant count state was accepted"
fi

printf 'PASS: every emitted docSet requires a valid nonzero exact count across the active collections.\n'
