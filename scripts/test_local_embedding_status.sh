#!/bin/bash

# Verifies local service status reports stable phase, progress, and active source names.

set -euo pipefail

readonly TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_WORK_DIRECTORY="$(mktemp -d)"
readonly TEST_COMMAND_DIRECTORY="$TEST_WORK_DIRECTORY/commands"
readonly TEST_PROJECT_ROOT="$TEST_WORK_DIRECTORY/java-chat"
readonly TEST_HOME_DIRECTORY="$TEST_WORK_DIRECTORY/home"
readonly STATUS_SOURCE_SCRIPT="$TEST_SCRIPT_DIRECTORY/local_embedding_status.sh"
readonly STATUS_SCRIPT="$TEST_PROJECT_ROOT/scripts/local_embedding_status.sh"

cleanup_local_embedding_status_test() {
    rm -rf -- "$TEST_WORK_DIRECTORY"
}
trap cleanup_local_embedding_status_test EXIT

fail_local_embedding_status_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

mkdir -p "$TEST_COMMAND_DIRECTORY" "$TEST_PROJECT_ROOT/scripts" \
    "$TEST_HOME_DIRECTORY/.local/bin" "$TEST_HOME_DIRECTORY/.local/state/java-chat"
cp "$STATUS_SOURCE_SCRIPT" "$STATUS_SCRIPT"
chmod +x "$STATUS_SCRIPT"

cat > "$TEST_COMMAND_DIRECTORY/systemctl" <<'EOF'
#!/bin/bash
set -euo pipefail
if [ "${FAKE_SYSTEMCTL_FAILURE:-false}" = true ]; then
    exit 1
fi
invocation_id="$(sed -n '1p' "$FAKE_INVOCATION_SEQUENCE_FILE")"
sed -n '2,$p' "$FAKE_INVOCATION_SEQUENCE_FILE" > "$FAKE_INVOCATION_SEQUENCE_FILE.next"
mv -- "$FAKE_INVOCATION_SEQUENCE_FILE.next" "$FAKE_INVOCATION_SEQUENCE_FILE"
if [ "$invocation_id" != EMPTY ]; then
    printf '%s\n' "$invocation_id"
fi
EOF

cat > "$TEST_COMMAND_DIRECTORY/journalctl" <<'EOF'
#!/bin/bash
set -euo pipefail
if [ "${FAKE_JOURNAL_FAILURE:-false}" = true ]; then
    exit 2
fi
invocation_id=""
printf '%s\n' "$*" >> "$FAKE_JOURNAL_ARGUMENT_LOG"
for journal_argument in "$@"; do
    case "$journal_argument" in
        _SYSTEMD_INVOCATION_ID=*) invocation_id="${journal_argument#*=}" ;;
    esac
done
cat "$FAKE_JOURNAL_DIRECTORY/$invocation_id"
EOF

cat > "$TEST_COMMAND_DIRECTORY/curl" <<'EOF'
#!/bin/bash
if [ "${FAKE_QDRANT_FAILURE:-false}" = true ]; then
    exit 22
fi
printf '%s\n' '{"result":{"points_count":58367,"indexed_vectors_count":124390,"status":"green"}}'
EOF

cat > "$TEST_COMMAND_DIRECTORY/uname" <<'EOF'
#!/bin/bash
printf 'Linux\n'
EOF

cat > "$TEST_COMMAND_DIRECTORY/stat" <<'EOF'
#!/bin/bash
case "$2" in
    '%Y %s') printf '%s %s\n' "$FAKE_PROCESS_LOG_EPOCH" 123 ;;
    '%Y') printf '%s\n' "$FAKE_PROCESS_LOG_EPOCH" ;;
    *) exit 64 ;;
esac
EOF

cat > "$TEST_COMMAND_DIRECTORY/date" <<'EOF'
#!/bin/bash
if [ "$1" = -d ]; then
    printf '%s\n' "$FAKE_RESUME_EPOCH"
elif [ "$1" = -u ] && [ "$2" = -d ]; then
    printf '2026-08-26T03:00:00Z\n'
