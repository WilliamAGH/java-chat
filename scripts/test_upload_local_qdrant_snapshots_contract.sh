#!/bin/bash

# Verifies the self-hosted Qdrant migration remains fail-closed and completion-gated.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
uploader_path="$TEST_SCRIPT_DIRECTORY/upload_local_qdrant_snapshots.sh"
reconciler_unit="$TEST_SCRIPT_DIRECTORY/../infra/systemd/user/java-chat-production-qdrant-reconcile.service"
TEST_WORK_DIRECTORY="$(mktemp -d)"
trap 'rm -rf -- "$TEST_WORK_DIRECTORY"' EXIT

fail_uploader_contract_test() {
    if [ -s "$TEST_WORK_DIRECTORY/home/uploader-output" ]; then
        cat "$TEST_WORK_DIRECTORY/home/uploader-output" >&2
    fi
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

uploader_line() {
    local expected_text="$1"
    local matching_lines
    matching_lines="$(grep -Fn "$expected_text" "$uploader_path" || true)"
    if [ "$(printf '%s\n' "$matching_lines" | sed '/^$/d' | wc -l | tr -d ' ')" -ne 1 ]; then
        fail_uploader_contract_test "uploader must contain exactly one: $expected_text"
    fi
    printf '%s\n' "${matching_lines%%:*}"
}

bash -n "$uploader_path"
if env -u QDRANT_API_KEY bash "$uploader_path" >/dev/null 2>&1; then
    fail_uploader_contract_test "uploader accepted a missing Qdrant API key"
fi

mkdir -p "$TEST_WORK_DIRECTORY/bin" "$TEST_WORK_DIRECTORY/home/.local/state/java-chat"
touch "$TEST_WORK_DIRECTORY/home/.local/state/java-chat/queued-platform-documentation-staging.complete"
cat > "$TEST_WORK_DIRECTORY/bin/curl" <<'FAKE_CURL'
#!/bin/bash
set -euo pipefail

request_method="GET"
request_url=""
for curl_argument in "$@"; do
    case "$curl_argument" in
        --request) ;;
        POST|DELETE) request_method="$curl_argument" ;;
        http://*|https://*) request_url="$curl_argument" ;;
    esac
done
printf '%s %s\n' "$request_method" "$request_url" >> "$HOME/curl-calls"

case "$request_method:$request_url" in
    "GET:http://127.0.0.1:8087/"|"GET:https://target.invalid:6333/")
        printf '%s\n' '{"title":"qdrant - vector search engine","version":"1.18.3"}'
        ;;
    "GET:http://127.0.0.1:8087/telemetry?details_level=0")
        printf '%s\n' '{"result":{"id":"source-instance"}}'
        ;;
    "GET:https://target.invalid:6333/telemetry?details_level=0")
        if [ -e "$HOME/same-instance" ]; then
            printf '%s\n' '{"result":{"id":"source-instance"}}'
        else
            printf '%s\n' '{"result":{"id":"target-instance"}}'
        fi
        ;;
    "GET:http://127.0.0.1:8087/collections")
        printf '%s\n' '{"result":{"collections":[{"name":"java-chat-qwen3-embedding-4b-2560-docs"}]}}'
        ;;
    "GET:http://127.0.0.1:8087/collections/java-chat-qwen3-embedding-4b-2560-docs")
        printf '%s\n' '{"result":{"config":{},"payload_schema":{},"status":"green"}}'
        ;;
    "POST:http://127.0.0.1:8087/collections/java-chat-qwen3-embedding-4b-2560-docs/points/count")
        printf '%s\n' '{"result":{"count":1}}'
        ;;
    "POST:http://127.0.0.1:8087/collections/java-chat-qwen3-embedding-4b-2560-docs/snapshots?wait=true")
        printf '%s\n' '{"result":{"name":"current.snapshot","checksum":"abc123"}}'
        ;;
    "GET:http://127.0.0.1:8087/collections/java-chat-qwen3-embedding-4b-2560-docs/snapshots/current.snapshot")
        printf '%s' 'snapshot-bytes'
        ;;
    "POST:https://target.invalid:6333/collections/java-chat-qwen3-embedding-4b-2560-docs/snapshots/upload?wait=true&priority=snapshot&checksum=abc123")
        cat >/dev/null
        if [ -e "$HOME/upload-success" ]; then
            printf '%s\n' '{"status":"ok"}'
        else
            exit 22
        fi
        ;;
    "GET:https://target.invalid:6333/collections/java-chat-qwen3-embedding-4b-2560-docs")
        printf '%s\n' '{"result":{"config":{},"payload_schema":{},"status":"green"}}'
        ;;
    "POST:https://target.invalid:6333/collections/java-chat-qwen3-embedding-4b-2560-docs/points/count")
        printf '%s\n' '{"result":{"count":1}}'
        ;;
    "DELETE:http://127.0.0.1:8087/collections/java-chat-qwen3-embedding-4b-2560-docs/snapshots/current.snapshot?wait=true")
        printf '%s\n' '{"status":"ok"}'
        ;;
    *)
        printf 'Unexpected fake curl request: %s %s\n' "$request_method" "$request_url" >&2
        exit 64
        ;;
