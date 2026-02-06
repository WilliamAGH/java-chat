#!/bin/bash

# GitHub repository ingestion pipeline.
# Supports single-repository ingestion (local path or GitHub URL) and
# batch synchronization of existing github-* Qdrant collections.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
LOG_TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG_FILE="$PROJECT_ROOT/process_github_repo_${LOG_TIMESTAMP}_$$.log"
LATEST_LOG_LINK="$PROJECT_ROOT/process_github_repo.log"

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIR/lib/common_qdrant.sh"

REPO_PATH="${REPO_PATH:-}"
REPO_URL="${REPO_URL:-}"
REPO_CACHE_DIR="${REPO_CACHE_DIR:-$PROJECT_ROOT/data/repos/github}"
REPO_CACHE_PATH="${REPO_CACHE_PATH:-}"
SYNC_EXISTING="${SYNC_EXISTING:-0}"

for argument in "$@"; do
    case "$argument" in
        --repo-path=*)
            REPO_PATH="${argument#*=}"
            ;;
        --repo-url=*)
            REPO_URL="${argument#*=}"
            ;;
        --repo-cache-dir=*)
            REPO_CACHE_DIR="${argument#*=}"
            ;;
        --repo-cache-path=*)
            REPO_CACHE_PATH="${argument#*=}"
            ;;
        --sync-existing)
            SYNC_EXISTING="1"
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
            echo "  --sync-existing            Sync all github-* collections by repo URL + remote HEAD"
            exit 0
            ;;
        *)
            echo "Unknown option: $argument"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

normalize_repository_url() {
    local raw_url="$1"
    echo "$raw_url" \
        | sed -E 's|^git@github.com:|https://github.com/|' \
        | sed -E 's|^ssh://git@github.com/|https://github.com/|' \
        | sed -E 's|\.git$||' \
        | sed -E 's|/$||'
}

short_sha256() {
    local raw_value="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        printf '%s' "$raw_value" | sha256sum | awk '{print substr($1,1,8)}'
        return
    fi
    if command -v shasum >/dev/null 2>&1; then
        printf '%s' "$raw_value" | shasum -a 256 | awk '{print substr($1,1,8)}'
        return
    fi
    python3 - "$raw_value" <<'PY'
import hashlib
import sys
print(hashlib.sha256(sys.argv[1].encode("utf-8")).hexdigest()[:8])
PY
}

sanitize_collection_segment() {
    local raw_segment="$1"
    echo "$raw_segment" \
        | sed -E 's|[^a-z0-9-]+|-|g; s|-+|-|g; s|^-||; s|-$||'
}

encode_collection_segment() {
    local raw_segment="$1"
    local sanitized_segment
    sanitized_segment="$(sanitize_collection_segment "$raw_segment")"
    if [[ "$raw_segment" =~ ^[a-z0-9-]+$ ]] && [ "$raw_segment" = "$sanitized_segment" ]; then
        echo "$sanitized_segment"
        return
    fi
    local hash_suffix
    hash_suffix="$(short_sha256 "$raw_segment")"
    echo "${sanitized_segment}-h${hash_suffix}"
}

