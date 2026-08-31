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

queued_launcher_line() {
    local expected_text="$1"
    local matching_lines
    matching_lines="$(grep -Fn "$expected_text" "$queued_launcher" || true)"
    if [ "$(printf '%s\n' "$matching_lines" | sed '/^$/d' | wc -l | tr -d ' ')" -ne 1 ]; then
        fail_queued_platform_test "queued launcher must contain exactly one: $expected_text"
    fi
    printf '%s\n' "${matching_lines%%:*}"
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
grep -Fq 'ConditionPathExists=!%h/.local/state/java-chat/queued-platform-documentation-staging.attempted' "$queued_unit" \
    || fail_queued_platform_test "an already-enabled documentation queue can automatically repeat a failed attempt"
grep -Fq 'ConditionPathExists=%h/.local/state/java-chat/local-embedding-staging.invocation' "$queued_unit" \
    || fail_queued_platform_test "documentation queue can start without durable predecessor proof"
grep -Fq 'invocation_id=$(systemctl --user show java-chat-local-embedding-staging.service --property=InvocationID --value)' "$queued_unit" \
    && grep -Fq 'if [ -z "$invocation_id" ]; then invocation_id=$(<%h/.local/state/java-chat/local-embedding-staging.invocation); fi' "$queued_unit" \
    && grep -Fq -- '--after-invocation=$invocation_id' "$queued_unit" \
    || fail_queued_platform_test "documentation queue does not select its live or completed predecessor invocation"
grep -Fq 'ExecStartPost=/usr/bin/touch %h/.local/state/java-chat/queued-platform-documentation-staging.complete' "$queued_unit" \
    || fail_queued_platform_test "successful documentation queue does not record terminal completion"
if grep -Eq '^Restart=|^RestartSec=' "$queued_unit"; then
    fail_queued_platform_test "failed documentation queue can automatically restart completed source fetches"
fi
if grep -Fq 'WantedBy=' "$queued_unit"; then
    fail_queued_platform_test "failed documentation queue can automatically restart with the user manager"
fi
if grep -Fq 'date -Ins' "$queued_launcher" \
    || ! grep -Fq "date -u '+%Y-%m-%dT%H:%M:%SZ'" "$queued_launcher"; then
    fail_queued_platform_test "queued job does not use portable UTC timestamps"
fi

grep -Fxq 'readonly QUEUED_DOCUMENTATION_SOURCES="porkbun,porkbun-mcp,cloudflare,dev-java,kotlin,scala,groovy,clojure,spring-boot,quarkus,java/java21-complete,java/java25-complete,spring-ai-reference,spring-ai-api-stable,spring-framework-reference,spring-framework-api,oracle-java25-release-notes,ibm-java25-overview,jetbrains-java25-article"' "$queued_launcher" \
    || fail_queued_platform_test "remaining documentation fetch inventory or order changed"
if grep -Fq 'QUEUED_DOCUMENTATION_SETS' "$queued_launcher"; then
    fail_queued_platform_test "documentation queue duplicates the canonical ingestion registry"
fi
grep -Fxq 'readonly FINAL_JAVA_DOCUMENTATION_SOURCE="java/java26-complete"' "$queued_launcher" \
    || fail_queued_platform_test "Java 26 is not sequenced after the current documentation backlog"
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
grep -Fq './scripts/process_all_to_qdrant.sh --doc-sets="$QUEUED_DOCUMENTATION_SOURCES"' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not process its exact current documentation inventory"
grep -Fq './scripts/fetch_all_docs.sh --doc-sets="$FINAL_JAVA_DOCUMENTATION_SOURCE"' "$queued_launcher" \
    && grep -Fq './scripts/process_all_to_qdrant.sh --doc-sets="$FINAL_JAVA_DOCUMENTATION_SOURCE"' "$queued_launcher" \
    || fail_queued_platform_test "queued job does not fetch and process Java 26 last"

completion_gate_line="$(queued_launcher_line "expected_staging_invocation_journal | grep -Fq 'LOCAL_STAGING_COMPLETE'")"
invocation_receipt_line="$(queued_launcher_line 'mv -- "$STAGING_INVOCATION_RECEIPT.next" "$STAGING_INVOCATION_RECEIPT"')"
attempt_receipt_line="$(queued_launcher_line 'touch "$QUEUED_STAGING_ATTEMPT_RECEIPT"')"
writer_lease_line="$(queued_launcher_line 'acquire_qdrant_writer_lease')"
source_refresh_line="$(queued_launcher_line './scripts/fetch_all_docs.sh --doc-sets="$QUEUED_DOCUMENTATION_SOURCES"')"
ingestion_start_line="$(queued_launcher_line './scripts/process_all_to_qdrant.sh --doc-sets="$QUEUED_DOCUMENTATION_SOURCES"')"
java26_fetch_line="$(queued_launcher_line './scripts/fetch_all_docs.sh --doc-sets="$FINAL_JAVA_DOCUMENTATION_SOURCE"')"
java26_ingestion_line="$(queued_launcher_line './scripts/process_all_to_qdrant.sh --doc-sets="$FINAL_JAVA_DOCUMENTATION_SOURCE"')"
if [ "$completion_gate_line" -ge "$invocation_receipt_line" ] \
    || [ "$invocation_receipt_line" -ge "$attempt_receipt_line" ] \
    || [ "$attempt_receipt_line" -ge "$source_refresh_line" ] \
    || [ "$source_refresh_line" -ge "$writer_lease_line" ] \
    || [ "$writer_lease_line" -ge "$ingestion_start_line" ] \
    || [ "$ingestion_start_line" -ge "$java26_fetch_line" ] \
    || [ "$java26_fetch_line" -ge "$java26_ingestion_line" ]; then
    fail_queued_platform_test "queued ingestion can start before the current completion gate"
fi

printf 'PASS: Remaining documentation stays queued behind the active sole writer.\n'
