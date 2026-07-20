#!/bin/bash

# Verifies GitHub synchronization rejects invalid generation config before network access and propagates dependency failures.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_WORK_DIRECTORY="$(mktemp -d)"
FAKE_BINARY_DIRECTORY="$TEST_WORK_DIRECTORY/bin"
NETWORK_CAPTURE="$TEST_WORK_DIRECTORY/network-capture"
mkdir -p "$FAKE_BINARY_DIRECTORY"
trap 'rm -rf -- "$TEST_WORK_DIRECTORY"' EXIT

fail_github_sync_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

printf '%s\n' \
    '#!/bin/bash' \
    'printf "curl invoked\n" >> "$GITHUB_NETWORK_CAPTURE"' \
    'exit 99' \
    > "$FAKE_BINARY_DIRECTORY/curl"
chmod +x "$FAKE_BINARY_DIRECTORY/curl"

if (
    export PATH="$FAKE_BINARY_DIRECTORY:$PATH"
    export GITHUB_NETWORK_CAPTURE="$NETWORK_CAPTURE"
    export QDRANT_HOST=invalid.example
    export QDRANT_PORT=6334
    export SPRING_PROFILE=staging
    export QDRANT_COLLECTION_DOCS=java-chat-staging-qwen3-embedding-4b-2560-docs
    export DOCS_SNAPSHOT_DIR=/app/data/qwen3-embedding-4b-2560/staging/snapshots
    export DOCS_PARSED_DIR=/app/data/qwen3-embedding-4b-2560/staging/parsed
    export DOCS_INDEX_DIR=/app/data/qwen3-embedding-4b-2560/staging/index
    bash "$TEST_SCRIPT_DIRECTORY/process_github_repo.sh" --sync-existing >/dev/null 2>&1
); then
    fail_github_sync_test "invalid SPRING_PROFILE was accepted"
fi
if [ -s "$NETWORK_CAPTURE" ]; then
    fail_github_sync_test "invalid SPRING_PROFILE reached a network preflight"
fi

set --
# shellcheck source=lib/common_qdrant.sh
source "$TEST_SCRIPT_DIRECTORY/lib/common_qdrant.sh"
# shellcheck source=lib/github_identity.sh
source "$TEST_SCRIPT_DIRECTORY/lib/github_identity.sh"
export SPRING_PROFILE=dev

qdrant_curl() {
    return 42
}
if list_github_collections "https://qdrant.invalid" >/dev/null 2>&1; then
    fail_github_sync_test "Qdrant collection-list transport failure was accepted as an empty cohort"
fi
if read_collection_repository_metadata \
    "github-dev-qwen3-embedding-4b-2560-example-repository" \
    "https://qdrant.invalid" >/dev/null 2>&1; then
    fail_github_sync_test "Qdrant metadata transport failure was accepted as missing metadata"
fi

qdrant_curl() {
    printf '%s\n' '{"result":{"collections":"malformed"}}'
}
if list_github_collections "https://qdrant.invalid" >/dev/null 2>&1; then
    fail_github_sync_test "malformed Qdrant collection JSON was accepted as an empty cohort"
fi

qdrant_curl() {
    printf '%s\n' '{"result":{"collections":[]}}'
}
if ! empty_collection_cohort="$(list_github_collections "https://qdrant.invalid")"; then
    fail_github_sync_test "a valid empty Qdrant collection cohort was rejected"
fi
if [ -n "$empty_collection_cohort" ]; then
    fail_github_sync_test "a valid empty Qdrant collection cohort produced collection names"
fi

git() {
    return 12
}
if remote_head_commit "https://github.com/example/repository" >/dev/null 2>&1; then
    fail_github_sync_test "GitHub remote HEAD failure was accepted as an empty commit"
fi

printf 'PASS: GitHub sync validates generation config before network access and propagates dependency failures.\n'
