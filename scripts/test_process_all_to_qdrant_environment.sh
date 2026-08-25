#!/bin/bash

# Verifies CLI environment propagation and fail-fast path validation without external services.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_WORK_DIRECTORY="$(mktemp -d)"
CAPTURED_CHILD_ENVIRONMENT="$TEST_WORK_DIRECTORY/child-environment"
CAPTURED_CHILD_ARGUMENTS="$TEST_WORK_DIRECTORY/child-arguments"
WRITER_LEASE_CAPTURE="$TEST_WORK_DIRECTORY/writer-lease-acquisitions"
JAR_STAGING_ROOT="$TEST_WORK_DIRECTORY/jar-staging"
trap 'rm -rf -- "$TEST_WORK_DIRECTORY"' EXIT

fail_process_environment_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

set --
# shellcheck source=process_all_to_qdrant.sh
source "$TEST_SCRIPT_DIRECTORY/process_all_to_qdrant.sh"

export LOG_FILE="$TEST_WORK_DIRECTORY/process_qdrant.log"
export PID_FILE="$TEST_WORK_DIRECTORY/process_qdrant.pid"

load_env_file() {
    :
}

acquire_qdrant_writer_lease() {
    printf 'acquired\n' >> "$WRITER_LEASE_CAPTURE"
}

check_qdrant_connection() {
    return 0
}

check_embedding_server() {
    return 0
}

setup_pid_and_cleanup() {
    if [ ! -s "$WRITER_LEASE_CAPTURE" ]; then
        fail_process_environment_test "ingestion claimed its PID before the writer lease"
    fi
}

build_application() {
    :
}

locate_app_jar() {
    printf '%s\n' "$TEST_WORK_DIRECTORY/application.jar"
}

