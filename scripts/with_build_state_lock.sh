#!/bin/bash

set -euo pipefail

readonly BUILD_LOCK_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly BUILD_LOCK_PROJECT_ROOT="$(cd "$BUILD_LOCK_SCRIPT_DIRECTORY/.." && pwd)"
readonly BUILD_LOCK_FILE="$BUILD_LOCK_PROJECT_ROOT/.gradle/build-state.lock"
readonly BUILD_LOCK_WAIT_TIMEOUT_SECONDS=900

if [ "$#" -eq 0 ]; then
    echo "Usage: $0 <command> [arguments...]" >&2
    exit 2
fi

mkdir -p -- "$BUILD_LOCK_PROJECT_ROOT/.gradle"

exec java "$BUILD_LOCK_SCRIPT_DIRECTORY/BuildStateLock.java" \
    "$BUILD_LOCK_FILE" \
    "$BUILD_LOCK_WAIT_TIMEOUT_SECONDS" \
    "$@"
