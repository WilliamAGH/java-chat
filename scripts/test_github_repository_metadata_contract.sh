#!/bin/bash

# Verifies repository metadata resolution tolerates pinned detached-HEAD clones
# that carry no refs/remotes/origin/HEAD, instead of aborting the ingestion run
# under set -e when the branch fallback fails (observed with the wafer-ai
# gpu-perf-engineering-resources pinned clone).

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_WORK_DIRECTORY="$(mktemp -d)"
FAKE_BINARY_DIRECTORY="$TEST_WORK_DIRECTORY/bin"
mkdir -p "$FAKE_BINARY_DIRECTORY"
trap 'rm -rf -- "$TEST_WORK_DIRECTORY"' EXIT

printf '%s\n' '#!/bin/bash' 'exit 1' > "$FAKE_BINARY_DIRECTORY/gh"
chmod +x "$FAKE_BINARY_DIRECTORY/gh"
export PATH="$FAKE_BINARY_DIRECTORY:$PATH"

fail_repository_metadata_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

PINNED_CLONE_DIRECTORY="$TEST_WORK_DIRECTORY/pinned-clone"
mkdir -p "$PINNED_CLONE_DIRECTORY"
git -C "$PINNED_CLONE_DIRECTORY" init --quiet
git -C "$PINNED_CLONE_DIRECTORY" -c user.name=contract -c user.email=contract@example.invalid \
    commit --quiet --allow-empty -m "pinned"
git -C "$PINNED_CLONE_DIRECTORY" remote add origin https://github.com/wafer-ai/gpu-perf-engineering-resources.git
git -C "$PINNED_CLONE_DIRECTORY" checkout --quiet --detach HEAD

METADATA_PROBE_OUTPUT="$(
    cd "$TEST_SCRIPT_DIRECTORY/.." || exit 1
    bash -c '
        set -euo pipefail
        RED=""; GREEN=""; YELLOW=""; NC=""
        source scripts/lib/github_identity.sh
        REPO_URL=""
        resolve_repository_metadata_from_path "$1"
        printf "branch=[%s] commit=[%s] key=[%s]\n" \
            "$REPOSITORY_BRANCH" "$REPOSITORY_COMMIT" "$REPOSITORY_KEY"
    ' metadata-probe "$PINNED_CLONE_DIRECTORY"
)" || fail_repository_metadata_test "metadata resolution aborted on a pinned clone without origin/HEAD"

case "$METADATA_PROBE_OUTPUT" in
    *"branch=[] commit=["*"] key=[wafer-ai/gpu-perf-engineering-resources]"*) ;;
    *) fail_repository_metadata_test "unexpected metadata: $METADATA_PROBE_OUTPUT" ;;
esac

printf 'PASS: pinned detached clone without origin/HEAD resolves metadata with empty branch\n'