elif [ "$1" = -u ]; then
    printf '2026-08-26T03:00:00Z\n'
else
    exit 64
fi
EOF
chmod +x "$TEST_COMMAND_DIRECTORY/systemctl" "$TEST_COMMAND_DIRECTORY/journalctl" \
    "$TEST_COMMAND_DIRECTORY/curl" "$TEST_COMMAND_DIRECTORY/uname" \
    "$TEST_COMMAND_DIRECTORY/stat" "$TEST_COMMAND_DIRECTORY/date"

run_status_case() {
    local scenario_name="$1"
    local invocation_sequence="$2"
    local journal_contents="$3"
    local process_log_contents="$4"
    local expected_phase_line="$5"
    local expected_repository_line="$6"
    local process_log_epoch="${7:-1787713200}"
    local invocation_id="${8:-active-invocation}"
    local status_command="${9:-$STATUS_SCRIPT}"
    local scenario_directory="$TEST_WORK_DIRECTORY/$scenario_name"
    mkdir -p "$scenario_directory/journals"
    printf '%s\n' "$invocation_sequence" > "$scenario_directory/invocations"
    printf '%s\n' "$journal_contents" > "$scenario_directory/journals/$invocation_id"
    printf '%s\n' "$process_log_contents" > "$TEST_PROJECT_ROOT/process_qdrant.log"

    local status_report
    status_report="$(
        HOME="$TEST_HOME_DIRECTORY" \
        FAKE_INVOCATION_SEQUENCE_FILE="$scenario_directory/invocations" \
        FAKE_JOURNAL_DIRECTORY="$scenario_directory/journals" \
        FAKE_JOURNAL_ARGUMENT_LOG="$TEST_WORK_DIRECTORY/journal-arguments" \
        FAKE_JOURNAL_FAILURE=false \
        FAKE_PROCESS_LOG_EPOCH="$process_log_epoch" \
        FAKE_QDRANT_FAILURE=false \
        FAKE_RESUME_EPOCH=1787706000 \
        FAKE_SYSTEMCTL_FAILURE=false \
        PATH="$TEST_COMMAND_DIRECTORY:$PATH" \
        "$status_command"
    )" || fail_local_embedding_status_test "$scenario_name status command failed"
    if ! grep -Fqx "$expected_phase_line" <<< "$status_report"; then
        fail_local_embedding_status_test "$scenario_name phase output was incorrect: $status_report"
    fi
    if ! grep -Fqx "$expected_repository_line" <<< "$status_report"; then
        fail_local_embedding_status_test "$scenario_name repository output was incorrect: $status_report"
    fi
    if grep -Fq 'latest= ' <<< "$status_report"; then
        fail_local_embedding_status_test "$scenario_name rendered a blank source name: $status_report"
    fi
    if grep -Eq '^log_modified=[^ ]+[ ]+log_bytes=[0-9]+$' <<< "$status_report"; then
        :
    else
        fail_local_embedding_status_test "$scenario_name log metadata was not machine parseable: $status_report"
    fi
}

run_status_case \
    documentation-phase \
    $'active-invocation\nactive-invocation' \
    'LOCAL_STAGING_RESUME 2026-08-25T22:47:45Z BATCH_SIZE=8 CONCURRENCY=8 DOCS=25 REPOS=23' \
    $'Qdrant postcondition required for docSet: jooq/3.21/manual\nRouted (docSet=jooq/3.21/api, docType=api-docs)' \
    'phase=documentation documentation=1/25 active_doc_set=jooq/3.21/api' \
    'repositories=0/23 started=0 active_repository=waiting_for_documentation staging_complete=false'

repository_process_log=""
for ((documentation_index = 1; documentation_index <= 25; documentation_index++)); do
    repository_process_log+="Qdrant postcondition required for docSet: docs/$documentation_index"$'\n'
