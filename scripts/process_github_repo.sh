#!/bin/bash

# GitHub repository ingestion pipeline.
#
# Modes:
#   --repo-path=PATH         Ingest from a local git clone
#   --repo-url=URL           Clone/refresh from GitHub, then ingest
#   --sync-existing          Re-ingest active environment/generation collections with upstream changes
#
# Prerequisites:
#   - Qdrant vector store reachable (QDRANT_HOST, QDRANT_PORT)
#   - Embedding provider configured (remote or local via APP_LOCAL_EMBEDDING_ENABLED)
#   - .env file at project root with required variables
#   - Gradle build environment (JDK 21+)
#
# Environment variables (loaded from .env):
#   QDRANT_HOST, QDRANT_PORT, QDRANT_API_KEY (optional), QDRANT_SSL (optional)
#   APP_LOCAL_EMBEDDING_ENABLED, OPENAI_BASE_URL, OPENAI_API_KEY
# Non-secret embedding endpoint and model settings are read from application.properties.
#
# Exit codes:
#   0 - Success
#   1 - Configuration error, connectivity failure, or ingestion failure

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
LOG_TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_FILE="$PROJECT_ROOT/process_github_repo_${LOG_TIMESTAMP}_$$.log"
LATEST_LOG_LINK="$PROJECT_ROOT/process_github_repo.log"

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIR/lib/common_qdrant.sh"
# shellcheck source=lib/github_identity.sh
source "$SCRIPT_DIR/lib/github_identity.sh"
# shellcheck source=lib/ingestion_diagnostics.sh
source "$SCRIPT_DIR/lib/ingestion_diagnostics.sh"

ARG_REPO_PATH=""
ARG_REPO_URL=""
ARG_REPO_CACHE_DIR=""
ARG_REPO_CACHE_PATH=""
ARG_SYNC_EXISTING="0"

for argument in "$@"; do
    case "$argument" in
        --repo-path=*)
            ARG_REPO_PATH="${argument#*=}"
            ;;
        --repo-url=*)
            ARG_REPO_URL="${argument#*=}"
            ;;
        --repo-cache-dir=*)
            ARG_REPO_CACHE_DIR="${argument#*=}"
            ;;
        --repo-cache-path=*)
            ARG_REPO_CACHE_PATH="${argument#*=}"
            ;;
        --sync-existing)
            ARG_SYNC_EXISTING="1"
            ;;
        --help|-h)
            echo "Usage: $0 [--repo-path=PATH | --repo-url=URL | --sync-existing]"
            echo ""
            echo "Single repository options:"
            echo "  --repo-path=PATH           Path to local git repository clone"
            echo "  --repo-url=URL             GitHub repository URL (auto clone/pull cache)"
            echo "  --repo-cache-dir=PATH      Local cache root for URL mode (default: data/repos/github)"
            echo "  --repo-cache-path=PATH     Exact local clone path for URL mode (single repo only)"
            echo ""
            echo "Batch options:"
            echo "  --sync-existing            Sync active environment/generation collections by repo URL + remote HEAD"
            exit 0
            ;;
        *)
            echo "Unknown option: $argument"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

prepare_pipeline_environment "QDRANT_HOST" "QDRANT_PORT" "SPRING_PROFILE" "QDRANT_COLLECTION_DOCS" \
    "DOCS_SNAPSHOT_DIR" "DOCS_PARSED_DIR" "DOCS_INDEX_DIR"

case "$SPRING_PROFILE" in
    local|dev|prod) ;;
    *)
        echo "SPRING_PROFILE must be exactly local, dev, or prod" >&2
        exit 1
        ;;
esac
EXPECTED_DOCUMENTATION_COLLECTION="java-chat-${SPRING_PROFILE}-qwen3-embedding-4b-2560-docs"
if [ "$QDRANT_COLLECTION_DOCS" != "$EXPECTED_DOCUMENTATION_COLLECTION" ]; then
    echo "QDRANT_COLLECTION_DOCS must match SPRING_PROFILE and the 4B/2560 generation" >&2
    exit 1
