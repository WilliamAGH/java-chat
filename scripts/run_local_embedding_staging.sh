#!/bin/bash

# Generates every downloaded documentation and pinned-repository embedding through
# the configured gateway while persisting vectors exclusively in local Qdrant.

set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIRECTORY/.."
readonly EXPECTED_REPOSITORY_COUNT=22
readonly DOCUMENTATION_SETS="jooq/3.21/manual,jooq/3.21/api,python/3.14,postgresql/17,postgresql/18,hikaricp/7.1.0/api,hikaricp/7.0.2/api,jackson/2.22.2/api,jackson/2.21.2/api,jackson/3.2.2/api,jackson/3.1.2/api,lombok/1.18.46/api,lombok/1.18.46/reference,anthropic/api,anthropic/claude-code,amp-code,tinker,docker,dokploy,infisical,doppler/docs,doppler/reference,doppler/changelog,spring-framework/7.0.7/api"

cd "$PROJECT_ROOT"

export SPRING_PROFILE=local
export APP_LOCAL_EMBEDDING_ENABLED=false
export APP_EMBEDDINGS_BATCH_MAX_CONCURRENT_REQUESTS=8
export APP_EMBEDDINGS_BATCH_REQUESTS_PER_SECOND=8.0
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

if [ -e process_qdrant.pid ]; then
    echo "Documentation ingestion already owns process_qdrant.pid" >&2
    exit 1
fi

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

staging_timestamp() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
}

echo "LOCAL_STAGING_RESUME $(staging_timestamp) BATCH_SIZE=8 CONCURRENCY=8 DOCS=24 REPOS=$EXPECTED_REPOSITORY_COUNT"
./scripts/process_all_to_qdrant.sh --doc-sets="$DOCUMENTATION_SETS"

for repository_path in "${repository_paths[@]}"; do
    echo "REPOSITORY_START $(staging_timestamp) $repository_path"
    ./scripts/process_github_repo.sh --repo-path="$repository_path"
    echo "REPOSITORY_COMPLETE $(staging_timestamp) $repository_path"
done

echo "LOCAL_STAGING_COMPLETE $(staging_timestamp)"
