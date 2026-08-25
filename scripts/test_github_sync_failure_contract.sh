#!/bin/bash

# Verifies GitHub synchronization rejects invalid generation config before network access and propagates dependency failures.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_WORK_DIRECTORY="$(mktemp -d)"
FAKE_BINARY_DIRECTORY="$TEST_WORK_DIRECTORY/bin"
NETWORK_CAPTURE="$TEST_WORK_DIRECTORY/network-capture"
WRITER_LEASE_CAPTURE="$TEST_WORK_DIRECTORY/writer-lease-capture"
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
printf '%s\n' \
    '#!/bin/bash' \
    'printf "python3 invoked\n" >> "$GITHUB_WRITER_LEASE_CAPTURE"' \
    'exit 99' \
    > "$FAKE_BINARY_DIRECTORY/python3"
chmod +x "$FAKE_BINARY_DIRECTORY/python3"

if (
    export PATH="$FAKE_BINARY_DIRECTORY:$PATH"
    export GITHUB_NETWORK_CAPTURE="$NETWORK_CAPTURE"
    export GITHUB_WRITER_LEASE_CAPTURE="$WRITER_LEASE_CAPTURE"
    export QDRANT_HOST=invalid.example
    export QDRANT_PORT=6334
    export SPRING_PROFILE=staging
    export QDRANT_COLLECTION_DOCS=java-chat-qwen3-embedding-4b-2560-docs
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
if [ -s "$WRITER_LEASE_CAPTURE" ]; then
    fail_github_sync_test "invalid SPRING_PROFILE acquired the Qdrant writer lease"
fi

set --
# shellcheck source=lib/common_qdrant.sh
source "$TEST_SCRIPT_DIRECTORY/lib/common_qdrant.sh"

readonly GITHUB_QWEN_COLLECTION_SCHEMA_TEST_NAME="github-qwen3-embedding-4b-2560-schema-contract"
readonly LOWERCASE_IDF_COLLECTION_STATE='{
  "result": {
    "config": {
      "params": {
        "vectors": {"dense": {"size": 2560, "distance": "Cosine"}},
        "sparse_vectors": {"bm25": {"modifier": "idf"}},
        "on_disk_payload": true
      }
    }
  }
}'

if ! validate_qwen_generation_collection_state \
    "$GITHUB_QWEN_COLLECTION_SCHEMA_TEST_NAME" \
    "$LOWERCASE_IDF_COLLECTION_STATE"; then
    fail_github_sync_test "lowercase Qdrant idf modifier was rejected"
fi

uppercase_idf_collection_state="$(jq \
    '.result.config.params.sparse_vectors.bm25.modifier = "Idf"' \
    <<< "$LOWERCASE_IDF_COLLECTION_STATE")"
if validate_qwen_generation_collection_state \
    "$GITHUB_QWEN_COLLECTION_SCHEMA_TEST_NAME" \
    "$uppercase_idf_collection_state" >/dev/null 2>&1; then
    fail_github_sync_test "uppercase Qdrant Idf modifier was accepted"
fi

wrong_dense_dimension_collection_state="$(jq \
    '.result.config.params.vectors.dense.size = 1536' \
    <<< "$LOWERCASE_IDF_COLLECTION_STATE")"
if validate_qwen_generation_collection_state \
    "$GITHUB_QWEN_COLLECTION_SCHEMA_TEST_NAME" \
    "$wrong_dense_dimension_collection_state" >/dev/null 2>&1; then
    fail_github_sync_test "wrong dense-vector dimension was accepted"
fi

wrong_dense_distance_collection_state="$(jq \
    '.result.config.params.vectors.dense.distance = "Dot"' \
    <<< "$LOWERCASE_IDF_COLLECTION_STATE")"
if validate_qwen_generation_collection_state \
    "$GITHUB_QWEN_COLLECTION_SCHEMA_TEST_NAME" \
    "$wrong_dense_distance_collection_state" >/dev/null 2>&1; then
    fail_github_sync_test "wrong dense-vector distance was accepted"
fi

off_disk_payload_collection_state="$(jq \
    '.result.config.params.on_disk_payload = false' \
    <<< "$LOWERCASE_IDF_COLLECTION_STATE")"
if validate_qwen_generation_collection_state \
    "$GITHUB_QWEN_COLLECTION_SCHEMA_TEST_NAME" \
    "$off_disk_payload_collection_state" >/dev/null 2>&1; then
    fail_github_sync_test "off-disk payload configuration was accepted"
fi

# shellcheck source=lib/github_identity.sh
source "$TEST_SCRIPT_DIRECTORY/lib/github_identity.sh"
qdrant_curl() {
    return 42
}
if list_github_collections "https://qdrant.invalid" >/dev/null 2>&1; then
    fail_github_sync_test "Qdrant collection-list transport failure was accepted as an empty cohort"
fi
if read_collection_repository_metadata \
    "github-qwen3-embedding-4b-2560-example-repository" \
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

repository_url_validation_line="$(grep -n '^    extract_repository_identity "\$REPO_URL"$' \
    "$TEST_SCRIPT_DIRECTORY/process_github_repo.sh" | cut -d: -f1)"
repository_cache_lease_line="$(grep -n '^    acquire_qdrant_writer_lease$' \
    "$TEST_SCRIPT_DIRECTORY/process_github_repo.sh" | tail -1 | cut -d: -f1)"
repository_cache_refresh_line="$(grep -n 'ensure_repository_cache_clone "\$REPO_URL"' \
    "$TEST_SCRIPT_DIRECTORY/process_github_repo.sh" | cut -d: -f1)"
if [ -z "$repository_url_validation_line" ] \
    || [ -z "$repository_cache_lease_line" ] \
    || [ -z "$repository_cache_refresh_line" ] \
    || [ "$repository_url_validation_line" -ge "$repository_cache_lease_line" ] \
    || [ "$repository_cache_lease_line" -ge "$repository_cache_refresh_line" ]; then
    fail_github_sync_test "repository cache refresh is not protected by the Qdrant writer lease"
fi

printf 'PASS: GitHub sync validates the exact Qdrant generation schema before network access and propagates dependency failures.\n'
