#!/bin/bash

# GitHub repository identity, naming, caching, metadata resolution, and
# Qdrant schema management for GitHub-sourced collections.
#
# Dependencies: source common_qdrant.sh first (qdrant_curl, color constants).
#
# Global variables read at call time (set by the calling script):
#   REPO_CACHE_DIR, REPO_URL, PROJECT_ROOT, REPOSITORY_* family
#
# Usage: source "$SCRIPT_DIR/lib/github_identity.sh"

# ── Canonical naming ─────────────────────────────────────────────────

expand_user_path() {
    local raw_path="$1"
    case "$raw_path" in
        "~") echo "$HOME" ;;
        "~/"*) echo "$HOME/${raw_path#"~/"}" ;;
        *) echo "$raw_path" ;;
    esac
}

normalize_repository_url() {
    local raw_url="$1"
    echo "$raw_url" \
        | sed -E \
            -e 's|^git@github.com:|https://github.com/|' \
            -e 's|^ssh://git@github.com/|https://github.com/|' \
            -e 's|\.git$||' \
            -e 's|/$||'
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

# Extracts owner, name, key, URL, and canonical collection name from a
# GitHub repository URL. Sets globals: REPOSITORY_OWNER, REPOSITORY_NAME,
# REPOSITORY_KEY, REPOSITORY_URL, CANONICAL_COLLECTION_NAME.
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

# ── Cache management ─────────────────────────────────────────────────

# Clones or refreshes a cached git checkout for a GitHub repository.
# Prints the resolved cache path to stdout.
#
# Reads REPO_CACHE_DIR at call time for default cache root.
ensure_repository_cache_clone() {
    local repository_url="$1"
    local explicit_cache_path="$2"
    extract_repository_identity "$repository_url"

    local cache_path=""
    if [ -n "$explicit_cache_path" ]; then
        cache_path="$(expand_user_path "$explicit_cache_path")"
    else
        cache_path="$(expand_user_path "$REPO_CACHE_DIR")/$REPOSITORY_OWNER/$REPOSITORY_NAME"
    fi
    mkdir -p "$(dirname "$cache_path")"

    if [ -d "$cache_path/.git" ]; then
        echo -e "${YELLOW}Refreshing cached clone: $cache_path${NC}"
        git -C "$cache_path" remote set-url origin "$REPOSITORY_URL" >/dev/null 2>&1
        if ! git -C "$cache_path" fetch origin --prune >/dev/null 2>&1; then
            echo -e "${RED}Error: git fetch failed for cached clone: $cache_path${NC}"
            exit 1
        fi

        local default_branch
        default_branch="$(git -C "$cache_path" symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null | sed 's|^origin/||')"
        if [ -z "$default_branch" ]; then
            default_branch="$(git -C "$cache_path" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"
        fi

        if [ -n "$default_branch" ]; then
            if git -C "$cache_path" show-ref --verify --quiet "refs/heads/$default_branch"; then
                if ! git -C "$cache_path" checkout "$default_branch" >/dev/null 2>&1; then
                    echo -e "${RED}Error: git checkout '$default_branch' failed for cached clone: $cache_path${NC}"
                    exit 1
                fi
            else
                if ! git -C "$cache_path" checkout -B "$default_branch" "origin/$default_branch" >/dev/null 2>&1; then
                    echo -e "${RED}Error: git checkout -B '$default_branch' failed for cached clone: $cache_path${NC}"
                    exit 1
                fi
            fi
            if ! git -C "$cache_path" pull --ff-only origin "$default_branch" >/dev/null 2>&1; then
                echo -e "${RED}Error: git pull failed for cached clone: $cache_path${NC}"
                exit 1
            fi
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

# ── Metadata resolution ──────────────────────────────────────────────

detect_repository_license() {
    local repository_root="$1"
    local license_filename
    for license_filename in LICENSE LICENSE.md LICENSE.txt LICENCE LICENCE.md; do
        if [ -f "$repository_root/$license_filename" ]; then
            local first_line
            first_line="$(head -1 "$repository_root/$license_filename" 2>/dev/null || true)"
            case "$first_line" in
                *MIT*) echo "MIT"; return ;;
                *Apache*2*) echo "Apache-2.0"; return ;;
                *GPL*3*) echo "GPL-3.0"; return ;;
                *GPL*2*) echo "GPL-2.0"; return ;;
                *BSD*3*) echo "BSD-3-Clause"; return ;;
                *BSD*2*) echo "BSD-2-Clause"; return ;;
                *ISC*) echo "ISC"; return ;;
                *MPL*2*) echo "MPL-2.0"; return ;;
                *LGPL*) echo "LGPL-3.0"; return ;;
                *AGPL*) echo "AGPL-3.0"; return ;;
                *Unlicense*) echo "Unlicense"; return ;;
            esac
            break
        fi
    done
    echo ""
}