done
run_status_case \
    repository-phase \
    $'active-invocation\nactive-invocation' \
    $'LOCAL_STAGING_RESUME 2026-08-25T22:47:45Z BATCH_SIZE=8 CONCURRENCY=8 DOCS=25 REPOS=23\nREPOSITORY_START 2026-08-26T01:00:00Z data/repos/github/owner/repository-one\nREPOSITORY_COMPLETE 2026-08-26T01:01:00Z data/repos/github/owner/repository-one\nREPOSITORY_START 2026-08-26T01:02:00Z data/repos/github/owner/repository-two' \
    "$repository_process_log" \
    'phase=repositories documentation=25/25 active_doc_set=complete' \
    'repositories=1/23 started=2 active_repository=owner/repository-two staging_complete=false'

ln -s "$STATUS_SCRIPT" "$TEST_HOME_DIRECTORY/.local/bin/java-chat-embedding-status"
run_status_case \
    symlink-entrypoint \
    $'active-invocation\nactive-invocation' \
    'LOCAL_STAGING_RESUME 2026-08-25T22:47:45Z BATCH_SIZE=8 CONCURRENCY=8 DOCS=25 REPOS=23' \
    'Routed (docSet=jooq/3.21/api, docType=api-docs)' \
    'phase=documentation documentation=0/25 active_doc_set=jooq/3.21/api' \
    'repositories=0/23 started=0 active_repository=waiting_for_documentation staging_complete=false' \
    1787713200 \
    active-invocation \
    "$TEST_HOME_DIRECTORY/.local/bin/java-chat-embedding-status"

run_status_case \
    stale-log-after-restart \
    $'active-invocation\nactive-invocation' \
    'LOCAL_STAGING_RESUME 2026-08-26T01:00:00Z BATCH_SIZE=8 CONCURRENCY=8 DOCS=25 REPOS=23' \
    $'Qdrant postcondition required for docSet: stale/complete\nRouted (docSet=stale/active, docType=api-docs)' \
    'phase=documentation documentation=0/25 active_doc_set=initializing' \
    'repositories=0/23 started=0 active_repository=waiting_for_documentation staging_complete=false' \
    1787700000

run_status_case \
    inactive-with-stale-log \
    $'EMPTY\nEMPTY' \
    '' \
    $'Qdrant postcondition required for docSet: stale/complete\nRouted (docSet=stale/active, docType=api-docs)' \
    'phase=inactive documentation=0/0 active_doc_set=none' \
    'repositories=0/0 started=0 active_repository=none staging_complete=false'

race_directory="$TEST_WORK_DIRECTORY/invocation-race"
mkdir -p "$race_directory/journals"
printf '%s\n' old-invocation new-invocation new-invocation new-invocation > "$race_directory/invocations"
printf '%s\n' \
    'LOCAL_STAGING_RESUME 2026-08-25T22:47:45Z BATCH_SIZE=8 CONCURRENCY=8 DOCS=25 REPOS=23' \
    > "$race_directory/journals/old-invocation"
printf '%s\n' \
    'LOCAL_STAGING_RESUME 2026-08-26T01:00:00Z BATCH_SIZE=8 CONCURRENCY=8 DOCS=25 REPOS=23' \
    'REPOSITORY_START 2026-08-26T02:00:00Z data/repos/github/owner/current-repository' \
    > "$race_directory/journals/new-invocation"
printf '%s\n' 'Routed (docSet=docs/complete, docType=api-docs)' > "$TEST_PROJECT_ROOT/process_qdrant.log"
race_status_report="$(
    HOME="$TEST_HOME_DIRECTORY" \
    FAKE_INVOCATION_SEQUENCE_FILE="$race_directory/invocations" \
    FAKE_JOURNAL_DIRECTORY="$race_directory/journals" \
    FAKE_JOURNAL_ARGUMENT_LOG="$TEST_WORK_DIRECTORY/journal-arguments" \
    FAKE_JOURNAL_FAILURE=false \
    FAKE_PROCESS_LOG_EPOCH=1787713200 \
    FAKE_QDRANT_FAILURE=false \
    FAKE_RESUME_EPOCH=1787706000 \
    FAKE_SYSTEMCTL_FAILURE=false \
    PATH="$TEST_COMMAND_DIRECTORY:$PATH" \
    "$STATUS_SCRIPT"
)" || fail_local_embedding_status_test "invocation race status command failed"
if ! grep -Fqx \
    'repositories=0/23 started=1 active_repository=owner/current-repository staging_complete=false' \
    <<< "$race_status_report"; then
    fail_local_embedding_status_test "invocation race mixed staging runs: $race_status_report"
