#!/bin/bash

# Verifies the durable staging launcher cannot inherit cloud Qdrant credentials.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_WORK_DIRECTORY="$(mktemp -d)"
staging_process_id=""

cleanup_local_staging_contract_test() {
    if [ -n "$staging_process_id" ]; then
        kill "$staging_process_id" 2>/dev/null || true
        wait "$staging_process_id" 2>/dev/null || true
    fi
    rm -rf -- "$TEST_WORK_DIRECTORY"
}

trap cleanup_local_staging_contract_test EXIT

fail_local_staging_contract_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

launcher_path="$TEST_SCRIPT_DIRECTORY/run_local_embedding_staging.sh"
local_profile_path="$TEST_SCRIPT_DIRECTORY/../src/main/resources/application-local.properties"
bash -n "$launcher_path"

if grep -Fq 'APP_EMBEDDINGS_BATCH_' "$launcher_path" \
    || ! grep -Fxq 'app.embeddings.batch-max-concurrent-requests=8' "$local_profile_path" \
    || ! grep -Fxq 'app.embeddings.batch-requests-per-second=8.0' "$local_profile_path"; then
    fail_local_staging_contract_test "local batch capacity is not owned by the Spring profile"
fi

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
if ! grep -Fxq 'readonly EXPECTED_REPOSITORY_COUNT=23' "$launcher_path"; then
    fail_local_staging_contract_test "launcher does not require exactly 23 pinned repositories"
fi
if grep -Eq 'GRADLE_USER_HOME=/|find .* -printf|sort -z|date -Ins|mapfile' "$launcher_path"; then
    fail_local_staging_contract_test "launcher contains GNU-only or developer-specific commands"
fi
if ! grep -Fq 'repository_git_directories=(data/repos/github/*/*/.git)' "$launcher_path" \
    || ! grep -Fq "date -u '+%Y-%m-%dT%H:%M:%SZ'" "$launcher_path"; then
    fail_local_staging_contract_test "launcher does not use portable repository discovery and timestamps"
fi
repository_validation_locations="$(grep -n 'Pinned repository is dirty:' "$launcher_path" || true)"
writer_lease_locations="$(grep -n '^acquire_qdrant_writer_lease$' "$launcher_path" || true)"
if [ "$(printf '%s\n' "$repository_validation_locations" | sed '/^$/d' | wc -l | tr -d ' ')" -ne 1 ] \
    || [ "$(printf '%s\n' "$writer_lease_locations" | sed '/^$/d' | wc -l | tr -d ' ')" -ne 1 ]; then
    fail_local_staging_contract_test "launcher does not contain exactly one repository validation and writer lease"
fi
repository_validation_line="${repository_validation_locations%%:*}"
writer_lease_line="${writer_lease_locations%%:*}"
if [ "$writer_lease_line" -le "$repository_validation_line" ]; then
    fail_local_staging_contract_test "launcher contends on the writer lease before repository validation"
fi

fake_project_root="$TEST_WORK_DIRECTORY/project"
fake_script_directory="$fake_project_root/scripts"
fake_binary_directory="$TEST_WORK_DIRECTORY/bin"
staging_trace="$TEST_WORK_DIRECTORY/staging.trace"
documentation_started_marker="$TEST_WORK_DIRECTORY/documentation.started"
documentation_release_marker="$TEST_WORK_DIRECTORY/documentation.release"
staging_output="$TEST_WORK_DIRECTORY/staging.output"
mkdir -p "$fake_script_directory/lib" "$fake_binary_directory" "$fake_project_root/data/repos/github/test"
cp "$launcher_path" "$fake_script_directory/run_local_embedding_staging.sh"

for repository_index in {1..23}; do
    mkdir -p "$fake_project_root/data/repos/github/test/repository-$repository_index/.git"
done

printf '%s\n' \
    '#!/bin/bash' \
    'exit 0' \
    > "$fake_binary_directory/git"
printf '%s\n' \
    '#!/bin/bash' \
    'set -euo pipefail' \
    'printf "DOCUMENTATION_STAGED\\n" >> "$STAGING_TRACE"' \
    'touch "$DOCUMENTATION_STARTED_MARKER"' \
    'while [ ! -f "$DOCUMENTATION_RELEASE_MARKER" ]; do sleep 0.05; done' \
    > "$fake_script_directory/process_all_to_qdrant.sh"
printf '%s\n' \
    '#!/bin/bash' \
    'set -euo pipefail' \
    'SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"' \
    'source "$SCRIPT_DIRECTORY/lib/common_qdrant.sh"' \
    'record_staged_library' \
    'printf "REPOSITORY_STAGED\\n" >> "$STAGING_TRACE"' \
    > "$fake_script_directory/process_github_repo.sh"
printf '%s\n' \
    'acquire_qdrant_writer_lease() { :; }' \
    'record_staged_library() { printf "LIBRARY_STAGED\\n" >> "$STAGING_TRACE"; }' \
    > "$fake_script_directory/lib/common_qdrant.sh"
chmod +x \
    "$fake_binary_directory/git" \
    "$fake_script_directory/run_local_embedding_staging.sh" \
    "$fake_script_directory/process_all_to_qdrant.sh" \
    "$fake_script_directory/process_github_repo.sh"

PATH="$fake_binary_directory:$PATH" \
STAGING_TRACE="$staging_trace" \
DOCUMENTATION_STARTED_MARKER="$documentation_started_marker" \
DOCUMENTATION_RELEASE_MARKER="$documentation_release_marker" \
    "$fake_script_directory/run_local_embedding_staging.sh" > "$staging_output" 2>&1 &
staging_process_id=$!

for ((wait_attempt = 0; wait_attempt < 200; wait_attempt++)); do
    [ -f "$documentation_started_marker" ] && break
    sleep 0.05
done
if [ ! -f "$documentation_started_marker" ]; then
    fail_local_staging_contract_test "staged documentation child did not start"
fi

printf '#!/bin/bash\nexit 99\n' > "$fake_script_directory/run_local_embedding_staging.sh"
printf '#!/bin/bash\nexit 99\n' > "$fake_script_directory/process_all_to_qdrant.sh"
printf '#!/bin/bash\nexit 99\n' > "$fake_script_directory/process_github_repo.sh"
printf 'return 99\n' > "$fake_script_directory/lib/common_qdrant.sh"
touch "$documentation_release_marker"

if ! wait "$staging_process_id"; then
    staging_process_id=""
    fail_local_staging_contract_test "staged launcher failed after live scripts changed"
fi
staging_process_id=""

repository_staged_count="$(grep -c '^REPOSITORY_STAGED$' "$staging_trace" || true)"
library_staged_count="$(grep -c '^LIBRARY_STAGED$' "$staging_trace" || true)"
if [ "$repository_staged_count" -ne 23 ] || [ "$library_staged_count" -ne 23 ]; then
    fail_local_staging_contract_test "staged launcher did not use all snapshotted repository children and libraries"
fi
if ! grep -q '^LOCAL_STAGING_COMPLETE ' "$staging_output"; then
    fail_local_staging_contract_test "staged launcher did not reach its completion boundary"
fi
shopt -s nullglob
staging_residue=("$fake_project_root"/.local-embedding-staging-run.*)
shopt -u nullglob
if [ "${#staging_residue[@]}" -ne 0 ]; then
    fail_local_staging_contract_test "staged launcher left a script snapshot behind"
fi

printf 'PASS: durable staging forces local Qdrant and masks cloud credentials across .env loading.\n'
printf 'PASS: durable staging executes one immutable script snapshot across live in-place edits.\n'