fi
for configured_state_directory in "$DOCS_SNAPSHOT_DIR:snapshots" "$DOCS_PARSED_DIR:parsed" "$DOCS_INDEX_DIR:index"; do
    state_directory="${configured_state_directory%:*}"
    state_leaf_directory="${configured_state_directory##*:}"
    required_state_suffix="qwen3-embedding-4b-2560/${SPRING_PROFILE}/${state_leaf_directory}"
    if [[ "$state_directory" != *"/$required_state_suffix" ]]; then
        echo "Ingestion state paths must match SPRING_PROFILE and the 4B/2560 generation" >&2
        exit 1
    fi
done

# Effective precedence:
# 1) CLI arguments
# 2) exported environment variables
# 3) .env values
# 4) script defaults
REPO_PATH="${ARG_REPO_PATH:-${REPO_PATH:-}}"
REPO_URL="${ARG_REPO_URL:-${REPO_URL:-}}"
REPO_CACHE_DIR="${ARG_REPO_CACHE_DIR:-${REPO_CACHE_DIR:-$PROJECT_ROOT/data/repos/github}}"
REPO_CACHE_PATH="${ARG_REPO_CACHE_PATH:-${REPO_CACHE_PATH:-}}"
if [ "$ARG_SYNC_EXISTING" = "1" ]; then
    SYNC_EXISTING="1"
else
    SYNC_EXISTING="${SYNC_EXISTING:-0}"
fi

if [ -n "$REPO_PATH" ]; then
    REPO_PATH="$(expand_user_path "$REPO_PATH")"
fi
REPO_CACHE_DIR="$(expand_user_path "$REPO_CACHE_DIR")"
if [ -n "$REPO_CACHE_PATH" ]; then
    REPO_CACHE_PATH="$(expand_user_path "$REPO_CACHE_PATH")"
fi

# Ensures a Qdrant collection exists with the correct vector config and
# payload indexes. Creates the collection from a reference if missing.
ensure_collection_exists() {
    local collection_name="$1"
    local qdrant_base_url="$2"

    local collection_status
    collection_status="$(qdrant_curl -s --connect-timeout 5 --max-time 20 -o /dev/null -w "%{http_code}" \
        "$qdrant_base_url/collections/$collection_name" 2>/dev/null || true)"
    if [ -z "$collection_status" ]; then
        collection_status="000"
    fi

    if [ "$collection_status" = "200" ]; then
        echo -e "${GREEN}Collection '$collection_name' already exists${NC}"
        require_qwen_generation_collection_schema "$collection_name" "$qdrant_base_url"
        ensure_github_payload_indexes "$collection_name" "$qdrant_base_url"
        return
    fi

    echo -e "${YELLOW}Creating collection '$collection_name'...${NC}"
    local reference_collection="${QDRANT_COLLECTION_DOCS:-}"
    if [ -z "$reference_collection" ]; then
        echo -e "${RED}QDRANT_COLLECTION_DOCS is required to clone the active documentation schema${NC}"
        exit 1
    fi
    create_collection_from_reference "$collection_name" "$qdrant_base_url" "$reference_collection"
    require_qwen_generation_collection_schema "$collection_name" "$qdrant_base_url"
    ensure_github_payload_indexes "$collection_name" "$qdrant_base_url"
}

export_github_environment_variables() {
    local repository_path="$1"
    local collection_name="$2"

    export GITHUB_REPO_PATH="$repository_path"
    export GITHUB_REPO_OWNER="$REPOSITORY_OWNER"
    export GITHUB_REPO_NAME="$REPOSITORY_NAME"
    export GITHUB_COLLECTION_NAME="$collection_name"
    export GITHUB_REPO_URL="$REPOSITORY_URL"
    export GITHUB_REPO_BRANCH="$REPOSITORY_BRANCH"
    export GITHUB_REPO_COMMIT="$REPOSITORY_COMMIT"
    export GITHUB_REPO_LICENSE="$REPOSITORY_LICENSE"
    export GITHUB_REPO_DESCRIPTION="$REPOSITORY_DESCRIPTION"
}