fi

printf '%s\n' EMPTY EMPTY > "$TEST_WORK_DIRECTORY/complete-invocations"
printf '%s\n' complete-invocation > "$TEST_HOME_DIRECTORY/.local/state/java-chat/local-embedding-staging.invocation"
mkdir -p "$TEST_WORK_DIRECTORY/complete-journals"
printf '%s\n' \
    'LOCAL_STAGING_RESUME 2026-08-26T01:00:00Z BATCH_SIZE=8 CONCURRENCY=8 DOCS=1 REPOS=1' \
    'REPOSITORY_START 2026-08-26T02:00:00Z data/repos/github/owner/repository' \
    'REPOSITORY_COMPLETE 2026-08-26T02:01:00Z data/repos/github/owner/repository' \
    'LOCAL_STAGING_COMPLETE 2026-08-26T02:02:00Z' \
    > "$TEST_WORK_DIRECTORY/complete-journals/complete-invocation"
printf '%s\n' 'Qdrant postcondition required for docSet: docs/complete' > "$TEST_PROJECT_ROOT/process_qdrant.log"
complete_status_report="$(
    HOME="$TEST_HOME_DIRECTORY" \
    FAKE_INVOCATION_SEQUENCE_FILE="$TEST_WORK_DIRECTORY/complete-invocations" \
    FAKE_JOURNAL_DIRECTORY="$TEST_WORK_DIRECTORY/complete-journals" \
    FAKE_JOURNAL_ARGUMENT_LOG="$TEST_WORK_DIRECTORY/journal-arguments" \
    FAKE_JOURNAL_FAILURE=false \
    FAKE_PROCESS_LOG_EPOCH=1787713200 \
    FAKE_QDRANT_FAILURE=false \
    FAKE_RESUME_EPOCH=1787706000 \
    FAKE_SYSTEMCTL_FAILURE=false \
    PATH="$TEST_COMMAND_DIRECTORY:$PATH" \
    "$STATUS_SCRIPT"
)" || fail_local_embedding_status_test "completed invocation status command failed"
if ! grep -Fqx 'phase=complete documentation=1/1 active_doc_set=complete' <<< "$complete_status_report" \
    || ! grep -Fqx \
        'repositories=1/1 started=1 active_repository=none staging_complete=true' \
        <<< "$complete_status_report"; then
    fail_local_embedding_status_test "completed invocation output was incorrect: $complete_status_report"
fi

journal_failure_directory="$TEST_WORK_DIRECTORY/journal-failure"
mkdir -p "$journal_failure_directory/journals"
printf '%s\n' active-invocation active-invocation > "$journal_failure_directory/invocations"
printf '%s\n' 'LOCAL_STAGING_RESUME 2026-08-26T01:00:00Z DOCS=1 REPOS=1' \
    > "$journal_failure_directory/journals/active-invocation"
if journal_failure_report="$(
    HOME="$TEST_HOME_DIRECTORY" \
    FAKE_INVOCATION_SEQUENCE_FILE="$journal_failure_directory/invocations" \
    FAKE_JOURNAL_DIRECTORY="$journal_failure_directory/journals" \
    FAKE_JOURNAL_ARGUMENT_LOG="$TEST_WORK_DIRECTORY/journal-arguments" \
    FAKE_JOURNAL_FAILURE=true \
    FAKE_PROCESS_LOG_EPOCH=1787713200 \
    FAKE_QDRANT_FAILURE=false \
    FAKE_RESUME_EPOCH=1787706000 \
    FAKE_SYSTEMCTL_FAILURE=false \
    PATH="$TEST_COMMAND_DIRECTORY:$PATH" \
    "$STATUS_SCRIPT" 2>&1
)"; then
    fail_local_embedding_status_test "journal failure returned success"