extract_repository_identity() {
    local repository_url="$1"
    local normalized_repository_url
    normalized_repository_url="$(normalize_repository_url "$repository_url")"

    if [[ ! "$normalized_repository_url" =~ ^https://github\.com/[^/]+/[^/]+$ ]]; then
        echo -e "${RED}Error: repository URL must match https://github.com/<owner>/<repo>${NC}"
        exit 1
    fi

    REPOSITORY_OWNER="$(echo "$normalized_repository_url" | cut -d'/' -f4 | tr '[:upper:]' '[:lower:]')"
    REPOSITORY_NAME="$(echo "$normalized_repository_url" | cut -d'/' -f5 | tr '[:upper:]' '[:lower:]')"
    REPOSITORY_KEY="$REPOSITORY_OWNER/$REPOSITORY_NAME"
    REPOSITORY_URL="https://github.com/$REPOSITORY_KEY"
    CANONICAL_COLLECTION_NAME="github-$(encode_collection_segment "$REPOSITORY_OWNER")-$(encode_collection_segment "$REPOSITORY_NAME")"
}

ensure_repository_cache_clone() {
    local repository_url="$1"
    local explicit_cache_path="$2"
    extract_repository_identity "$repository_url"

    local cache_path=""
    if [ -n "$explicit_cache_path" ]; then
        cache_path="$explicit_cache_path"
    else
        cache_path="$REPO_CACHE_DIR/$REPOSITORY_OWNER/$REPOSITORY_NAME"
    fi
    mkdir -p "$(dirname "$cache_path")"

    if [ -d "$cache_path/.git" ]; then
        echo -e "${YELLOW}Refreshing cached clone: $cache_path${NC}"
        git -C "$cache_path" remote set-url origin "$REPOSITORY_URL" >/dev/null 2>&1
        git -C "$cache_path" fetch origin --prune >/dev/null

        local default_branch
        default_branch="$(git -C "$cache_path" symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null | sed 's|^origin/||')"
        if [ -z "$default_branch" ]; then
            default_branch="$(git -C "$cache_path" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"
        fi

        if [ -n "$default_branch" ]; then
            if git -C "$cache_path" show-ref --verify --quiet "refs/heads/$default_branch"; then
                git -C "$cache_path" checkout "$default_branch" >/dev/null 2>&1
            else
                git -C "$cache_path" checkout -B "$default_branch" "origin/$default_branch" >/dev/null 2>&1
            fi
            git -C "$cache_path" pull --ff-only origin "$default_branch" >/dev/null 2>&1
        else
            echo -e "${RED}Error: unable to resolve default branch for cached clone: $cache_path${NC}"
            exit 1
        fi
    else
        echo -e "${YELLOW}Cloning repository into cache: $REPOSITORY_URL${NC}"
        git clone "$REPOSITORY_URL" "$cache_path" >/dev/null
    fi

    echo "$cache_path"
}

resolve_repository_metadata_from_path() {
    local resolved_path="$1"
    if [ ! -d "$resolved_path/.git" ]; then
        echo -e "${RED}Error: Not a git repository: $resolved_path${NC}"
        exit 1
    fi

    local remote_url=""
    if git -C "$resolved_path" remote get-url origin >/dev/null 2>&1; then
        remote_url="$(git -C "$resolved_path" remote get-url origin)"
    fi

    if [ -n "$REPO_URL" ]; then
        extract_repository_identity "$REPO_URL"
    elif [ -n "$remote_url" ]; then
        extract_repository_identity "$remote_url"
    else
        echo -e "${RED}Error: Unable to resolve GitHub owner/repository from local path; origin remote is required${NC}"
        exit 1
    fi

    REPOSITORY_BRANCH="$(git -C "$resolved_path" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"
    REPOSITORY_COMMIT="$(git -C "$resolved_path" rev-parse HEAD 2>/dev/null || echo "")"

    REPOSITORY_LICENSE=""
    for license_file in LICENSE LICENSE.md LICENSE.txt LICENCE LICENCE.md; do
        if [ -f "$resolved_path/$license_file" ]; then
            local first_line
            first_line="$(head -1 "$resolved_path/$license_file" 2>/dev/null || true)"
            case "$first_line" in
                *MIT*) REPOSITORY_LICENSE="MIT" ;;
                *Apache*2*) REPOSITORY_LICENSE="Apache-2.0" ;;
                *GPL*3*) REPOSITORY_LICENSE="GPL-3.0" ;;
                *GPL*2*) REPOSITORY_LICENSE="GPL-2.0" ;;
                *BSD*3*) REPOSITORY_LICENSE="BSD-3-Clause" ;;
                *BSD*2*) REPOSITORY_LICENSE="BSD-2-Clause" ;;
                *ISC*) REPOSITORY_LICENSE="ISC" ;;
                *MPL*2*) REPOSITORY_LICENSE="MPL-2.0" ;;
                *LGPL*) REPOSITORY_LICENSE="LGPL-3.0" ;;
                *AGPL*) REPOSITORY_LICENSE="AGPL-3.0" ;;
                *Unlicense*) REPOSITORY_LICENSE="Unlicense" ;;
            esac
            break
        fi
    done

    REPOSITORY_DESCRIPTION=""
    if command -v gh >/dev/null 2>&1; then
        REPOSITORY_DESCRIPTION="$(gh repo view "$REPOSITORY_KEY" --json description -q '.description' 2>/dev/null || true)"
    fi
}

