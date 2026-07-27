#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

bootstrap_arguments="$(
    make --no-print-directory -f - verify <<'MAKEFILE'
include config/make/common.mk

verify:
	@QDRANT_HOST=localhost; APP_ARGS=(); $(call append_local_qdrant_bootstrap_argument); printf 'localhost=%s\n' "$${APP_ARGS[*]}"; \
	 QDRANT_HOST=127.0.0.1; APP_ARGS=(); $(call append_local_qdrant_bootstrap_argument); printf 'loopback=%s\n' "$${APP_ARGS[*]}"; \
	 QDRANT_HOST=qdrant.example.test; APP_ARGS=(); $(call append_local_qdrant_bootstrap_argument); printf 'remote=%s\n' "$${APP_ARGS[*]}"
MAKEFILE
)"

expected_arguments=$'localhost=--app.qdrant.ensure-collections=true\nloopback=--app.qdrant.ensure-collections=true\nremote='
if [[ "$bootstrap_arguments" != "$expected_arguments" ]]; then
    printf 'Unexpected local Qdrant bootstrap arguments:\n%s\n' "$bootstrap_arguments" >&2
    exit 1
fi

printf 'PASS: local Make commands bootstrap loopback Qdrant only.\n'