esac
FAKE_CURL
chmod +x "$TEST_WORK_DIRECTORY/bin/curl"

touch "$TEST_WORK_DIRECTORY/home/same-instance"
if HOME="$TEST_WORK_DIRECTORY/home" PATH="$TEST_WORK_DIRECTORY/bin:$PATH" \
    QDRANT_API_KEY=contract-test QDRANT_HOST=target.invalid QDRANT_REST_PORT=6333 QDRANT_SSL=true \
    bash "$uploader_path" 9>&- > "$TEST_WORK_DIRECTORY/home/uploader-output" 2>&1; then
    fail_uploader_contract_test "uploader restored a Qdrant instance onto itself"
fi
grep -Fq 'Source and target must be different Qdrant instances' "$TEST_WORK_DIRECTORY/home/uploader-output" \
    || fail_uploader_contract_test "same-instance rejection did not reach the identity gate"
if [ -e "$TEST_WORK_DIRECTORY/home/curl-calls" ] \
    && grep -Fq '/snapshots?wait=true' "$TEST_WORK_DIRECTORY/home/curl-calls"; then
    fail_uploader_contract_test "same-instance rejection occurred after snapshot creation"
fi

rm "$TEST_WORK_DIRECTORY/home/same-instance" "$TEST_WORK_DIRECTORY/home/curl-calls" \
    "$TEST_WORK_DIRECTORY/home/uploader-output"
if HOME="$TEST_WORK_DIRECTORY/home" PATH="$TEST_WORK_DIRECTORY/bin:$PATH" \
    QDRANT_API_KEY=contract-test QDRANT_HOST=target.invalid QDRANT_REST_PORT=6333 QDRANT_SSL=true \
    bash "$uploader_path" 9>&- > "$TEST_WORK_DIRECTORY/home/uploader-output" 2>&1; then
    fail_uploader_contract_test "uploader accepted a failed snapshot upload"
fi
grep -Fxq \
    'DELETE http://127.0.0.1:8087/collections/java-chat-qwen3-embedding-4b-2560-docs/snapshots/current.snapshot?wait=true' \
    "$TEST_WORK_DIRECTORY/home/curl-calls" \
    || fail_uploader_contract_test "failed upload did not delete its invocation-created snapshot"
if [ -e "$TEST_WORK_DIRECTORY/home/.local/state/java-chat/production-qdrant-reconcile.complete" ]; then
    fail_uploader_contract_test "failed upload created the reconciliation completion marker"
fi

rm "$TEST_WORK_DIRECTORY/home/curl-calls" "$TEST_WORK_DIRECTORY/home/uploader-output"
touch "$TEST_WORK_DIRECTORY/home/upload-success"
HOME="$TEST_WORK_DIRECTORY/home" PATH="$TEST_WORK_DIRECTORY/bin:$PATH" \
    QDRANT_API_KEY=contract-test QDRANT_HOST=target.invalid QDRANT_REST_PORT=6333 QDRANT_SSL=true \
    bash "$uploader_path" 9>&- > "$TEST_WORK_DIRECTORY/home/uploader-output" 2>&1
grep -Fxq \
    'POST https://target.invalid:6333/collections/java-chat-qwen3-embedding-4b-2560-docs/points/count' \
    "$TEST_WORK_DIRECTORY/home/curl-calls" \
    || fail_uploader_contract_test "successful recovery did not verify the exact target count"
if [ ! -e "$TEST_WORK_DIRECTORY/home/.local/state/java-chat/production-qdrant-reconcile.complete" ]; then
    fail_uploader_contract_test "successful recovery did not create the reconciliation completion marker"
fi
if QDRANT_API_KEY=contract-test \
    QDRANT_HOST=contract.invalid \
    QDRANT_REST_PORT=01000 \
    QDRANT_SSL=false \
    bash "$uploader_path" >/dev/null 2>&1; then
    fail_uploader_contract_test "uploader accepted an ambiguous leading-zero port"