# Resolves full repository metadata from a local git clone path.
# Sets globals: REPOSITORY_BRANCH, REPOSITORY_COMMIT, REPOSITORY_LICENSE,
# REPOSITORY_DESCRIPTION (plus all extract_repository_identity globals).
#
# Reads REPO_URL at call time for explicit URL override.
resolve_repository_metadata_from_path() {
    local resolved_path
    resolved_path="$(expand_user_path "$1")"
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

    local resolved_branch
    resolved_branch="$(git -C "$resolved_path" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"
    if [ -z "$resolved_branch" ] || [ "$resolved_branch" = "HEAD" ]; then
        resolved_branch="$(git -C "$resolved_path" symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null | sed 's|^origin/||')"
    fi
    REPOSITORY_BRANCH="$resolved_branch"
    REPOSITORY_COMMIT="$(git -C "$resolved_path" rev-parse HEAD 2>/dev/null || echo "")"
    REPOSITORY_LICENSE="$(detect_repository_license "$resolved_path")"

    REPOSITORY_DESCRIPTION=""
    if command -v gh >/dev/null 2>&1; then
        REPOSITORY_DESCRIPTION="$(gh repo view "$REPOSITORY_KEY" --json description -q '.description' 2>/dev/null || true)"
    fi
}

# ── Qdrant schema for GitHub collections ─────────────────────────────

# Keyword fields enable exact-match filtering; integer fields enable range filtering.
GITHUB_KEYWORD_INDEX_FIELDS=(url hash docSet docPath sourceName sourceKind docVersion docType filePath language repoUrl repoOwner repoName repoKey repoBranch commitHash license repoDescription)
GITHUB_INTEGER_INDEX_FIELDS=(chunkIndex)

ensure_github_payload_indexes() {
    local collection_name="$1"
    local qdrant_base_url="$2"

    echo -e "${YELLOW}Ensuring payload indexes for '$collection_name'...${NC}"

    local field_name
    for field_name in "${GITHUB_KEYWORD_INDEX_FIELDS[@]}"; do
        if ! qdrant_curl -s -o /dev/null -X PUT \
            -H "Content-Type: application/json" \
            -d "{\"field_name\": \"$field_name\", \"field_schema\": {\"type\": \"keyword\"}}" \
            "$qdrant_base_url/collections/$collection_name/index" 2>/dev/null; then
            echo -e "${RED}Failed to create keyword index for field '$field_name'${NC}"
            exit 1
        fi
    done

    for field_name in "${GITHUB_INTEGER_INDEX_FIELDS[@]}"; do
        if ! qdrant_curl -s -o /dev/null -X PUT \
            -H "Content-Type: application/json" \
            -d "{\"field_name\": \"$field_name\", \"field_schema\": {\"type\": \"integer\"}}" \
            "$qdrant_base_url/collections/$collection_name/index" 2>/dev/null; then
            echo -e "${RED}Failed to create integer index for field '$field_name'${NC}"
            exit 1
        fi
    done

    echo -e "${GREEN}Payload indexes ensured${NC}"
}

# ── Manifest I/O ─────────────────────────────────────────────────────

# Writes an ingestion manifest recording the collection, repo identity, and
# timestamp for post-ingestion sync operations.
#
# Reads PROJECT_ROOT and REPOSITORY_* globals at call time.
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

# ── Qdrant collection queries (GitHub-specific) ─────────────────────

# Reads repository metadata (repoUrl, repoKey, commitHash) from the first
# point in a Qdrant collection. Returns pipe-delimited: url|key|commit.
read_collection_repository_metadata() {
    local collection_name="$1"
    local qdrant_base_url="$2"

    local scroll_query='{"limit":1,"with_payload":["repoUrl","repoKey","commitHash"],"with_vector":false}'
    local qdrant_scroll_body

    qdrant_scroll_body="$(qdrant_curl -s -X POST \
        -H "Content-Type: application/json" \
        -d "$scroll_query" \
        "$qdrant_base_url/collections/$collection_name/points/scroll" 2>/dev/null || echo "")"

    if [ -z "$qdrant_scroll_body" ]; then
        echo "||"
        return
    fi

    echo "$qdrant_scroll_body" | jq -r '
        .result.points[0].payload // {} |
        [(.repoUrl // "" | gsub("^\\s+|\\s+$"; "")),
         (.repoKey // "" | gsub("^\\s+|\\s+$"; "")),
         (.commitHash // "" | gsub("^\\s+|\\s+$"; ""))] |
        join("|")
    ' 2>/dev/null || echo "||"
}

remote_head_commit() {
    local repository_url="$1"
    git ls-remote "$repository_url" HEAD 2>/dev/null | awk '{print $1}'
}

# Lists all Qdrant collections with the "github-" prefix.
list_github_collections() {
    local qdrant_base_url="$1"
    local qdrant_collections_listing

    qdrant_collections_listing="$(qdrant_curl -s "$qdrant_base_url/collections" 2>/dev/null || echo "")"

    if [ -z "$qdrant_collections_listing" ]; then
        return
    fi

    echo "$qdrant_collections_listing" | jq -r '
        .result.collections[]?.name // empty |
        select(startswith("github-"))
    ' 2>/dev/null || true
}
