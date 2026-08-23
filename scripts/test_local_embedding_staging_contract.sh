#!/bin/bash

# Verifies the durable staging launcher cannot inherit cloud Qdrant credentials.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$TEST_SCRIPT_DIRECTORY/.."
TEST_WORK_DIRECTORY="$(mktemp -d)"
trap 'rm -rf -- "$TEST_WORK_DIRECTORY"' EXIT

fail_local_staging_contract_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

launcher_path="$TEST_SCRIPT_DIRECTORY/run_local_embedding_staging.sh"
bash -n "$launcher_path"

if ! grep -Fxq 'export QDRANT_HOST=127.0.0.1' "$launcher_path" \
    || ! grep -Fxq 'export QDRANT_PORT=8086' "$launcher_path" \
    || ! grep -Fxq 'export QDRANT_REST_PORT=8087' "$launcher_path" \
    || ! grep -Fxq 'export QDRANT_SSL=false' "$launcher_path"; then
    fail_local_staging_contract_test "launcher does not force the local Qdrant endpoint"
fi
if ! grep -Fxq 'export QDRANT_API_KEY=' "$launcher_path"; then
    fail_local_staging_contract_test "launcher does not mask the injected cloud Qdrant API key"
fi
if grep -Eq '^[[:space:]]*unset[[:space:]]+QDRANT_API_KEY([[:space:]]|$)' "$launcher_path"; then
    fail_local_staging_contract_test "launcher allows .env to restore the cloud Qdrant API key"
fi

environment_file_path="$TEST_WORK_DIRECTORY/.env"
printf 'QDRANT_API_KEY=cloud-key-must-not-survive\n' > "$environment_file_path"

# shellcheck source=lib/env_loader.sh
source "$TEST_SCRIPT_DIRECTORY/lib/env_loader.sh"
export QDRANT_API_KEY=
preserve_process_env_then_source_file "$environment_file_path"
if [ -n "$QDRANT_API_KEY" ]; then
    fail_local_staging_contract_test "empty process override did not survive .env loading"
fi

if [ "$(find "$PROJECT_ROOT/data/docs" -type f -name '*.html' | wc -l | tr -d ' ')" -ne 24798 ]; then
    fail_local_staging_contract_test "downloaded documentation census changed"
fi
if [ "$(find "$PROJECT_ROOT/data/repos/github" -mindepth 3 -maxdepth 3 -type d -name .git | wc -l | tr -d ' ')" -ne 22 ]; then
    fail_local_staging_contract_test "pinned repository census changed"
fi

printf 'PASS: durable staging forces local Qdrant and masks cloud credentials across .env loading.\n'