qdrant_auth_header() {
    if [ -n "${QDRANT_API_KEY:-}" ]; then
        echo "api-key: $QDRANT_API_KEY"
    fi
}

print_processing_failure_summary() {
    local log_file_path="$1"

    echo ""
    echo -e "${RED}Failure summary (from log):${NC}"

    local failure_headline
    failure_headline="$(grep -E "Embedding failed for batch|Remote embedding provider returned HTTP|Remote embedding response validation failed|Remote embedding call failed|Qdrant operation failed|Application run failed|GitHub repo processing completed with [0-9]+ failed file\(s\)" "$log_file_path" | tail -n 1 || true)"
    if [ -n "$failure_headline" ]; then
        echo "  $failure_headline"
    fi

    local root_cause_line
    root_cause_line="$(grep -E "Caused by:" "$log_file_path" | tail -n 1 || true)"
    if [ -n "$root_cause_line" ]; then
        echo ""
        echo "Root cause:"
        echo "  $root_cause_line"
    fi

    local retry_trace
    retry_trace="$(grep -E "transient HTTP|retrying in [0-9]+ms|Retryable OpenAI SDK failure|response validation failed|STEP 1: EMBEDDING GENERATION - Failed" "$log_file_path" | tail -n 12 || true)"
    if [ -n "$retry_trace" ]; then
        echo ""
        echo "Recent retry/error trace:"
        echo "$retry_trace"
    fi

    echo ""
    echo "Last 20 log lines:"
    tail -n 20 "$log_file_path" || true
}

ensure_github_payload_indexes() {
    local collection_name="$1"
    local qdrant_base_url="$2"

    echo -e "${YELLOW}Ensuring payload indexes for '$collection_name'...${NC}"
    local field_name
    for field_name in url hash chunkIndex docSet docPath sourceName sourceKind docVersion docType filePath language repoUrl repoOwner repoName repoKey repoBranch commitHash license repoDescription; do
        local field_type='{"type": "keyword"}'
        if [ "$field_name" = "chunkIndex" ]; then
            field_type='{"type": "integer"}'
        fi

        if [ -n "$(qdrant_auth_header)" ]; then
            curl -s -o /dev/null -X PUT \
                -H "Content-Type: application/json" \
                -H "$(qdrant_auth_header)" \
                -d "{\"field_name\": \"$field_name\", \"field_schema\": $field_type}" \
                "$qdrant_base_url/collections/$collection_name/index" 2>/dev/null || true
        else
            curl -s -o /dev/null -X PUT \
                -H "Content-Type: application/json" \
                -d "{\"field_name\": \"$field_name\", \"field_schema\": $field_type}" \
                "$qdrant_base_url/collections/$collection_name/index" 2>/dev/null || true
        fi
    done
    echo -e "${GREEN}Payload indexes ensured${NC}"
}

