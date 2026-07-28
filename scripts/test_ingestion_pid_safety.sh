#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PROJECT_ROOT
SCRIPT_DIR="$PROJECT_ROOT/scripts"
TEST_WORK_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/java-chat-pid-safety.XXXXXX")"
trap 'rm -rf -- "$TEST_WORK_DIRECTORY"' EXIT

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIR/lib/common_qdrant.sh"

fail_ingestion_pid_safety_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

existing_pid_file="$TEST_WORK_DIRECTORY/process_qdrant.pid"
signal_attempt_log="$TEST_WORK_DIRECTORY/signal-attempts.log"
printf '%s\n' '4242' > "$existing_pid_file"

kill() {
    printf '%s\n' "$*" >> "$signal_attempt_log"
    return 0
}

if setup_pid_and_cleanup "$existing_pid_file" 2>/dev/null; then
    fail_ingestion_pid_safety_test "An existing PID file did not stop the new ingestion run."
fi

if [[ ! -f "$existing_pid_file" ]]; then
    fail_ingestion_pid_safety_test "The existing PID file was deleted."
fi

if [[ "$(cat "$existing_pid_file")" != "4242" ]]; then
    fail_ingestion_pid_safety_test "The existing PID file was modified."
fi

if [[ -s "$signal_attempt_log" ]]; then
    fail_ingestion_pid_safety_test "The existing PID triggered a signal attempt."
fi

new_pid_file="$TEST_WORK_DIRECTORY/new-process_qdrant.pid"
setup_pid_and_cleanup "$new_pid_file"
if [[ "${COMMON_PID_FILE:-}" != "$new_pid_file" ]]; then
    fail_ingestion_pid_safety_test "A new ingestion run did not claim its PID file path."
fi
if [[ ! -f "$new_pid_file" ]]; then
    fail_ingestion_pid_safety_test "A new ingestion run did not atomically reserve its PID file."
fi
rm -f -- "$new_pid_file"

concurrent_pid_file="$TEST_WORK_DIRECTORY/concurrent-process_qdrant.pid"
claimant_log="$TEST_WORK_DIRECTORY/claimants.log"
claim_release_file="$TEST_WORK_DIRECTORY/release-claim"
: > "$claimant_log"
claimant_process_ids=()
for claimant_number in {1..8}; do
    (
        if setup_pid_and_cleanup "$concurrent_pid_file" 2>/dev/null; then
            printf '%s\n' "$claimant_number" >> "$claimant_log"
            while [[ ! -f "$claim_release_file" ]]; do
                sleep 0.01
            done
            rm -f -- "$concurrent_pid_file"
        fi
    ) &
    claimant_process_ids+=("$!")
done

for ((claim_observation_attempt = 0; claim_observation_attempt < 100; claim_observation_attempt++)); do
    if [[ -s "$claimant_log" ]]; then
        break
    fi
    sleep 0.01
done
sleep 0.05

claimant_count="$(wc -l < "$claimant_log" | tr -d '[:space:]')"
if [[ "$claimant_count" != "1" ]]; then
    touch "$claim_release_file"
    wait "${claimant_process_ids[@]}"
    fail_ingestion_pid_safety_test "Expected exactly one concurrent PID-file claimant, observed $claimant_count."
fi

touch "$claim_release_file"
wait "${claimant_process_ids[@]}"

printf 'PASS: ingestion PID files fail closed without signaling or replacing another run.\n'
