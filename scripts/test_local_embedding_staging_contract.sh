#!/bin/bash

# Verifies the durable staging launcher cannot inherit cloud Qdrant credentials.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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

documentation_set_count="$(
    sed -n 's/^readonly DOCUMENTATION_SETS="\([^"]*\)"$/\1/p' "$launcher_path" \
        | tr ',' '\n' \
        | sed '/^$/d' \
        | wc -l \
        | tr -d ' '
)"
if [ "$documentation_set_count" -ne 25 ]; then
    fail_local_staging_contract_test "launcher does not enumerate exactly 25 documentation sets"
fi
if ! grep -Fxq 'readonly EXPECTED_REPOSITORY_COUNT=22' "$launcher_path"; then
    fail_local_staging_contract_test "launcher does not require exactly 22 pinned repositories"
fi
if grep -Eq 'GRADLE_USER_HOME=/|find .* -printf|sort -z|date -Ins|mapfile' "$launcher_path"; then
    fail_local_staging_contract_test "launcher contains GNU-only or developer-specific commands"
fi
if ! grep -Fq 'repository_git_directories=(data/repos/github/*/*/.git)' "$launcher_path" \
    || ! grep -Fq "date -u '+%Y-%m-%dT%H:%M:%SZ'" "$launcher_path"; then
    fail_local_staging_contract_test "launcher does not use portable repository discovery and timestamps"
fi

printf 'PASS: durable staging forces local Qdrant and masks cloud credentials across .env loading.\n'