ensure_collection_exists() {
    local collection_name="$1"
    local qdrant_base_url="$2"

    local collection_status
    if [ -n "$(qdrant_auth_header)" ]; then
        collection_status="$(curl -s -o /dev/null -w "%{http_code}" \
            -H "$(qdrant_auth_header)" \
            "$qdrant_base_url/collections/$collection_name" 2>/dev/null || echo "000")"
    else
        collection_status="$(curl -s -o /dev/null -w "%{http_code}" \
            "$qdrant_base_url/collections/$collection_name" 2>/dev/null || echo "000")"
    fi

    if [ "$collection_status" = "200" ]; then
        echo -e "${GREEN}Collection '$collection_name' already exists${NC}"
        ensure_github_payload_indexes "$collection_name" "$qdrant_base_url"
        return
    fi

    echo -e "${YELLOW}Creating collection '$collection_name'...${NC}"

    local reference_collection="${QDRANT_REFERENCE_COLLECTION:-java-docs}"
    local reference_info
    if [ -n "$(qdrant_auth_header)" ]; then
        reference_info="$(curl -s -H "$(qdrant_auth_header)" \
            "$qdrant_base_url/collections/$reference_collection" 2>/dev/null || echo "")"
    else
        reference_info="$(curl -s "$qdrant_base_url/collections/$reference_collection" 2>/dev/null || echo "")"
    fi

    if [ -z "$reference_info" ]; then
        echo -e "${RED}Failed to read reference collection '$reference_collection'${NC}"
        exit 1
    fi

    local vectors_config
    vectors_config="$(echo "$reference_info" | python3 -c '
import json
import sys
collection_info = json.load(sys.stdin)
params = collection_info["result"]["config"]["params"]
vectors = params.get("vectors", {})
sparse_vectors = params.get("sparse_vectors", {})
print(json.dumps({"vectors": vectors, "sparse_vectors": sparse_vectors, "on_disk_payload": True}))
' 2>/dev/null || echo "")"

    if [ -z "$vectors_config" ]; then
        echo -e "${RED}Failed to extract vector config from reference collection${NC}"
        exit 1
    fi

    local create_response
    if [ -n "$(qdrant_auth_header)" ]; then
        create_response="$(curl -s -w "\n%{http_code}" \
            -X PUT \
            -H "Content-Type: application/json" \
            -H "$(qdrant_auth_header)" \
            -d "$vectors_config" \
            "$qdrant_base_url/collections/$collection_name" 2>/dev/null || echo "")"
    else
        create_response="$(curl -s -w "\n%{http_code}" \
            -X PUT \
            -H "Content-Type: application/json" \
            -d "$vectors_config" \
            "$qdrant_base_url/collections/$collection_name" 2>/dev/null || echo "")"
    fi

    local create_http_code
    create_http_code="$(echo "$create_response" | tail -1)"
    local create_body
    # Use sed instead of `head -n -1` for BSD/macOS portability.
    create_body="$(echo "$create_response" | sed '$d')"

    if [ "$create_http_code" != "200" ]; then
        echo -e "${RED}Failed to create collection (HTTP $create_http_code): $create_body${NC}"
        exit 1
    fi

    echo -e "${GREEN}Collection '$collection_name' created${NC}"
    ensure_github_payload_indexes "$collection_name" "$qdrant_base_url"
}

collection_point_count() {
    local collection_name="$1"
    local qdrant_base_url="$2"

    local collection_info
    if [ -n "$(qdrant_auth_header)" ]; then
        collection_info="$(curl -s -H "$(qdrant_auth_header)" \
            "$qdrant_base_url/collections/$collection_name" 2>/dev/null || echo "")"
    else
        collection_info="$(curl -s "$qdrant_base_url/collections/$collection_name" 2>/dev/null || echo "")"
    fi

    if [ -z "$collection_info" ]; then
        echo "unknown"
        return
    fi

    echo "$collection_info" | python3 -c '
import json
import sys
collection_info = json.load(sys.stdin)
print(collection_info.get("result", {}).get("points_count", "unknown"))
' 2>/dev/null || echo "unknown"
}

