#!/bin/bash

# Verifies CLI environment propagation and fail-fast path validation without external services.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_WORK_DIRECTORY="$(mktemp -d)"
CAPTURED_CHILD_ENVIRONMENT="$TEST_WORK_DIRECTORY/child-environment"
CAPTURED_CHILD_ARGUMENTS="$TEST_WORK_DIRECTORY/child-arguments"
trap 'rm -rf -- "$TEST_WORK_DIRECTORY"' EXIT

fail_process_environment_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

set --
# shellcheck source=process_all_to_qdrant.sh
source "$TEST_SCRIPT_DIRECTORY/process_all_to_qdrant.sh"

load_env_file() {
    :
}

check_qdrant_connection() {
    return 0
}

check_embedding_server() {
    return 0
}

setup_pid_and_cleanup() {
    :
}

build_application() {
    :
}

locate_app_jar() {
    printf '%s\n' "$TEST_WORK_DIRECTORY/application.jar"
}

monitor_java_process() {
    wait "$1"
}

verify_doc_set_postconditions() {
    :
}

log() {
    :
}

java() {
    printf '%s|%s\n' "$DOCS_DIR" "$SPRING_PROFILE" > "$CAPTURED_CHILD_ENVIRONMENT"
    printf '%s\n' "$@" > "$CAPTURED_CHILD_ARGUMENTS"
}

mkdir -p \
    "$TEST_WORK_DIRECTORY/arbitrary-corpus/kotlin" \
    "$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/snapshots" \
    "$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/parsed" \
    "$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/index"
printf '<html>Kotlin 2.4.10</html>\n' > "$TEST_WORK_DIRECTORY/arbitrary-corpus/kotlin/index.html"
: > "$TEST_WORK_DIRECTORY/application.jar"

export QDRANT_HOST=127.0.0.1
export QDRANT_PORT=8086
export APP_LOCAL_EMBEDDING_ENABLED=false
export SPRING_PROFILE=local
export QDRANT_COLLECTION_BOOKS=java-chat-local-qwen3-embedding-4b-2560-books
export QDRANT_COLLECTION_DOCS=java-chat-local-qwen3-embedding-4b-2560-docs
export QDRANT_COLLECTION_ARTICLES=java-chat-local-qwen3-embedding-4b-2560-articles
export QDRANT_COLLECTION_PDFS=java-chat-local-qwen3-embedding-4b-2560-pdfs
export DOCS_DIR="$TEST_WORK_DIRECTORY/arbitrary-corpus"
export DOCS_SNAPSHOT_DIR="$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/snapshots"
export DOCS_PARSED_DIR="$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/parsed"
export DOCS_INDEX_DIR="$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/index"

run_documentation_ingestion --doc-sets=kotlin >/dev/null

if [ "$(< "$CAPTURED_CHILD_ENVIRONMENT")" != "$DOCS_DIR|local" ]; then
    fail_process_environment_test "DOCS_DIR and SPRING_PROFILE did not reach the child environment"
fi
if ! grep -Fxq -- "--spring.main.web-application-type=none" "$CAPTURED_CHILD_ARGUMENTS" \
    || ! grep -Fxq -- "--server.port=0" "$CAPTURED_CHILD_ARGUMENTS"; then
    fail_process_environment_test "CLI child did not preserve non-web startup arguments"
fi
if grep -q -- "-DDOCS_DIR" "$CAPTURED_CHILD_ARGUMENTS"; then
    fail_process_environment_test "DOCS_DIR was passed as an ineffective JVM property"
fi

if (
    export SPRING_PROFILE=staging
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    fail_process_environment_test "invalid SPRING_PROFILE was accepted"
fi

if (
    export SPRING_PROFILE=local
    export DOCS_DIR="$TEST_WORK_DIRECTORY/missing-corpus"
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    fail_process_environment_test "missing DOCS_DIR was accepted"
fi

if (
    export SPRING_PROFILE=dev
    export DOCS_DIR="$TEST_WORK_DIRECTORY/arbitrary-corpus"
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    fail_process_environment_test "local collections and state were accepted under the dev profile"
fi

if (
    export SPRING_PROFILE=local
    export DOCS_DIR="$TEST_WORK_DIRECTORY/arbitrary-corpus"
    export QDRANT_COLLECTION_DOCS=java-chat-local-qwen3-embedding-8b-4096-docs
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    fail_process_environment_test "a different embedding-generation collection was accepted"
fi

if (
    export SPRING_PROFILE=local
    export DOCS_DIR="$TEST_WORK_DIRECTORY/arbitrary-corpus"
    export DOCS_INDEX_DIR="$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/prod/index"
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    fail_process_environment_test "production generation state was accepted under the local profile"
fi

printf 'PASS: arbitrary DOCS_DIR is exported to a servlet-free ingestion child after fail-fast validation.\n'
