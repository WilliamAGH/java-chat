#!/bin/bash

# Verifies the queued platform-documentation job stays behind the current sole writer.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
queued_launcher="$TEST_SCRIPT_DIRECTORY/run_queued_platform_documentation_staging.sh"
local_unit="$TEST_SCRIPT_DIRECTORY/../infra/systemd/user/java-chat-local-embedding-staging.service"
queued_unit="$TEST_SCRIPT_DIRECTORY/../infra/systemd/user/java-chat-queued-platform-documentation-staging.service"

fail_queued_platform_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

bash -n "$queued_launcher"
grep -Fq 'ConditionPathExists=!%h/.local/state/java-chat/local-embedding-staging.complete' "$local_unit" \
    || fail_queued_platform_test "completed local backlog can restart on a later login"
grep -Fq 'ExecStartPre=/usr/bin/mkdir -p %h/.local/state/java-chat' "$local_unit" \
    || fail_queued_platform_test "local backlog does not create its completion-marker parent"
grep -Fq 'ExecStartPre=/usr/bin/rm -f %h/.local/state/java-chat/local-embedding-staging.invocation %h/.local/state/java-chat/local-embedding-staging.invocation.next' "$local_unit" \
    || fail_queued_platform_test "local backlog does not delete stale predecessor proof before a new run"
grep -Fq '/usr/bin/mv -- %h/.local/state/java-chat/local-embedding-staging.invocation.next %h/.local/state/java-chat/local-embedding-staging.invocation' "$local_unit" \
    || fail_queued_platform_test "local backlog does not publish predecessor proof atomically"
grep -Fq '&& /usr/bin/touch %h/.local/state/java-chat/local-embedding-staging.complete' "$local_unit" \
    || fail_queued_platform_test "successful local backlog does not record terminal completion"
if grep -Fq 'ExecStartPost=' "$local_unit"; then
    fail_queued_platform_test "local backlog can be marked complete immediately after launch"
fi
grep -Fxq 'OnSuccess=java-chat-queued-platform-documentation-staging.service' "$local_unit" \
    || fail_queued_platform_test "successful local backlog does not activate the documentation queue"
grep -Fxq 'After=java-chat-local-embedding-staging.service' "$queued_unit" \
    || fail_queued_platform_test "documentation queue is not ordered after the local backlog"
grep -Fq 'ConditionPathExists=!%h/.local/state/java-chat/queued-platform-documentation-staging.complete' "$queued_unit" \
    || fail_queued_platform_test "completed documentation queue can restart on a later login"
grep -Fq 'ConditionPathExists=%h/.local/state/java-chat/local-embedding-staging.invocation' "$queued_unit" \
    || fail_queued_platform_test "documentation queue can start without durable predecessor proof"
grep -Fq 'invocation_id=$(<%h/.local/state/java-chat/local-embedding-staging.invocation)' "$queued_unit" \
    && grep -Fq -- '--after-invocation=$invocation_id' "$queued_unit" \
    || fail_queued_platform_test "documentation queue does not restore its predecessor invocation"
grep -Fq 'ExecStartPost=/usr/bin/touch %h/.local/state/java-chat/queued-platform-documentation-staging.complete' "$queued_unit" \
    || fail_queued_platform_test "successful documentation queue does not record terminal completion"
if grep -Fq 'date -Ins' "$queued_launcher" \
    || ! grep -Fq "date -u '+%Y-%m-%dT%H:%M:%SZ'" "$queued_launcher"; then
    fail_queued_platform_test "queued job does not use portable UTC timestamps"
fi

grep -Fxq 'readonly QUEUED_DOCUMENTATION_SOURCES="porkbun,porkbun-mcp,cloudflare,dev-java,kotlin,scala,groovy,clojure,spring-boot,quarkus,java/java21-complete,java/java24-complete,java/java25-complete,spring-ai-reference,spring-ai-api-stable,spring-framework-reference,spring-framework-api,oracle-java25-release-notes,ibm-java25-overview,jetbrains-java25-article"' "$queued_launcher" \
    || fail_queued_platform_test "remaining documentation fetch inventory or order changed"
grep -Fxq 'readonly QUEUED_DOCUMENTATION_SETS="porkbun,porkbun-mcp,cloudflare,dev-java,kotlin,scala,groovy/5.0.7,clojure,spring-boot,quarkus,java/java21-complete,java/java24-complete,java/java25-complete,spring-ai-reference,spring-ai-api-stable,spring-framework-reference,spring-framework-api,oracle/javase,ibm/articles,jetbrains/idea/2025/09"' "$queued_launcher" \
    || fail_queued_platform_test "remaining documentation ingestion inventory or order changed"
grep -Fxq 'export QDRANT_HOST=127.0.0.1' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not force loopback Qdrant"
grep -Fxq 'export QDRANT_API_KEY=' "$queued_launcher" \
    || fail_queued_platform_test "queued job can inherit a cloud Qdrant credential"
grep -Fq '"_SYSTEMD_INVOCATION_ID=$EXPECTED_STAGING_INVOCATION_ID"' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not scope completion to one systemd invocation"
grep -Fq "expected_staging_invocation_journal | grep -Fq 'LOCAL_STAGING_COMPLETE'" "$queued_launcher" \
    || fail_queued_platform_test "queued job does not require its predecessor completion marker"
grep -Fq 'mv -- "$STAGING_INVOCATION_RECEIPT.next" "$STAGING_INVOCATION_RECEIPT"' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not preserve verified predecessor proof across restarts"
grep -Fq 'if [ -n "$current_staging_invocation_id" ]' "$queued_launcher" \
    || fail_queued_platform_test "queued job requires volatile predecessor state after a manager restart"
if grep -Fq 'predecessor_result=' "$queued_launcher" \
    || grep -Fq 'predecessor_exit_status=' "$queued_launcher"; then
    fail_queued_platform_test "queued job still depends on volatile predecessor results"
fi
grep -Fq 'acquire_qdrant_writer_lease' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not claim the shared Qdrant writer lease"
grep -Fq './scripts/fetch_all_docs.sh --doc-sets="$QUEUED_DOCUMENTATION_SOURCES"' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not refresh and validate its exact source inventory"
grep -Fq './scripts/process_all_to_qdrant.sh --doc-sets="$QUEUED_DOCUMENTATION_SETS"' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not invoke the targeted sole-writer entrypoint"

completion_gate_line="$(grep -n "expected_staging_invocation_journal | grep -Fq 'LOCAL_STAGING_COMPLETE'" "$queued_launcher" | tail -1 | cut -d: -f1)"
invocation_receipt_line="$(grep -n '^mv -- "$STAGING_INVOCATION_RECEIPT.next"' "$queued_launcher" | cut -d: -f1)"
writer_lease_line="$(grep -n '^acquire_qdrant_writer_lease$' "$queued_launcher" | cut -d: -f1)"
source_refresh_line="$(grep -n './scripts/fetch_all_docs.sh --doc-sets=' "$queued_launcher" | cut -d: -f1)"
ingestion_start_line="$(grep -n './scripts/process_all_to_qdrant.sh --doc-sets=' "$queued_launcher" | cut -d: -f1)"
if [ "$completion_gate_line" -ge "$invocation_receipt_line" ] \
    || [ "$invocation_receipt_line" -ge "$source_refresh_line" ] \
    || [ "$source_refresh_line" -ge "$writer_lease_line" ] \
    || [ "$writer_lease_line" -ge "$ingestion_start_line" ]; then
    fail_queued_platform_test "queued ingestion can start before the current completion gate"
fi

printf 'PASS: Remaining documentation stays queued behind the active sole writer.\n'
