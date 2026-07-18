#!/bin/bash

# Verifies that monitor_java_process accepts only a zero-exit child with the completion marker.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_SCRIPT_DIRECTORY/.." && pwd)"
TEST_WORK_ROOT="$(mktemp -d)"
readonly TEST_MONITOR_SLEEP_SECONDS="0.01"
readonly MONITOR_SUCCESS_EXIT_CODE=0
readonly MONITOR_FAILURE_EXIT_CODE=1
readonly CHILD_SUCCESS_EXIT_CODE=0
readonly CHILD_SIGTERM_EXIT_CODE=143
readonly CHILD_FAILURE_EXIT_CODE=17
readonly PARENT_SIGINT_EXIT_CODE=130
readonly PARENT_SIGTERM_EXIT_CODE=143
readonly PARENT_SIGNAL_TEST_MAX_POLLS=100
readonly COMPLETION_MARKER_PRESENT="present"
readonly COMPLETION_MARKER_MISSING="missing"
SIGNAL_TEST_WRAPPER_PID=""
SIGNAL_TEST_CHILD_PID=""

cleanup_monitor_test() {
    if [ -n "$SIGNAL_TEST_WRAPPER_PID" ] && kill -0 "$SIGNAL_TEST_WRAPPER_PID" 2>/dev/null; then
        kill -TERM "$SIGNAL_TEST_WRAPPER_PID" 2>/dev/null || true
    fi
    if [ -n "$SIGNAL_TEST_CHILD_PID" ] && kill -0 "$SIGNAL_TEST_CHILD_PID" 2>/dev/null; then
        kill -TERM "$SIGNAL_TEST_CHILD_PID" 2>/dev/null || true
        command sleep "$TEST_MONITOR_SLEEP_SECONDS"
        if kill -0 "$SIGNAL_TEST_CHILD_PID" 2>/dev/null; then
            kill -KILL "$SIGNAL_TEST_CHILD_PID" 2>/dev/null || true
        fi
    fi
    rm -rf "$TEST_WORK_ROOT"
}
trap cleanup_monitor_test EXIT

# shellcheck source=lib/common_qdrant.sh
source "$PROJECT_ROOT/scripts/lib/common_qdrant.sh"

# Keeps synthetic-child tests fast without changing the production polling interval.
sleep() {
    command sleep "$TEST_MONITOR_SLEEP_SECONDS"
}

fail_monitor_test() {
    local failure_message="$1"
    printf 'FAIL: %s\n' "$failure_message" >&2
    exit 1
}

run_monitor_case() {
    local scenario_name="$1"
    local child_exit_code="$2"
    local completion_marker_state="$3"
    local expected_monitor_exit_code="$4"
    local log_file="$TEST_WORK_ROOT/${scenario_name}.log"
    local pid_file="$TEST_WORK_ROOT/${scenario_name}.pid"

    : > "$log_file"
    (
        if [ "$completion_marker_state" = "$COMPLETION_MARKER_PRESENT" ]; then
            printf '%s\n' "$DOCUMENT_PROCESSING_COMPLETE_MARKER" >> "$log_file"
        fi
        command sleep 0.05
        exit "$child_exit_code"
    ) &
    local child_pid=$!
    printf '%s\n' "$child_pid" > "$pid_file"

    local monitor_exit_code
    if monitor_java_process "$child_pid" "$log_file" "$pid_file"; then
        monitor_exit_code=$MONITOR_SUCCESS_EXIT_CODE
    else
        monitor_exit_code=$?
    fi

    if [ "$monitor_exit_code" -ne "$expected_monitor_exit_code" ]; then
        fail_monitor_test "$scenario_name returned $monitor_exit_code; expected $expected_monitor_exit_code"
    fi
    if [ -e "$pid_file" ]; then
        fail_monitor_test "$scenario_name did not remove its PID file"
    fi
}

