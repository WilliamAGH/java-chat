#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"
MAKE_BINARY="$(command -v make)"

fail_make_port_safety_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

run_port_availability_check() {
    "$MAKE_BINARY" --no-print-directory -f - verify <<'MAKEFILE'
include config/make/common.mk
unexport JAR_PATH

verify:
	@$(call require_port_available,8085)
MAKEFILE
}

available_port_diagnostic="$(
    lsof() {
        return 1
    }
    export -f lsof
    run_port_availability_check
)"
if [[ -n "$available_port_diagnostic" ]]; then
    fail_make_port_safety_test "Expected an available port check to be quiet, received: $available_port_diagnostic"
fi

if occupied_port_diagnostic="$(
    lsof() {
        printf '%s\n' '4242'
    }
    export -f lsof
    run_port_availability_check 2>&1
)"; then
    fail_make_port_safety_test 'Expected an occupied port check to fail.'
fi

expected_port_conflict_message='ERROR: Port 8085 is already in use by process ID(s): 4242. Stop the owning process and retry.'
if [[ "$occupied_port_diagnostic" != *"$expected_port_conflict_message"* ]]; then
    fail_make_port_safety_test "Expected occupied-port diagnostic, received: $occupied_port_diagnostic"
fi

if inspection_failure_diagnostic="$(
    lsof() {
        printf '%s\n' 'simulated lsof failure' >&2
        return 2
    }
    export -f lsof
    run_port_availability_check 2>&1
)"; then
    fail_make_port_safety_test 'Expected a failed port inspection to fail closed.'
fi

expected_inspection_failure_message='ERROR: Unable to verify availability of port 8085: simulated lsof failure'
if [[ "$inspection_failure_diagnostic" != *"$expected_inspection_failure_message"* ]]; then
    fail_make_port_safety_test "Expected inspection-failure diagnostic, received: $inspection_failure_diagnostic"
fi

if unavailable_lsof_diagnostic="$(
    PATH='/no-lsof-command-path'
    export PATH
    run_port_availability_check 2>&1
)"; then
    fail_make_port_safety_test 'Expected an unavailable lsof command to fail closed.'
fi

expected_unavailable_lsof_message='ERROR: Cannot verify availability of port 8085: lsof is unavailable.'
if [[ "$unavailable_lsof_diagnostic" != *"$expected_unavailable_lsof_message"* ]]; then
    fail_make_port_safety_test "Expected unavailable-lsof diagnostic, received: $unavailable_lsof_diagnostic"
fi

port_guard_definition="$(sed -n '/^define require_port_available$/,/^endef$/p' config/make/common.mk)"
if [[ "$port_guard_definition" == *kill* ]]; then
    fail_make_port_safety_test 'Port availability checks must not signal listener processes.'
fi

if grep -n -E '(^|[^[:alnum:]_])kill([^[:alnum:]_]|$)' Makefile; then
    fail_make_port_safety_test 'Make recipes must not signal process groups.'
fi

if grep -n -F 'free_port' Makefile config/make/common.mk; then
    fail_make_port_safety_test 'Make targets must use the fail-closed port availability check.'
fi

printf 'PASS: Make port checks fail closed without signaling listeners.\n'