write_ingestion_manifest() {
    local collection_name="$1"
    local repository_path="$2"

    local manifest_dir="$PROJECT_ROOT/data/github-manifests"
    mkdir -p "$manifest_dir"
    local manifest_file="$manifest_dir/${collection_name}.manifest"

    cat > "$manifest_file" <<MANIFEST_EOF
repo_path="$repository_path"
collection_name="$collection_name"
repo_url="$REPOSITORY_URL"
repo_owner="$REPOSITORY_OWNER"
repo_name="$REPOSITORY_NAME"
repo_branch="$REPOSITORY_BRANCH"
last_commit="$REPOSITORY_COMMIT"
last_ingested="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
MANIFEST_EOF

    echo -e "${GREEN}Manifest written: $manifest_file${NC}"
}

run_single_ingestion() {
    local repository_path="$1"
    local collection_name="$2"
    local qdrant_base_url="$3"
    local app_jar="$4"

    resolve_repository_metadata_from_path "$repository_path"
    if [ "$collection_name" != "$CANONICAL_COLLECTION_NAME" ]; then
        echo -e "${RED}Error: Collection naming mismatch for $REPOSITORY_URL${NC}"
        echo -e "${RED}Expected canonical collection: $CANONICAL_COLLECTION_NAME${NC}"
        echo -e "${RED}Received collection: $collection_name${NC}"
        exit 1
    fi

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

    ensure_collection_exists "$collection_name" "$qdrant_base_url"

    export GITHUB_REPO_PATH="$repository_path"
    export GITHUB_REPO_OWNER="$REPOSITORY_OWNER"
    export GITHUB_REPO_NAME="$REPOSITORY_NAME"
    export GITHUB_COLLECTION_NAME="$collection_name"
    export GITHUB_REPO_URL="$REPOSITORY_URL"
    export GITHUB_REPO_BRANCH="$REPOSITORY_BRANCH"
    export GITHUB_REPO_COMMIT="$REPOSITORY_COMMIT"
    export GITHUB_REPO_LICENSE="$REPOSITORY_LICENSE"
    export GITHUB_REPO_DESCRIPTION="$REPOSITORY_DESCRIPTION"

    echo -e "${YELLOW}Starting GitHub repository processor...${NC}"
    echo "[command] java -Dspring.profiles.active=cli-github -jar $app_jar --spring.main.web-application-type=none --server.port=0" >> "$LOG_FILE"
    if ! java -Dspring.profiles.active=cli-github -jar "$app_jar" \
        --spring.main.web-application-type=none \
        --server.port=0 >> "$LOG_FILE" 2>&1; then
        echo -e "${RED}GitHub repository processing failed${NC}"
        print_processing_failure_summary "$LOG_FILE"
        echo "Log file: $LOG_FILE"
        exit 1
    fi

    local point_count
    point_count="$(collection_point_count "$collection_name" "$qdrant_base_url")"

    write_ingestion_manifest "$collection_name" "$repository_path"

    echo ""
    echo -e "${GREEN}Pipeline completed${NC}"
    echo "Collection: $collection_name"
    echo "Points in collection: $point_count"
    echo "Log file: $LOG_FILE"
}

list_github_collections() {
    local qdrant_base_url="$1"
    local collection_payload

    if [ -n "$(qdrant_auth_header)" ]; then
        collection_payload="$(curl -s -H "$(qdrant_auth_header)" "$qdrant_base_url/collections" 2>/dev/null || echo "")"
    else
        collection_payload="$(curl -s "$qdrant_base_url/collections" 2>/dev/null || echo "")"
    fi

    if [ -z "$collection_payload" ]; then
        return
    fi

    echo "$collection_payload" | python3 -c '
import json
import sys
payload = json.load(sys.stdin)
for collection in payload.get("result", {}).get("collections", []):
    collection_name = collection.get("name", "")
    if collection_name.startswith("github-"):
        print(collection_name)
' 2>/dev/null || true
}