stage_app_jar() {
    cp "$1" "$2/application.jar"
    printf '%s\n' "$2/application.jar"
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
    "$JAR_STAGING_ROOT" \
    "$TEST_WORK_DIRECTORY/arbitrary-corpus/kotlin" \
    "$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/snapshots" \
    "$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/parsed" \
    "$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/index" \
    "$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/dev/snapshots" \
    "$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/dev/parsed" \
    "$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/dev/index"
printf '<html>Kotlin 2.4.10</html>\n' > "$TEST_WORK_DIRECTORY/arbitrary-corpus/kotlin/index.html"
: > "$TEST_WORK_DIRECTORY/application.jar"

export QDRANT_HOST=127.0.0.1
export QDRANT_PORT=8086
export APP_LOCAL_EMBEDDING_ENABLED=false
export SPRING_PROFILE=local
export QDRANT_COLLECTION_BOOKS=java-chat-qwen3-embedding-4b-2560-books
export QDRANT_COLLECTION_DOCS=java-chat-qwen3-embedding-4b-2560-docs
export QDRANT_COLLECTION_ARTICLES=java-chat-qwen3-embedding-4b-2560-articles
export QDRANT_COLLECTION_PDFS=java-chat-qwen3-embedding-4b-2560-pdfs
export DOCS_DIR="$TEST_WORK_DIRECTORY/arbitrary-corpus"
export DOCS_SNAPSHOT_DIR="$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/snapshots"
export DOCS_PARSED_DIR="$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/parsed"
export DOCS_INDEX_DIR="$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/local/index"
export TMPDIR="$JAR_STAGING_ROOT"

prior_failure_evidence='FAILED file=data/docs/docker/reference/cli/docker.md phase=qdrant-replacement'
checkpoint_sentinel="$DOCS_INDEX_DIR/checkpoint-sentinel"
printf '%s\n' "$prior_failure_evidence" > "$LOG_FILE"
printf 'checkpoint-preserved\n' > "$checkpoint_sentinel"

if ! (run_documentation_ingestion --help >/dev/null); then
    fail_process_environment_test "help did not exit successfully"
fi
if [ -e "$WRITER_LEASE_CAPTURE" ]; then
    fail_process_environment_test "help acquired the writer lease"
fi
if (run_documentation_ingestion --unsupported-option >/dev/null 2>&1); then
    fail_process_environment_test "unknown CLI option was accepted"
fi
if [ -e "$WRITER_LEASE_CAPTURE" ]; then
    fail_process_environment_test "unknown CLI option acquired the writer lease"
fi
if (run_documentation_ingestion --doc-sets= >/dev/null 2>&1) \
    || (run_documentation_ingestion "--doc-sets=   " >/dev/null 2>&1); then
    fail_process_environment_test "blank targeted documentation selector was accepted"
fi
if [ -e "$WRITER_LEASE_CAPTURE" ]; then
    fail_process_environment_test "blank targeted documentation selector acquired the writer lease"
fi

run_documentation_ingestion --doc-sets=kotlin >/dev/null
if [ "$(wc -l < "$WRITER_LEASE_CAPTURE" | tr -d ' ')" -ne 1 ]; then
    fail_process_environment_test "validated ingestion did not acquire exactly one writer lease"
fi

archived_processing_log=""
archived_processing_log_count=0
for processing_log_candidate in "$TEST_WORK_DIRECTORY"/process_qdrant_attempt_*.log; do
    [ -f "$processing_log_candidate" ] || continue
    archived_processing_log="$processing_log_candidate"
    archived_processing_log_count=$((archived_processing_log_count + 1))
done
if [ "$archived_processing_log_count" -ne 1 ]; then
    fail_process_environment_test "prior processing log was not archived exactly once"
fi
if ! grep -Fxq "$prior_failure_evidence" "$archived_processing_log"; then
    fail_process_environment_test "archived processing log lost the prior file and phase evidence"
fi
if grep -Fq "$prior_failure_evidence" "$LOG_FILE" \
    || ! grep -Fq 'Starting document processing' "$LOG_FILE"; then
    fail_process_environment_test "fresh processing log retained stale failure evidence or lost its start marker"
fi
if [ "$(< "$checkpoint_sentinel")" != "checkpoint-preserved" ]; then
    fail_process_environment_test "processing log archival changed durable checkpoint state"
fi

if ! (
    export LOG_FILE="$TEST_WORK_DIRECTORY/prebuilt-process.log"
    export PID_FILE="$TEST_WORK_DIRECTORY/prebuilt-process.pid"
    build_application() {
        fail_process_environment_test "prebuilt ingestion invoked Gradle"
    }
    locate_app_jar() {
        fail_process_environment_test "prebuilt ingestion searched build outputs"
    }
    run_documentation_ingestion \
        --doc-sets=kotlin \
        --app-jar="$TEST_WORK_DIRECTORY/application.jar" >/dev/null
); then
    fail_process_environment_test "prebuilt ingestion JAR did not bypass the build lane"
fi

collision_log="$TEST_WORK_DIRECTORY/collision.log"
collision_archive="${collision_log%.log}_attempt_20260824T000000Z_$$_1.log"
printf 'new failure evidence\n' > "$collision_log"
printf 'existing failure evidence\n' > "$collision_archive"
if (
    LOG_FILE="$collision_log"
    PROCESSING_LOG_ARCHIVE_SEQUENCE=0
    date() {
        printf '20260824T000000Z\n'
    }
    archive_prior_processing_log >/dev/null 2>&1
); then
    fail_process_environment_test "processing log archive collision was overwritten"
fi
if [ "$(< "$collision_log")" != "new failure evidence" ] \
    || [ "$(< "$collision_archive")" != "existing failure evidence" ]; then
    fail_process_environment_test "processing log archive collision changed forensic evidence"
fi

fresh_log_failure_pid="$TEST_WORK_DIRECTORY/fresh-log-failure.pid"
if (
    export LOG_FILE="$TEST_WORK_DIRECTORY/missing-log-parent/process.log"
    export PID_FILE="$fresh_log_failure_pid"
    setup_pid_and_cleanup() {
        : > "$1"
    }
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    fail_process_environment_test "fresh processing log initialization failure was accepted"
fi
if [ -e "$fresh_log_failure_pid" ]; then
    fail_process_environment_test "fresh processing log initialization failure left the ingestion PID claim"
fi

if [ "$(< "$CAPTURED_CHILD_ENVIRONMENT")" != "$DOCS_DIR|local" ]; then
    fail_process_environment_test "DOCS_DIR and SPRING_PROFILE did not reach the child environment"
fi
if ! grep -Fxq -- "--app.qdrant.ensure-collections=true" "$CAPTURED_CHILD_ARGUMENTS" \
    || ! grep -Fxq -- "--spring.main.web-application-type=none" "$CAPTURED_CHILD_ARGUMENTS" \
    || ! grep -Fxq -- "--server.port=0" "$CAPTURED_CHILD_ARGUMENTS"; then
    fail_process_environment_test "CLI child did not preserve explicit collection creation and non-web startup arguments"
fi
if grep -q -- "-DDOCS_DIR" "$CAPTURED_CHILD_ARGUMENTS"; then
    fail_process_environment_test "DOCS_DIR was passed as an ineffective JVM property"
fi
staged_app_jar_argument="$(awk 'previous_argument == "-jar" { print; exit } { previous_argument = $0 }' "$CAPTURED_CHILD_ARGUMENTS")"
if [ "$staged_app_jar_argument" = "$TEST_WORK_DIRECTORY/application.jar" ] \
    || [[ "$staged_app_jar_argument" != "$JAR_STAGING_ROOT"/java-chat-document-ingestion.*/application.jar ]]; then
    fail_process_environment_test "CLI child did not receive the private staged application jar"
fi
if find "$JAR_STAGING_ROOT" -mindepth 1 -print -quit | grep -q .; then
    fail_process_environment_test "successful ingestion left a staged application jar directory"
fi

rm -f "$WRITER_LEASE_CAPTURE"
if (
    export SPRING_PROFILE=staging
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    fail_process_environment_test "invalid SPRING_PROFILE was accepted"
fi
if [ -e "$WRITER_LEASE_CAPTURE" ]; then
    fail_process_environment_test "invalid SPRING_PROFILE acquired the writer lease"
fi

rm -f "$WRITER_LEASE_CAPTURE"
if (
    export SPRING_PROFILE=local
    export DOCS_DIR="$TEST_WORK_DIRECTORY/missing-corpus"
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    fail_process_environment_test "missing DOCS_DIR was accepted"
fi
if [ -e "$WRITER_LEASE_CAPTURE" ]; then
    fail_process_environment_test "missing DOCS_DIR acquired the writer lease"
fi

rm -f "$WRITER_LEASE_CAPTURE"
invalid_state_parent="$TEST_WORK_DIRECTORY/state-file"
: > "$invalid_state_parent"
if (
    export SPRING_PROFILE=local
    export DOCS_DIR="$TEST_WORK_DIRECTORY/arbitrary-corpus"
    export DOCS_SNAPSHOT_DIR="$invalid_state_parent/qwen3-embedding-4b-2560/local/snapshots"
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    fail_process_environment_test "invalid ingestion state directory was accepted"
fi
if [ -e "$WRITER_LEASE_CAPTURE" ]; then
    fail_process_environment_test "invalid ingestion state directory acquired the writer lease"
fi

if (
    export SPRING_PROFILE=dev
    export DOCS_DIR="$TEST_WORK_DIRECTORY/arbitrary-corpus"
    export DOCS_SNAPSHOT_DIR="$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/dev/snapshots"
    export DOCS_PARSED_DIR="$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/dev/parsed"
    export DOCS_INDEX_DIR="$TEST_WORK_DIRECTORY/state/qwen3-embedding-4b-2560/dev/index"
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    :
else
    fail_process_environment_test "shared generation collections were rejected under the dev profile"
fi

if (
    export SPRING_PROFILE=dev
    export DOCS_DIR="$TEST_WORK_DIRECTORY/arbitrary-corpus"
    run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1
); then
    fail_process_environment_test "local ingestion state was accepted under the dev profile"
fi

if (
    export SPRING_PROFILE=local
    export DOCS_DIR="$TEST_WORK_DIRECTORY/arbitrary-corpus"
    export QDRANT_COLLECTION_DOCS=java-chat-qwen3-embedding-8b-4096-docs
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

setup_pid_and_cleanup() {
    : > "$1"
}

stage_app_jar() {
    return 1
}

if run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1; then
    fail_process_environment_test "application jar staging failure was accepted"
fi
if [ -e "$PID_FILE" ]; then
    fail_process_environment_test "application jar staging failure left the ingestion PID claim"
fi
if find "$JAR_STAGING_ROOT" -mindepth 1 -print -quit | grep -q .; then
    fail_process_environment_test "application jar staging failure left its private staging directory"
fi

mktemp() {
    return 1
}

if run_documentation_ingestion --doc-sets=kotlin >/dev/null 2>&1; then
    fail_process_environment_test "temporary staging directory failure was accepted"
fi
if [ -e "$PID_FILE" ]; then
    fail_process_environment_test "temporary staging directory failure left the ingestion PID claim"
fi

printf 'PASS: arbitrary DOCS_DIR is exported to a servlet-free ingestion child after fail-fast validation.\n'
