#!/bin/bash

# Proves the Python-backed writer lease across Bash 3.2 and current Linux Bash.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_PROJECT_ROOT="$(cd "$TEST_SCRIPT_DIRECTORY/.." && pwd)"
TEST_WORK_DIRECTORY="$(mktemp -d)"
BACKGROUND_PROCESS_IDENTIFIERS=()

cleanup_writer_lease_test() {
    find "$TEST_WORK_DIRECTORY" -type f -name release -exec touch {} \; 2>/dev/null || true
    for background_process_identifier in "${BACKGROUND_PROCESS_IDENTIFIERS[@]}"; do
        kill "$background_process_identifier" 2>/dev/null || true
        wait "$background_process_identifier" 2>/dev/null || true
    done
    rm -rf -- "$TEST_WORK_DIRECTORY"
}

acquire_writer_lease_in_clean_shell() {
    local writer_lease_root="$1"
    bash --noprofile --norc -c '
        source "$1/scripts/lib/common_qdrant.sh"
        acquire_qdrant_writer_lease_at "$2"
    ' bash "$TEST_PROJECT_ROOT" "$writer_lease_root"
}
trap cleanup_writer_lease_test EXIT

fail_writer_lease_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

wait_for_lease_signal() {
    local lease_signal_file="$1"
    local lease_signal_poll=0
    while [ "$lease_signal_poll" -lt 200 ]; do
        if [ -e "$lease_signal_file" ]; then
            return 0
        fi
        lease_signal_poll=$((lease_signal_poll + 1))
        sleep 0.05
    done
    fail_writer_lease_test "timed out waiting for lease test signal: $lease_signal_file"
}

wait_for_lease_attempts() {
    local lease_attempts_file="$1"
    local expected_attempt_count="$2"
    local lease_attempt_poll=0
    local observed_attempt_count
    while [ "$lease_attempt_poll" -lt 200 ]; do
        observed_attempt_count="$(wc -l < "$lease_attempts_file" 2>/dev/null || printf '0\n')"
        observed_attempt_count="$(printf '%s' "$observed_attempt_count" | tr -d ' ')"
        if [ "$observed_attempt_count" -eq "$expected_attempt_count" ]; then
            return 0
        fi
        lease_attempt_poll=$((lease_attempt_poll + 1))
        sleep 0.05
    done
    fail_writer_lease_test "timed out waiting for $expected_attempt_count parallel lease attempts"
}

wait_for_writer_lease_release() {
    local writer_lease_root="$1"
    local writer_lease_release_poll=0
    while [ "$writer_lease_release_poll" -lt 200 ]; do
        if acquire_writer_lease_in_clean_shell "$writer_lease_root" >/dev/null 2>&1; then
            return 0
        fi
        writer_lease_release_poll=$((writer_lease_release_poll + 1))
        sleep 0.05
    done
    fail_writer_lease_test "timed out waiting for the final writer lease descriptor to close"
}

start_lease_owner() {
    local writer_lease_root="$1"
    local owner_ready_file="$2"
    local owner_release_file="$3"
    bash --noprofile --norc -c '
        set -euo pipefail
        source "$1/scripts/lib/common_qdrant.sh"
        acquire_qdrant_writer_lease_at "$2"
        bash --noprofile --norc -c '\''
            set -euo pipefail
            source "$1/scripts/lib/common_qdrant.sh"
            acquire_qdrant_writer_lease_at "$2"
        '\'' bash "$1" "$2"
        : > "$3"
        while [ ! -e "$4" ]; do sleep 0.1; done
    ' bash "$TEST_PROJECT_ROOT" "$writer_lease_root" "$owner_ready_file" "$owner_release_file" &
    LEASE_OWNER_PROCESS_IDENTIFIER=$!
    BACKGROUND_PROCESS_IDENTIFIERS+=("$LEASE_OWNER_PROCESS_IDENTIFIER")
}

writer_lease_root="$TEST_WORK_DIRECTORY/normal"
mkdir "$writer_lease_root"
owner_ready_file="$writer_lease_root/ready"
owner_release_file="$writer_lease_root/release"
start_lease_owner "$writer_lease_root" "$owner_ready_file" "$owner_release_file"
wait_for_lease_signal "$owner_ready_file"

if acquire_writer_lease_in_clean_shell "$writer_lease_root" >/dev/null 2>&1; then
    fail_writer_lease_test "independent writer entered while the lease was held"
fi

