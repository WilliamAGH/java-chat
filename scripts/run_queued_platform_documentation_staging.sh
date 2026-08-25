#!/bin/bash

# Waits for the current local embedding backlog, then appends every remaining
# documentation corpus through the same remote gateway and local-only Qdrant boundary.

set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIRECTORY/.."
readonly ACTIVE_STAGING_SERVICE="java-chat-local-embedding-staging.service"
readonly QUEUE_POLL_INTERVAL_SECONDS=60
readonly STAGING_INVOCATION_RECEIPT="$HOME/.local/state/java-chat/local-embedding-staging.invocation"
readonly QUEUED_DOCUMENTATION_SOURCES="porkbun,porkbun-mcp,cloudflare,dev-java,kotlin,scala,groovy,clojure,spring-boot,quarkus,java/java21-complete,java/java24-complete,java/java25-complete,spring-ai-reference,spring-ai-api-stable,spring-framework-reference,spring-framework-api,oracle-java25-release-notes,ibm-java25-overview,jetbrains-java25-article"

if [ "$#" -ne 1 ] || [[ "$1" != --after-invocation=* ]]; then
    echo "Usage: $0 --after-invocation=SYSTEMD_INVOCATION_ID" >&2
    exit 1
fi
readonly EXPECTED_STAGING_INVOCATION_ID="${1#*=}"
if [[ ! "$EXPECTED_STAGING_INVOCATION_ID" =~ ^[0-9a-f]{32}$ ]]; then
    echo "Invalid staging systemd invocation identifier" >&2
    exit 1
fi

cd "$PROJECT_ROOT"

export SPRING_PROFILE=local
export APP_LOCAL_EMBEDDING_ENABLED=false
export QDRANT_HOST=127.0.0.1
export QDRANT_PORT=8086
export QDRANT_REST_PORT=8087
export QDRANT_SSL=false
export QDRANT_API_KEY=
export QDRANT_COLLECTION_BOOKS=java-chat-qwen3-embedding-4b-2560-books
export QDRANT_COLLECTION_DOCS=java-chat-qwen3-embedding-4b-2560-docs
export QDRANT_COLLECTION_ARTICLES=java-chat-qwen3-embedding-4b-2560-articles
export QDRANT_COLLECTION_PDFS=java-chat-qwen3-embedding-4b-2560-pdfs
export DOCS_DIR="$PROJECT_ROOT/data/docs"
export DOCS_SNAPSHOT_DIR="$PROJECT_ROOT/data/qwen3-embedding-4b-2560/local/snapshots"
export DOCS_PARSED_DIR="$PROJECT_ROOT/data/qwen3-embedding-4b-2560/local/parsed"
export DOCS_INDEX_DIR="$PROJECT_ROOT/data/qwen3-embedding-4b-2560/local/index"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

expected_staging_invocation_journal() {
    journalctl --user \
        -u "$ACTIVE_STAGING_SERVICE" \
        "_SYSTEMD_INVOCATION_ID=$EXPECTED_STAGING_INVOCATION_ID" \
        --no-pager \
        -o cat
}

current_staging_invocation_id="$(systemctl --user show \
    "$ACTIVE_STAGING_SERVICE" --property=InvocationID --value)"
if [ -n "$current_staging_invocation_id" ] \
    && [ "$current_staging_invocation_id" != "$EXPECTED_STAGING_INVOCATION_ID" ]; then
    echo "Active staging invocation does not match the queued predecessor" >&2
    exit 1
fi

while true; do
    if ! active_staging_state="$(systemctl --user show \
        "$ACTIVE_STAGING_SERVICE" --property=ActiveState --value 2>/dev/null)"; then
        echo "Could not read the staging predecessor state before completion" >&2
        exit 1
    fi
    current_staging_invocation_id="$(systemctl --user show \
        "$ACTIVE_STAGING_SERVICE" --property=InvocationID --value 2>/dev/null)"
    if [ -n "$current_staging_invocation_id" ] \
        && [ "$current_staging_invocation_id" != "$EXPECTED_STAGING_INVOCATION_ID" ]; then
        echo "A different staging invocation replaced the queued predecessor" >&2
        exit 1
    fi
    case "$active_staging_state" in
        active|activating|reloading|deactivating)
            sleep "$QUEUE_POLL_INTERVAL_SECONDS"
            ;;
        inactive)
            break
            ;;
        *)
            echo "Staging predecessor ended in unexpected state: $active_staging_state" >&2
            exit 1
            ;;
    esac
done

if ! expected_staging_invocation_journal | grep -Fq 'LOCAL_STAGING_COMPLETE'; then
    echo "Current local embedding backlog lacks LOCAL_STAGING_COMPLETE" >&2
    exit 1
fi
printf '%s\n' "$EXPECTED_STAGING_INVOCATION_ID" > "$STAGING_INVOCATION_RECEIPT.next"
mv -- "$STAGING_INVOCATION_RECEIPT.next" "$STAGING_INVOCATION_RECEIPT"

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIRECTORY/lib/common_qdrant.sh"

./scripts/fetch_all_docs.sh --doc-sets="$QUEUED_DOCUMENTATION_SOURCES"

acquire_qdrant_writer_lease
echo "QUEUED_PLATFORM_STAGING_START $(date -u '+%Y-%m-%dT%H:%M:%SZ') SETS=all"
./scripts/process_all_to_qdrant.sh --doc-sets=all
echo "QUEUED_PLATFORM_STAGING_COMPLETE $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