print_ingestion_banner() {
    local repository_path="$1"
    local collection_name="$2"

    echo "[$(date)] Starting GitHub repository processing" > "$LOG_FILE"
    ln -sf "$(basename "$LOG_FILE")" "$LATEST_LOG_LINK" 2>/dev/null || true
    echo "=============================================="
    echo "GitHub Repository Ingestion Pipeline"
    echo "=============================================="
    echo -e "${CYAN}Repository: ${YELLOW}$REPOSITORY_KEY${NC}"
    echo "Path: $repository_path"
    echo "Collection: $collection_name"
    echo "URL: $REPOSITORY_URL"
    [ -n "$REPOSITORY_BRANCH" ] && echo "Branch: $REPOSITORY_BRANCH"
    [ -n "$REPOSITORY_COMMIT" ] && echo "Commit: ${REPOSITORY_COMMIT:0:12}"
    [ -n "$REPOSITORY_LICENSE" ] && echo "License: $REPOSITORY_LICENSE"
    echo ""
}

run_single_ingestion() {
    local repository_path="$1"
    local collection_name="$2"
    local qdrant_base_url="$3"
    local app_jar="$4"

    resolve_repository_metadata_from_path "$repository_path"
    require_canonical_collection_name "$collection_name" "$REPOSITORY_URL"

    print_ingestion_banner "$repository_path" "$collection_name"
    ensure_collection_exists "$collection_name" "$qdrant_base_url"
    export_github_environment_variables "$repository_path" "$collection_name"

    echo -e "${YELLOW}Starting GitHub repository processor...${NC}"
    echo "[command] java -Dspring.profiles.active=cli-github -jar $app_jar --spring.main.web-application-type=none --server.port=0" >> "$LOG_FILE"
    if ! java -Dspring.profiles.active=cli-github -jar "$app_jar" \
        --spring.main.web-application-type=none \
        --server.port=0 >> "$LOG_FILE" 2>&1; then
        echo -e "${RED}GitHub repository processing failed${NC}"
        print_processing_failure_summary "$LOG_FILE"
        exit 1
    fi

    local point_count
    point_count="$(collection_point_count "$collection_name" "$qdrant_base_url")"

    echo ""
    echo -e "${GREEN}Pipeline completed${NC}"
    echo "Collection: $collection_name"
    echo "Points in collection: $point_count"
    echo "Log file: $LOG_FILE"
}

# Evaluates a single collection for sync: resolves metadata, checks remote HEAD,
# and re-ingests if upstream has changed.
#
# Sets SYNC_COLLECTION_OUTCOME to "processed" or "unchanged".
# Must be called directly (not in a subshell) so that side effects from
# run_single_ingestion environment exports persist.
sync_single_collection() {
    local collection_name="$1"
    local qdrant_base_url="$2"
    local app_jar="$3"

    local metadata_line
    if ! metadata_line="$(read_collection_repository_metadata "$collection_name" "$qdrant_base_url")"; then
        echo "Unable to synchronize $collection_name because its Qdrant metadata could not be read" >&2
        return 1
    fi
    local stored_repository_url
    stored_repository_url="$(echo "$metadata_line" | cut -d'|' -f1)"
    local stored_repository_key
    stored_repository_key="$(echo "$metadata_line" | cut -d'|' -f2)"
    local stored_commit
    stored_commit="$(echo "$metadata_line" | cut -d'|' -f3)"

    if [ -z "$stored_repository_url" ] && [ -n "$stored_repository_key" ]; then
        stored_repository_url="https://github.com/$stored_repository_key"
    fi

    if [ -z "$stored_repository_url" ]; then
        echo "Collection $collection_name is missing required repoUrl/repoKey payload metadata" >&2
        return 1
    fi

    require_canonical_collection_name "$collection_name" "$stored_repository_url"

    local remote_commit
    if ! remote_commit="$(remote_head_commit "$stored_repository_url")"; then
        echo "Unable to synchronize $collection_name because remote HEAD resolution failed" >&2
        return 1
    fi

    if [ -n "$stored_commit" ] && [ "$stored_commit" = "$remote_commit" ]; then
        echo -e "${GREEN}No upstream changes for $collection_name ($stored_repository_url)${NC}"
        SYNC_COLLECTION_OUTCOME="unchanged"
        return
    fi

    echo -e "${CYAN}Syncing updated repository for $collection_name: $stored_repository_url${NC}"
    local cached_repository_path
    cached_repository_path="$(ensure_repository_cache_clone "$stored_repository_url" "")"

    local previous_repo_url="$REPO_URL"
    REPO_URL="$stored_repository_url"
    run_single_ingestion "$cached_repository_path" "$collection_name" "$qdrant_base_url" "$app_jar"
    REPO_URL="$previous_repo_url"

    SYNC_COLLECTION_OUTCOME="processed"
}

