#!/bin/bash

# Generates every downloaded documentation and pinned-repository embedding through
# the configured gateway while persisting vectors exclusively in local Qdrant.

set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIRECTORY/.."
readonly STAGED_SCRIPT_DIRECTORY_PREFIX=".local-embedding-staging-run."
readonly EXPECTED_REPOSITORY_COUNT=23
readonly DOCUMENTATION_SETS="jooq/3.21/manual,jooq/3.21/api,python/3.14,postgresql/17,postgresql/18,hikaricp/7.1.0/api,hikaricp/7.0.2/api,jackson/2.22.2/api,jackson/2.21.2/api,jackson/3.2.2/api,jackson/3.1.2/api,lombok/1.18.46/api,lombok/1.18.46/reference,anthropic/api,anthropic/claude-code,amp-code,tinker,docker,traefik,dokploy,infisical,doppler/docs,doppler/reference,doppler/changelog,spring-framework/7.0.7/api"

cleanup_staged_scripts() {
    local staged_directory="$1"
    chmod -R u+w "$staged_directory" 2>/dev/null || true
    rm -rf -- "$staged_directory"
}

if [[ "$(basename "$SCRIPT_DIRECTORY")" == "$STAGED_SCRIPT_DIRECTORY_PREFIX"* ]]; then
    trap 'cleanup_staged_scripts "$SCRIPT_DIRECTORY"' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
else
    staged_script_directory="$(mktemp -d "$PROJECT_ROOT/$STAGED_SCRIPT_DIRECTORY_PREFIX"XXXXXX)"
    trap 'cleanup_staged_scripts "$staged_script_directory"' EXIT
    cp -R "$SCRIPT_DIRECTORY"/. "$staged_script_directory"/
    chmod -R a-w "$staged_script_directory"
    exec "$staged_script_directory/run_local_embedding_staging.sh" "$@"
fi

cd "$PROJECT_ROOT"

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIRECTORY/lib/common_qdrant.sh"

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

shopt -s nullglob
repository_git_directories=(data/repos/github/*/*/.git)
shopt -u nullglob
repository_paths=()
for repository_git_directory in "${repository_git_directories[@]}"; do
    repository_paths+=("${repository_git_directory%/.git}")
done
if [ "${#repository_paths[@]}" -ne "$EXPECTED_REPOSITORY_COUNT" ]; then
    echo "Expected $EXPECTED_REPOSITORY_COUNT pinned repositories, found ${#repository_paths[@]}" >&2
    exit 1
fi

for repository_path in "${repository_paths[@]}"; do
    if [ -n "$(git -C "$repository_path" status --porcelain=v1 --untracked-files=all)" ]; then
        echo "Pinned repository is dirty: $repository_path" >&2
        exit 1
    fi
done

acquire_qdrant_writer_lease

staging_timestamp() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
}

echo "LOCAL_STAGING_RESUME $(staging_timestamp) BATCH_SIZE=8 CONCURRENCY=8 DOCS=25 REPOS=$EXPECTED_REPOSITORY_COUNT"
"$SCRIPT_DIRECTORY/process_all_to_qdrant.sh" --doc-sets="$DOCUMENTATION_SETS"

for repository_path in "${repository_paths[@]}"; do
    echo "REPOSITORY_START $(staging_timestamp) $repository_path"
    "$SCRIPT_DIRECTORY/process_github_repo.sh" --repo-path="$repository_path"
    echo "REPOSITORY_COMPLETE $(staging_timestamp) $repository_path"
done

echo "LOCAL_STAGING_COMPLETE $(staging_timestamp)"
