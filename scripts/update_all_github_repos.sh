#!/bin/bash

# Batch synchronization of GitHub repository collections using manifest files.
# Checks each manifest's last_commit against the remote HEAD and re-ingests
# only repositories with upstream changes.
#
# Manifests are written by process_github_repo.sh after each successful ingestion.
# Falls back to --sync-existing (Qdrant scroll) when no manifests exist.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIR/lib/common_qdrant.sh"

MANIFEST_DIR="$PROJECT_ROOT/data/github-manifests"

load_env_file
validate_required_vars "QDRANT_HOST" "QDRANT_PORT" "APP_LOCAL_EMBEDDING_ENABLED"
echo ""

if ! check_qdrant_connection "echo -e"; then
    echo -e "${RED}Cannot proceed without Qdrant connectivity${NC}"
    exit 1
fi

if ! check_embedding_server "echo -e"; then
    echo -e "${RED}Embedding provider check failed${NC}"
    exit 1
fi

if [ ! -d "$MANIFEST_DIR" ] || [ -z "$(ls -A "$MANIFEST_DIR"/*.manifest 2>/dev/null)" ]; then
    echo -e "${YELLOW}No manifest files found in $MANIFEST_DIR${NC}"
    echo -e "${YELLOW}Falling back to Qdrant scroll-based sync...${NC}"
    exec "$SCRIPT_DIR/process_github_repo.sh" --sync-existing
fi

updated_count=0
unchanged_count=0
failed_count=0

for manifest_file in "$MANIFEST_DIR"/*.manifest; do
    [ -f "$manifest_file" ] || continue

    # Source manifest to get repo_path, repo_url, repo_branch, last_commit, collection_name
    # shellcheck disable=SC1090
    source "$manifest_file"

    if [ -z "${repo_url:-}" ] || [ -z "${collection_name:-}" ]; then
        echo -e "${YELLOW}Skipping malformed manifest: $manifest_file${NC}"
        failed_count=$((failed_count + 1))
        continue
    fi

    echo -e "${CYAN}Checking: ${collection_name} (${repo_url})${NC}"

    remote_head="$(git ls-remote "$repo_url" "refs/heads/${repo_branch:-HEAD}" 2>/dev/null | cut -f1 || true)"
    if [ -z "$remote_head" ]; then
        remote_head="$(git ls-remote "$repo_url" HEAD 2>/dev/null | cut -f1 || true)"
    fi

    if [ -z "$remote_head" ]; then
        echo -e "${YELLOW}  Unable to resolve remote HEAD; skipping${NC}"
        failed_count=$((failed_count + 1))
        continue
    fi

    if [ "${last_commit:-}" = "$remote_head" ]; then
        echo -e "${GREEN}  No changes (${last_commit:0:12})${NC}"
        unchanged_count=$((unchanged_count + 1))
        continue
    fi

    echo -e "${YELLOW}  Upstream changed: ${last_commit:0:12} -> ${remote_head:0:12}${NC}"

    if [ -n "${repo_path:-}" ] && [ -d "${repo_path}/.git" ]; then
        git -C "$repo_path" pull --ff-only origin "${repo_branch:-HEAD}" >/dev/null 2>&1 || true
        "$SCRIPT_DIR/process_github_repo.sh" --repo-path="$repo_path"
    else
        "$SCRIPT_DIR/process_github_repo.sh" --repo-url="$repo_url"
    fi

    updated_count=$((updated_count + 1))
done

echo ""
echo "=============================================="
echo "GitHub Batch Sync Summary"
echo "=============================================="
echo "Repositories updated: $updated_count"
echo "Repositories unchanged: $unchanged_count"
echo "Repositories skipped/failed: $failed_count"
