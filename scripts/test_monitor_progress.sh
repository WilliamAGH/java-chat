#!/bin/bash

# Verifies embedding status reports explicit phase, numeric progress, and active source names.

set -euo pipefail

readonly TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_WORK_DIRECTORY="$(mktemp -d)"
readonly TEST_COMMAND_DIRECTORY="$TEST_WORK_DIRECTORY/commands"
readonly TEST_PROJECT_ROOT="$TEST_WORK_DIRECTORY/java-chat"
readonly TEST_HOME_DIRECTORY="$TEST_WORK_DIRECTORY/home"
readonly STATUS_SCRIPT="$TEST_SCRIPT_DIRECTORY/monitor_progress.sh"

cleanup_monitor_progress_test() {
    rm -rf -- "$TEST_WORK_DIRECTORY"
}
trap cleanup_monitor_progress_test EXIT

fail_monitor_progress_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

mkdir -p "$TEST_COMMAND_DIRECTORY" "$TEST_PROJECT_ROOT" \
    "$TEST_HOME_DIRECTORY/.local/bin" "$TEST_HOME_DIRECTORY/.local/state/java-chat"

cat > "$TEST_COMMAND_DIRECTORY/systemctl" <<'EOF'
#!/bin/bash
set -euo pipefail
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
printf '%s\n' '{"result":{"points_count":58367,"indexed_vectors_count":124390,"status":"green"}}'
EOF
chmod +x "$TEST_COMMAND_DIRECTORY/systemctl" "$TEST_COMMAND_DIRECTORY/journalctl" "$TEST_COMMAND_DIRECTORY/curl"

run_status_case() {
    local scenario_name="$1"
    local invocation_sequence="$2"
    local journal_contents="$3"
    local process_log_contents="$4"
    local expected_phase_line="$5"
    local expected_repository_line="$6"
    local invocation_id="${7:-active-invocation}"
    local status_command="${8:-$STATUS_SCRIPT}"
    local scenario_directory="$TEST_WORK_DIRECTORY/$scenario_name"
    mkdir -p "$scenario_directory/journals"
    printf '%s\n' "$invocation_sequence" > "$scenario_directory/invocations"
    printf '%s\n' "$journal_contents" > "$scenario_directory/journals/$invocation_id"
    printf '%s\n' "$process_log_contents" > "$TEST_PROJECT_ROOT/process_qdrant.log"

    local status_report
    status_report="$(
        HOME="$TEST_HOME_DIRECTORY" \
        JAVA_CHAT_PROJECT_ROOT="$TEST_PROJECT_ROOT" \
        FAKE_INVOCATION_SEQUENCE_FILE="$scenario_directory/invocations" \
        FAKE_JOURNAL_DIRECTORY="$scenario_directory/journals" \
        FAKE_JOURNAL_ARGUMENT_LOG="$TEST_WORK_DIRECTORY/journal-arguments" \
        PATH="$TEST_COMMAND_DIRECTORY:$PATH" \
        "$status_command"
    )" || fail_monitor_progress_test "$scenario_name status command failed"
    if ! grep -Fqx "$expected_phase_line" <<< "$status_report"; then
        fail_monitor_progress_test "$scenario_name phase output was incorrect: $status_report"
    fi
    if ! grep -Fqx "$expected_repository_line" <<< "$status_report"; then
        fail_monitor_progress_test "$scenario_name repository output was incorrect: $status_report"
    fi
    if grep -Fq 'latest= ' <<< "$status_report"; then
        fail_monitor_progress_test "$scenario_name rendered a blank source name: $status_report"
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
    active-invocation \
    "$TEST_HOME_DIRECTORY/.local/bin/java-chat-embedding-status"

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
    JAVA_CHAT_PROJECT_ROOT="$TEST_PROJECT_ROOT" \
    FAKE_INVOCATION_SEQUENCE_FILE="$race_directory/invocations" \
    FAKE_JOURNAL_DIRECTORY="$race_directory/journals" \
    FAKE_JOURNAL_ARGUMENT_LOG="$TEST_WORK_DIRECTORY/journal-arguments" \
    PATH="$TEST_COMMAND_DIRECTORY:$PATH" \
    "$STATUS_SCRIPT"
)" || fail_monitor_progress_test "invocation race status command failed"
if ! grep -Fqx \
    'repositories=0/23 started=1 active_repository=owner/current-repository staging_complete=false' \
    <<< "$race_status_report"; then
    fail_monitor_progress_test "invocation race mixed staging runs: $race_status_report"
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
    JAVA_CHAT_PROJECT_ROOT="$TEST_PROJECT_ROOT" \
    FAKE_INVOCATION_SEQUENCE_FILE="$TEST_WORK_DIRECTORY/complete-invocations" \
    FAKE_JOURNAL_DIRECTORY="$TEST_WORK_DIRECTORY/complete-journals" \
    FAKE_JOURNAL_ARGUMENT_LOG="$TEST_WORK_DIRECTORY/journal-arguments" \
    PATH="$TEST_COMMAND_DIRECTORY:$PATH" \
    "$STATUS_SCRIPT"
)" || fail_monitor_progress_test "completed invocation status command failed"
if ! grep -Fqx 'phase=complete documentation=1/1 active_doc_set=complete' <<< "$complete_status_report" \
    || ! grep -Fqx \
        'repositories=1/1 started=1 active_repository=none staging_complete=true' \
        <<< "$complete_status_report"; then
    fail_monitor_progress_test "completed invocation output was incorrect: $complete_status_report"
fi
if ! grep -Fq -- '--grep=^(LOCAL_STAGING_RESUME|REPOSITORY_START|REPOSITORY_COMPLETE|LOCAL_STAGING_COMPLETE)( |$)' \
    "$TEST_WORK_DIRECTORY/journal-arguments"; then
    fail_monitor_progress_test "status command read the unfiltered ingestion journal"
fi

printf 'PASS: embedding status reports stable phase, numeric progress, and active repository identity.\n'
