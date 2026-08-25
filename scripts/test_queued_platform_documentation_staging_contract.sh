#!/bin/bash

# Verifies the queued platform-documentation job stays behind the current sole writer.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
queued_launcher="$TEST_SCRIPT_DIRECTORY/run_queued_platform_documentation_staging.sh"
local_unit="$TEST_SCRIPT_DIRECTORY/../infra/systemd/user/java-chat-local-embedding-staging.service"

fail_queued_platform_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

bash -n "$queued_launcher"
grep -Fq 'ConditionPathExists=!%h/.local/state/java-chat/local-embedding-staging.complete' "$local_unit" \
    || fail_queued_platform_test "completed local backlog can restart on a later login"
grep -Fq 'ExecStartPre=/usr/bin/mkdir -p %h/.local/state/java-chat' "$local_unit" \
    || fail_queued_platform_test "local backlog does not create its completion-marker parent"
grep -Fq '&& /usr/bin/touch %h/.local/state/java-chat/local-embedding-staging.complete' "$local_unit" \
    || fail_queued_platform_test "successful local backlog does not record terminal completion"
if grep -Fq 'ExecStartPost=' "$local_unit"; then
    fail_queued_platform_test "local backlog can be marked complete immediately after launch"
fi
if grep -Fq 'date -Ins' "$queued_launcher" \
    || ! grep -Fq "date -u '+%Y-%m-%dT%H:%M:%SZ'" "$queued_launcher"; then
    fail_queued_platform_test "queued job does not use portable UTC timestamps"
fi

grep -Fxq 'readonly QUEUED_DOCUMENTATION_SETS="porkbun,porkbun-mcp,cloudflare"' "$queued_launcher" \
    || fail_queued_platform_test "queued documentation set order changed"
grep -Fxq 'export QDRANT_HOST=127.0.0.1' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not force loopback Qdrant"
grep -Fxq 'export QDRANT_API_KEY=' "$queued_launcher" \
    || fail_queued_platform_test "queued job can inherit a cloud Qdrant credential"
grep -Fq '"_SYSTEMD_INVOCATION_ID=$EXPECTED_STAGING_INVOCATION_ID"' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not scope completion to one systemd invocation"
grep -Fq "expected_staging_invocation_journal | grep -Fq 'LOCAL_STAGING_COMPLETE'" "$queued_launcher" \
    || fail_queued_platform_test "queued job does not require its predecessor completion marker"
grep -Fq 'acquire_qdrant_writer_lease' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not claim the shared Qdrant writer lease"
grep -Fq './scripts/fetch_all_docs.sh --doc-sets="$QUEUED_DOCUMENTATION_SETS"' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not refresh and validate its exact source inventory"
grep -Fq './scripts/process_all_to_qdrant.sh --doc-sets="$QUEUED_DOCUMENTATION_SETS"' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not invoke the targeted sole-writer entrypoint"

completion_gate_line="$(grep -n "expected_staging_invocation_journal | grep -Fq 'LOCAL_STAGING_COMPLETE'" "$queued_launcher" | tail -1 | cut -d: -f1)"
writer_lease_line="$(grep -n '^acquire_qdrant_writer_lease$' "$queued_launcher" | cut -d: -f1)"
source_refresh_line="$(grep -n './scripts/fetch_all_docs.sh --doc-sets=' "$queued_launcher" | cut -d: -f1)"
ingestion_start_line="$(grep -n './scripts/process_all_to_qdrant.sh --doc-sets=' "$queued_launcher" | cut -d: -f1)"
if [ "$completion_gate_line" -ge "$writer_lease_line" ] \
    || [ "$writer_lease_line" -ge "$source_refresh_line" ] \
    || [ "$source_refresh_line" -ge "$ingestion_start_line" ]; then
    fail_queued_platform_test "queued ingestion can start before the current completion gate"
fi

printf 'PASS: Porkbun and Cloudflare remain queued behind the active sole writer.\n'