unrelated_descriptor_file="$writer_lease_root/unrelated"
: > "$unrelated_descriptor_file"
if QDRANT_WRITER_LEASE_DESCRIPTOR=9 \
    bash --noprofile --norc -c '
        exec 9>> "$3"
        source "$1/scripts/lib/common_qdrant.sh"
        acquire_qdrant_writer_lease_at "$2"
    ' bash "$TEST_PROJECT_ROOT" "$writer_lease_root" "$unrelated_descriptor_file" \
    >/dev/null 2>&1; then
    fail_writer_lease_test "forged inherited descriptor was accepted"
fi

: > "$owner_release_file"
wait "$LEASE_OWNER_PROCESS_IDENTIFIER"

if ! acquire_writer_lease_in_clean_shell "$writer_lease_root"; then
    fail_writer_lease_test "lease was not released after its final owner exited"
fi

preopened_descriptor_file="$writer_lease_root/preopened"
: > "$preopened_descriptor_file"
if bash --noprofile --norc -c '
    exec 9>> "$3"
    source "$1/scripts/lib/common_qdrant.sh"
    acquire_qdrant_writer_lease_at "$2"
' bash "$TEST_PROJECT_ROOT" "$writer_lease_root" "$preopened_descriptor_file" \
    >/dev/null 2>&1; then
    fail_writer_lease_test "fresh acquisition clobbered an unrelated descriptor 9"
fi

legacy_inheritance_root="$TEST_WORK_DIRECTORY/legacy-inheritance"
mkdir "$legacy_inheritance_root"
if ! bash --noprofile --norc -c '
    set -euo pipefail
    exec 8>> "$2/qdrant-writer.lock"
    python3 "$1/scripts/qdrant_writer_lease.py" \
        --lock-path "$2/qdrant-writer.lock" \
        --descriptor 8
    export QDRANT_WRITER_LEASE_DESCRIPTOR=8
    bash --noprofile --norc -c '\''
        source "$1/scripts/lib/common_qdrant.sh"
        acquire_qdrant_writer_lease_at "$2"
    '\'' bash "$1" "$2"
' bash "$TEST_PROJECT_ROOT" "$legacy_inheritance_root"; then
    fail_writer_lease_test "validated inherited descriptor transition was rejected"
fi

parallel_lease_root="$TEST_WORK_DIRECTORY/parallel"
mkdir "$parallel_lease_root"
parallel_start_file="$parallel_lease_root/start"
parallel_release_file="$parallel_lease_root/release"
parallel_winners_file="$parallel_lease_root/winners"
parallel_attempts_file="$parallel_lease_root/attempts"
: > "$parallel_attempts_file"
parallel_process_identifiers=()
for contender_index in 1 2 3 4 5 6 7 8; do
    bash --noprofile --norc -c '
        while [ ! -e "$3" ]; do sleep 0.05; done
        source "$1/scripts/lib/common_qdrant.sh"
        if acquire_qdrant_writer_lease_at "$2" >/dev/null 2>&1; then
            printf "%s\n" "$$" >> "$4"
            printf "%s\n" "$$" >> "$6"
            while [ ! -e "$5" ]; do sleep 0.1; done
        else
            printf "%s\n" "$$" >> "$6"
        fi
    ' bash "$TEST_PROJECT_ROOT" "$parallel_lease_root" "$parallel_start_file" \
        "$parallel_winners_file" "$parallel_release_file" "$parallel_attempts_file" &
    contender_process_identifier=$!
    parallel_process_identifiers+=("$contender_process_identifier")
    BACKGROUND_PROCESS_IDENTIFIERS+=("$contender_process_identifier")
done
: > "$parallel_start_file"
wait_for_lease_signal "$parallel_winners_file"
wait_for_lease_attempts "$parallel_attempts_file" 8
parallel_winner_count="$(wc -l < "$parallel_winners_file" | tr -d ' ')"
if [ "$parallel_winner_count" -ne 1 ]; then
    fail_writer_lease_test "parallel acquisition admitted $parallel_winner_count writers"
fi
: > "$parallel_release_file"
for contender_process_identifier in "${parallel_process_identifiers[@]}"; do
    wait "$contender_process_identifier"
done

crash_lease_root="$TEST_WORK_DIRECTORY/crash"
mkdir "$crash_lease_root"
crash_owner_ready_file="$crash_lease_root/ready"
crash_descendant_identifier_file="$crash_lease_root/descendant"
bash --noprofile --norc -c '
    set -euo pipefail
    source "$1/scripts/lib/common_qdrant.sh"
    acquire_qdrant_writer_lease_at "$2"
    sleep 300 &
    printf "%s\n" "$!" > "$3"
    : > "$4"
    wait