read_collection_repository_metadata() {
    local collection_name="$1"
    local qdrant_base_url="$2"

    local scroll_payload='{"limit":1,"with_payload":["repoUrl","repoKey","commitHash"],"with_vector":false}'
    local response_body

    if [ -n "$(qdrant_auth_header)" ]; then
        response_body="$(curl -s -X POST \
            -H "Content-Type: application/json" \
            -H "$(qdrant_auth_header)" \
            -d "$scroll_payload" \
            "$qdrant_base_url/collections/$collection_name/points/scroll" 2>/dev/null || echo "")"
    else
        response_body="$(curl -s -X POST \
            -H "Content-Type: application/json" \
            -d "$scroll_payload" \
            "$qdrant_base_url/collections/$collection_name/points/scroll" 2>/dev/null || echo "")"
    fi

    if [ -z "$response_body" ]; then
        echo "||"
        return
    fi

    echo "$response_body" | python3 -c '
import json
import sys
payload = json.load(sys.stdin)
points = payload.get("result", {}).get("points", [])
if not points:
    print("||")
    raise SystemExit(0)
metadata = points[0].get("payload", {})
repository_url = (metadata.get("repoUrl") or "").strip()
repository_key = (metadata.get("repoKey") or "").strip()
stored_commit = (metadata.get("commitHash") or "").strip()
print(f"{repository_url}|{repository_key}|{stored_commit}")
' 2>/dev/null || echo "||"
}

remote_head_commit() {
    local repository_url="$1"
    git ls-remote "$repository_url" HEAD 2>/dev/null | awk '{print $1}'
}

sync_existing_collections() {
    local qdrant_base_url="$1"
    local app_jar="$2"

    local github_collections
    github_collections="$(list_github_collections "$qdrant_base_url")"

    if [ -z "$github_collections" ]; then
        echo -e "${YELLOW}No github-* collections found in Qdrant${NC}"
        return
    fi

    local processed_collections=0
    local unchanged_collections=0
    local skipped_collections=0

    while IFS= read -r collection_name; do
        [ -z "$collection_name" ] && continue

        local metadata_line
        metadata_line="$(read_collection_repository_metadata "$collection_name" "$qdrant_base_url")"
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
            echo -e "${YELLOW}Skipping $collection_name (missing repoUrl/repoKey payload metadata)${NC}"
            skipped_collections=$((skipped_collections + 1))
            continue
        fi

        local remote_commit
        remote_commit="$(remote_head_commit "$stored_repository_url")"
        if [ -z "$remote_commit" ]; then
            echo -e "${YELLOW}Skipping $collection_name (unable to resolve remote HEAD for $stored_repository_url)${NC}"
            skipped_collections=$((skipped_collections + 1))
            continue
        fi

        if [ -n "$stored_commit" ] && [ "$stored_commit" = "$remote_commit" ]; then
            echo -e "${GREEN}No upstream changes for $collection_name ($stored_repository_url)${NC}"
            unchanged_collections=$((unchanged_collections + 1))
            continue
        fi

        echo -e "${CYAN}Syncing updated repository for $collection_name: $stored_repository_url${NC}"
        local cached_repository_path
        cached_repository_path="$(ensure_repository_cache_clone "$stored_repository_url" "")"

        extract_repository_identity "$stored_repository_url"
        if [ "$collection_name" != "$CANONICAL_COLLECTION_NAME" ]; then
            echo -e "${RED}Collection '$collection_name' does not match canonical name '$CANONICAL_COLLECTION_NAME'${NC}"
            exit 1
        fi

        local previous_repo_url="$REPO_URL"
        REPO_URL="$stored_repository_url"
        run_single_ingestion "$cached_repository_path" "$collection_name" "$qdrant_base_url" "$app_jar"
        REPO_URL="$previous_repo_url"

        processed_collections=$((processed_collections + 1))
    done <<< "$github_collections"

    echo ""
    echo "=============================================="
    echo "GitHub Collection Sync Summary"
    echo "=============================================="
    echo "Collections updated: $processed_collections"
    echo "Collections unchanged: $unchanged_collections"
    echo "Collections skipped: $skipped_collections"
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

echo -e "${YELLOW}Building application...${NC}"
build_application "$LOG_FILE"
APP_JAR="$(locate_app_jar)"
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