fi
if ! grep -Fq 'Could not read local embedding lifecycle markers.' <<< "$journal_failure_report"; then
    fail_local_embedding_status_test "journal failure was reported as inactive: $journal_failure_report"
fi

systemctl_failure_directory="$TEST_WORK_DIRECTORY/systemctl-failure"
mkdir -p "$systemctl_failure_directory/journals"
printf '%s\n' active-invocation active-invocation > "$systemctl_failure_directory/invocations"
if systemctl_failure_report="$(
    HOME="$TEST_HOME_DIRECTORY" \
    FAKE_INVOCATION_SEQUENCE_FILE="$systemctl_failure_directory/invocations" \
    FAKE_JOURNAL_DIRECTORY="$systemctl_failure_directory/journals" \
    FAKE_JOURNAL_ARGUMENT_LOG="$TEST_WORK_DIRECTORY/journal-arguments" \
    FAKE_JOURNAL_FAILURE=false \
    FAKE_PROCESS_LOG_EPOCH=1787713200 \
    FAKE_QDRANT_FAILURE=false \
    FAKE_RESUME_EPOCH=1787706000 \
    FAKE_SYSTEMCTL_FAILURE=true \
    PATH="$TEST_COMMAND_DIRECTORY:$PATH" \
    "$STATUS_SCRIPT" 2>&1
)"; then
    fail_local_embedding_status_test "systemctl failure used the prior invocation receipt"
fi
if ! grep -Fq 'Could not resolve the local embedding service invocation.' <<< "$systemctl_failure_report"; then
    fail_local_embedding_status_test "systemctl failure was reported from stale state: $systemctl_failure_report"
fi

qdrant_failure_directory="$TEST_WORK_DIRECTORY/qdrant-failure"
mkdir -p "$qdrant_failure_directory/journals"
printf '%s\n' active-invocation active-invocation > "$qdrant_failure_directory/invocations"
printf '%s\n' 'LOCAL_STAGING_RESUME 2026-08-26T01:00:00Z DOCS=1 REPOS=1' \
    > "$qdrant_failure_directory/journals/active-invocation"
if qdrant_failure_report="$(
    HOME="$TEST_HOME_DIRECTORY" \
    FAKE_INVOCATION_SEQUENCE_FILE="$qdrant_failure_directory/invocations" \
    FAKE_JOURNAL_DIRECTORY="$qdrant_failure_directory/journals" \
    FAKE_JOURNAL_ARGUMENT_LOG="$TEST_WORK_DIRECTORY/journal-arguments" \
    FAKE_JOURNAL_FAILURE=false \
    FAKE_PROCESS_LOG_EPOCH=1787713200 \
    FAKE_QDRANT_FAILURE=true \
    FAKE_RESUME_EPOCH=1787706000 \
    FAKE_SYSTEMCTL_FAILURE=false \
    PATH="$TEST_COMMAND_DIRECTORY:$PATH" \
    "$STATUS_SCRIPT" 2>&1
)"; then
    fail_local_embedding_status_test "Qdrant failure returned success"
fi
if ! grep -Fqx 'latest=docSet=initializing qdrant=unavailable' <<< "$qdrant_failure_report" \
    || ! grep -Fqx 'phase=documentation documentation=1/1 active_doc_set=initializing' \
        <<< "$qdrant_failure_report"; then
    fail_local_embedding_status_test "Qdrant failure merged status lines: $qdrant_failure_report"
fi

if ! grep -Fq -- '--grep=^(LOCAL_STAGING_RESUME|REPOSITORY_START|REPOSITORY_COMPLETE|LOCAL_STAGING_COMPLETE)( |$)' \
    "$TEST_WORK_DIRECTORY/journal-arguments"; then
    fail_local_embedding_status_test "status command read the unfiltered ingestion journal"
fi

printf 'PASS: local embedding status reports stable phase, numeric progress, and active repository identity.\n'