sync_existing_collections() {
    local qdrant_base_url="$1"
    local app_jar="$2"

    local github_collections
    if ! github_collections="$(list_github_collections "$qdrant_base_url")"; then
        echo "Unable to synchronize GitHub collections because Qdrant discovery failed" >&2
        return 1
    fi

    if [ -z "$github_collections" ]; then
        echo -e "${YELLOW}No active environment/generation GitHub collections found in Qdrant${NC}"
        return
    fi

    local processed_collections=0
    local unchanged_collections=0
    while IFS= read -r collection_name; do
        [ -z "$collection_name" ] && continue

        sync_single_collection "$collection_name" "$qdrant_base_url" "$app_jar"

        case "$SYNC_COLLECTION_OUTCOME" in
            processed) processed_collections=$((processed_collections + 1)) ;;
            unchanged) unchanged_collections=$((unchanged_collections + 1)) ;;
            *)
                echo "Unexpected GitHub synchronization outcome for $collection_name" >&2
                return 1
                ;;
        esac
    done <<< "$github_collections"

    echo ""
    echo "=============================================="
    echo "GitHub Collection Sync Summary"
    echo "=============================================="
    echo "Collections updated: $processed_collections"
    echo "Collections unchanged: $unchanged_collections"
}

if [ "$SYNC_EXISTING" = "1" ]; then
    if [ -n "$REPO_PATH" ] || [ -n "$REPO_URL" ] || [ -n "$REPO_CACHE_PATH" ]; then
        echo -e "${RED}Error: --sync-existing cannot be combined with --repo-path, --repo-url, or --repo-cache-path${NC}"
        exit 1
    fi
else
    if [ -n "$REPO_PATH" ] && [ -n "$REPO_URL" ]; then
        echo -e "${RED}Error: use either --repo-path or --repo-url, not both${NC}"
        exit 1
    fi
    if [ -z "$REPO_PATH" ] && [ -z "$REPO_URL" ]; then
        echo -e "${RED}Error: provide --repo-path, --repo-url, or --sync-existing${NC}"
        exit 1
    fi
    if [ -n "$REPO_CACHE_PATH" ] && [ -z "$REPO_URL" ]; then
        echo -e "${RED}Error: --repo-cache-path requires --repo-url${NC}"
        exit 1
    fi
fi

verify_pipeline_connections
build_application "$LOG_FILE"
SOURCE_APP_JAR="$(locate_app_jar)"
STAGED_APP_JAR_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/java-chat-github-ingestion.XXXXXX")"
cleanup_staged_app_jar() {
    chmod u+w "$STAGED_APP_JAR_DIRECTORY" "$STAGED_APP_JAR_DIRECTORY/application.jar" 2>/dev/null || true
    rm -rf "$STAGED_APP_JAR_DIRECTORY"
}
trap cleanup_staged_app_jar EXIT
APP_JAR="$(stage_app_jar "$SOURCE_APP_JAR" "$STAGED_APP_JAR_DIRECTORY")"
QDRANT_BASE_URL="$(qdrant_rest_base_url)"

if [ "$SYNC_EXISTING" = "1" ]; then
    sync_existing_collections "$QDRANT_BASE_URL" "$APP_JAR"
    exit 0
fi

if [ -n "$REPO_URL" ]; then
    RESOLVED_REPOSITORY_PATH="$(ensure_repository_cache_clone "$REPO_URL" "$REPO_CACHE_PATH")"
else
    RESOLVED_REPOSITORY_PATH="$(cd "$REPO_PATH" 2>/dev/null && pwd)" || {
        echo -e "${RED}Error: repository path does not exist: $REPO_PATH${NC}"
        exit 1
    }
fi

if [ ! -d "$RESOLVED_REPOSITORY_PATH/.git" ]; then
    echo -e "${RED}Error: Not a git repository: $RESOLVED_REPOSITORY_PATH${NC}"
    exit 1
fi

resolve_repository_metadata_from_path "$RESOLVED_REPOSITORY_PATH"
TARGET_COLLECTION_NAME="$CANONICAL_COLLECTION_NAME"
run_single_ingestion "$RESOLVED_REPOSITORY_PATH" "$TARGET_COLLECTION_NAME" "$QDRANT_BASE_URL" "$APP_JAR"