fi
grep -Fq ': "${QDRANT_REST_PORT:?QDRANT_REST_PORT is required for the self-hosted Qdrant target}"' "$uploader_path" \
    && grep -Fq 'readonly TARGET_QDRANT_REST_BASE="$TARGET_QDRANT_SCHEME://$QDRANT_HOST:$QDRANT_REST_PORT"' "$uploader_path" \
    || fail_uploader_contract_test "target does not use the injected Java Chat Qdrant REST connection"
if grep -Eq 'qdrant\.haiku\.host|qdrant\.2\.haiku\.host' "$uploader_path"; then
    fail_uploader_contract_test "uploader bypasses the production Java Chat Qdrant connection"
fi
grep -Fq 'queued-platform-documentation-staging.complete' "$uploader_path" \
    || fail_uploader_contract_test "documentation snapshot is not gated on terminal staging"
grep -Fq 'config: .result.config' "$uploader_path" \
    && grep -Fq 'payloadSchema: .result.payload_schema' "$uploader_path" \
    || fail_uploader_contract_test "collection verification does not compare full stable configuration"
grep -Fq 'Configured local source must identify Qdrant' "$uploader_path" \
    || fail_uploader_contract_test "uploader does not validate the local Qdrant owner"
grep -Fq 'SOURCE_COLLECTION_EMPTY' "$uploader_path" \
    || fail_uploader_contract_test "empty local collections can overwrite production-owned data"
completion_wait_line="$(uploader_line 'while [ ! -e "$DOCUMENTATION_STAGING_COMPLETE_MARKER" ]; do')"
writer_lease_line="$(uploader_line 'acquire_qdrant_writer_lease')"
final_inventory_line="$(uploader_line 'final_collection_names="$(local_collection_names)"')"
completion_marker_line="$(uploader_line 'touch "$QDRANT_RECONCILIATION_COMPLETE_MARKER"')"
if [ -z "$completion_wait_line" ] || [ -z "$writer_lease_line" ] \
    || [ -z "$final_inventory_line" ] || [ -z "$completion_marker_line" ] \
    || [ "$completion_wait_line" -ge "$final_inventory_line" ] \
    || [ "$completion_wait_line" -ge "$writer_lease_line" ] \
    || [ "$writer_lease_line" -ge "$final_inventory_line" ] \
    || [ "$final_inventory_line" -ge "$completion_marker_line" ]; then
    fail_uploader_contract_test "final all-collection reconciliation is not completion-gated"
fi
if grep -Fq 'initial_collection_names=' "$uploader_path"; then
    fail_uploader_contract_test "snapshot migration can race the active local writer"
fi
grep -Fq 'Completed local inventory lacks the documentation collection' "$uploader_path" \
    || fail_uploader_contract_test "empty local inventory can create a terminal completion marker"
if grep -Fq 'TARGET_COLLECTION_EXISTS' "$uploader_path" \
    || grep -Fq 'latest_snapshot_metadata "$collection_name"' "$uploader_path"; then
    fail_uploader_contract_test "uploader can accept equal counts or reuse a stale snapshot"
fi
grep -Fq -- '--speed-limit "$SNAPSHOT_TRANSFER_MINIMUM_BYTES_PER_SECOND"' "$uploader_path" \
    && grep -Fq -- '--speed-time "$SNAPSHOT_TRANSFER_STALL_SECONDS"' "$uploader_path" \
    || fail_uploader_contract_test "snapshot transfer can wait forever without progress"
if grep -Eq -- '--insecure|--skip-tls-verification|api-key: \$QDRANT_API_KEY' "$uploader_path"; then
    fail_uploader_contract_test "uploader weakens TLS or exposes the API key in curl arguments"
fi
grep -Fq 'ConditionPathExists=!%h/.local/state/java-chat/production-qdrant-reconcile.complete' "$reconciler_unit" \
    && grep -Fq -- '--projectId f15c5155-687d-48b5-b736-c3142beb4b79 --env prod' "$reconciler_unit" \
    && grep -Fq 'Restart=on-failure' "$reconciler_unit" \
    && grep -Fq 'TimeoutStartSec=infinity' "$reconciler_unit" \
    && grep -Fxq 'WantedBy=default.target' "$reconciler_unit" \
    || fail_uploader_contract_test "persistent production reconciler is not fail-closed and restartable"

printf 'PASS: Self-hosted Qdrant upload stays secret-safe, checksummed, and completion-gated.\n'