run_parent_signal_cleanup_case() {
    local received_signal="$1"
    local expected_parent_exit_code="$2"
    local scenario_name
    case "$received_signal" in
        INT)
            scenario_name="parent-int-signal"
            ;;
        TERM)
            scenario_name="parent-term-signal"
            ;;
        *)
            fail_monitor_test "unsupported parent signal test: $received_signal"
            ;;
    esac
    local parent_pid_file="$TEST_WORK_ROOT/${scenario_name}.pid"
    local managed_child_pid_file="$TEST_WORK_ROOT/${scenario_name}.child.pid"
    local parent_ready_file="$TEST_WORK_ROOT/${scenario_name}.ready"
    local parent_log_file="$TEST_WORK_ROOT/${scenario_name}.log"

    (
        sleep() {
            command sleep "$TEST_MONITOR_SLEEP_SECONDS"
        }
        setup_pid_and_cleanup "$parent_pid_file"
        (
            trap '' INT TERM
            while true; do
                command sleep 1
            done
        ) &
        APP_PID=$!
        printf '%s\n' "$APP_PID" > "$parent_pid_file"
        printf '%s\n' "$APP_PID" > "$managed_child_pid_file"
        : > "$parent_ready_file"
        wait "$APP_PID"
    ) > "$parent_log_file" 2>&1 &
    local signal_wrapper_pid=$!
    SIGNAL_TEST_WRAPPER_PID="$signal_wrapper_pid"

    local readiness_poll
    for ((readiness_poll = 0; readiness_poll < PARENT_SIGNAL_TEST_MAX_POLLS; readiness_poll++)); do
        if [ -s "$managed_child_pid_file" ] && [ -f "$parent_ready_file" ]; then
            break
        fi
        command sleep "$TEST_MONITOR_SLEEP_SECONDS"
    done
    if [ ! -s "$managed_child_pid_file" ] || [ ! -f "$parent_ready_file" ]; then
        fail_monitor_test "$scenario_name did not launch a managed child before signal delivery"
    fi

    local managed_child_pid
    managed_child_pid="$(< "$managed_child_pid_file")"
    SIGNAL_TEST_CHILD_PID="$managed_child_pid"
    if ! kill -0 "$managed_child_pid" 2>/dev/null; then
        fail_monitor_test "$scenario_name managed child exited before signal delivery"
    fi

    kill -s "$received_signal" "$signal_wrapper_pid"
    local parent_exit_code
    if wait "$signal_wrapper_pid"; then
        parent_exit_code=0
    else
        parent_exit_code=$?
    fi
    if [ "$parent_exit_code" -ne "$expected_parent_exit_code" ]; then
        fail_monitor_test "$scenario_name returned $parent_exit_code; expected $expected_parent_exit_code"
    fi
    if [ -e "$parent_pid_file" ]; then
        fail_monitor_test "$scenario_name did not remove its PID file"
    fi

    local shutdown_poll
    for ((shutdown_poll = 0; shutdown_poll < PARENT_SIGNAL_TEST_MAX_POLLS; shutdown_poll++)); do
        if ! kill -0 "$managed_child_pid" 2>/dev/null; then
            break
        fi
        command sleep "$TEST_MONITOR_SLEEP_SECONDS"
    done
    if kill -0 "$managed_child_pid" 2>/dev/null; then
        fail_monitor_test "$scenario_name orphaned its managed child"
    fi
    SIGNAL_TEST_WRAPPER_PID=""
    SIGNAL_TEST_CHILD_PID=""
}

run_monitor_case \
    "zero-exit-with-marker" \
    "$CHILD_SUCCESS_EXIT_CODE" \
    "$COMPLETION_MARKER_PRESENT" \
    "$MONITOR_SUCCESS_EXIT_CODE"
run_monitor_case \
    "zero-exit-without-marker" \
    "$CHILD_SUCCESS_EXIT_CODE" \
    "$COMPLETION_MARKER_MISSING" \
    "$MONITOR_FAILURE_EXIT_CODE"
run_monitor_case \
    "sigterm-exit-with-marker" \
    "$CHILD_SIGTERM_EXIT_CODE" \
    "$COMPLETION_MARKER_PRESENT" \
    "$MONITOR_FAILURE_EXIT_CODE"
run_monitor_case \
    "nonzero-exit-with-marker" \
    "$CHILD_FAILURE_EXIT_CODE" \
    "$COMPLETION_MARKER_PRESENT" \
    "$MONITOR_FAILURE_EXIT_CODE"
run_parent_signal_cleanup_case INT "$PARENT_SIGINT_EXIT_CODE"
run_parent_signal_cleanup_case TERM "$PARENT_SIGTERM_EXIT_CODE"

printf 'PASS: monitor_java_process exit and marker contract\n'