' bash "$TEST_PROJECT_ROOT" "$crash_lease_root" "$crash_descendant_identifier_file" \
    "$crash_owner_ready_file" &
crash_owner_process_identifier=$!
BACKGROUND_PROCESS_IDENTIFIERS+=("$crash_owner_process_identifier")
wait_for_lease_signal "$crash_owner_ready_file"
crash_descendant_process_identifier="$(cat "$crash_descendant_identifier_file")"
BACKGROUND_PROCESS_IDENTIFIERS+=("$crash_descendant_process_identifier")
kill -KILL "$crash_owner_process_identifier"
wait "$crash_owner_process_identifier" 2>/dev/null || true
if acquire_writer_lease_in_clean_shell "$crash_lease_root" >/dev/null 2>&1; then
    fail_writer_lease_test "parent crash released a lease still held by its mutating descendant"
fi
kill "$crash_descendant_process_identifier"
wait_for_writer_lease_release "$crash_lease_root"

symlink_target_directory="$TEST_WORK_DIRECTORY/symlink-target"
symlink_root="$TEST_WORK_DIRECTORY/symlink-root"
mkdir "$symlink_target_directory"
ln -s "$symlink_target_directory" "$symlink_root"
if acquire_writer_lease_in_clean_shell "$symlink_root" >/dev/null 2>&1; then
    fail_writer_lease_test "symbolic-link lease directory was accepted"
fi
if [ -e "$symlink_target_directory/qdrant-writer.lock" ]; then
    fail_writer_lease_test "symbolic-link lease directory changed its target"
fi

symlink_file_root="$TEST_WORK_DIRECTORY/symlink-file-root"
symlink_file_target="$TEST_WORK_DIRECTORY/symlink-file-target"
mkdir "$symlink_file_root"
: > "$symlink_file_target"
ln -s "$symlink_file_target" "$symlink_file_root/qdrant-writer.lock"
if acquire_writer_lease_in_clean_shell "$symlink_file_root" >/dev/null 2>&1; then
    fail_writer_lease_test "symbolic-link lease file was accepted"
fi
if [ -s "$symlink_file_target" ]; then
    fail_writer_lease_test "symbolic-link lease file changed its target"
fi

poisoned_binary_directory="$TEST_WORK_DIRECTORY/poisoned-bin"
poisoned_lease_root="$TEST_WORK_DIRECTORY/poisoned-flock"
mkdir "$poisoned_binary_directory" "$poisoned_lease_root"
printf '#!/bin/sh\nexit 99\n' > "$poisoned_binary_directory/flock"
chmod +x "$poisoned_binary_directory/flock"
if ! PATH="$poisoned_binary_directory:$PATH" \
    acquire_writer_lease_in_clean_shell "$poisoned_lease_root"; then
    fail_writer_lease_test "lease still depends on the platform flock command"
fi

if grep -En 'exec \{[^}]+\}|flock --nonblock' "$TEST_PROJECT_ROOT/scripts/lib/common_qdrant.sh"; then
    fail_writer_lease_test "lease owner still contains Bash 4 or Linux-only syntax"
fi

python3 - "$TEST_PROJECT_ROOT/scripts/qdrant_writer_lease.py" <<'PY'
import importlib.util
import os
import pathlib
import tempfile
from unittest import mock
import sys

lease_script_path = pathlib.Path(sys.argv[1])
lease_specification = importlib.util.spec_from_file_location("qdrant_writer_lease", lease_script_path)
if lease_specification is None or lease_specification.loader is None:
    raise RuntimeError("Could not load Qdrant writer lease module")
lease_module = importlib.util.module_from_spec(lease_specification)
lease_specification.loader.exec_module(lease_module)
with tempfile.TemporaryDirectory() as lease_directory_name:
    lease_path = pathlib.Path(lease_directory_name) / "qdrant-writer.lock"
    lease_descriptor = os.open(lease_path, os.O_CREAT | os.O_APPEND | os.O_WRONLY, 0o600)
    try:
        with mock.patch.object(lease_module.sysconfig, "get_config_var", return_value=0):
            try:
                lease_module.claim_writer_lease(lease_path, lease_descriptor)
            except OSError as lease_capability_failure:
                if "operating system flock" not in str(lease_capability_failure):
                    raise
            else:
                raise AssertionError("Missing native flock support was accepted")
    finally:
        os.close(lease_descriptor)
PY

printf 'PASS: shared Qdrant writer lease is inherited, crash-safe, and portable.\n'
