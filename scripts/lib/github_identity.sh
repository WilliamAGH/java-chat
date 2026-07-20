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

GITHUB_COLLECTION_GENERATION="qwen3-embedding-4b-2560"

active_github_collection_prefix() {
    case "${SPRING_PROFILE:-}" in
        local|dev|prod) printf 'github-%s-%s-' "$SPRING_PROFILE" "$GITHUB_COLLECTION_GENERATION" ;;
        *)
            echo "ERROR: SPRING_PROFILE must be exactly local, dev, or prod" >&2
            return 1
            ;;
    esac
}

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
    if command -v openssl >/dev/null 2>&1; then
        printf '%s' "$raw_value" | openssl dgst -sha256 -r | awk '{print substr($1,1,8)}'
        return
    fi
    echo "ERROR: no SHA-256 tool available (need sha256sum, shasum, or openssl)" >&2
    return 1
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
    if ! hash_suffix="$(short_sha256 "$raw_segment")"; then
        echo "ERROR: failed to hash collection segment '$raw_segment' (no SHA-256 tool available)" >&2
        return 1
    fi
    if [ -z "$hash_suffix" ]; then
        echo "ERROR: failed to hash collection segment '$raw_segment' (empty hash output)" >&2
        return 1
    fi
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
    local encoded_owner_segment
    if ! encoded_owner_segment="$(encode_collection_segment "$REPOSITORY_OWNER")"; then
        echo -e "${RED}Error: failed to encode repository owner segment${NC}"
        exit 1
    fi
    if [ -z "$encoded_owner_segment" ]; then
        echo -e "${RED}Error: encoded repository owner segment is empty${NC}"
        exit 1
    fi

    local encoded_name_segment
    if ! encoded_name_segment="$(encode_collection_segment "$REPOSITORY_NAME")"; then
        echo -e "${RED}Error: failed to encode repository name segment${NC}"
        exit 1
    fi
    if [ -z "$encoded_name_segment" ]; then
        echo -e "${RED}Error: encoded repository name segment is empty${NC}"
        exit 1
    fi

    local repository_boundary="-"
    if [[ ! "$REPOSITORY_OWNER" =~ ^[a-z0-9]+$ ]]; then
        repository_boundary="_"
    fi
    local active_collection_prefix
    if ! active_collection_prefix="$(active_github_collection_prefix)"; then
        exit 1
    fi
    CANONICAL_COLLECTION_NAME="${active_collection_prefix}${encoded_owner_segment}${repository_boundary}${encoded_name_segment}"
}

# Rejects collection names that do not match their repository's canonical identity.
require_canonical_collection_name() {
    local collection_name="$1"
    local repository_url="$2"

    extract_repository_identity "$repository_url"
    if [ "$collection_name" != "$CANONICAL_COLLECTION_NAME" ]; then
        echo -e "${RED}Collection '$collection_name' does not match canonical name '$CANONICAL_COLLECTION_NAME'.${NC}" >&2
        return 1
    fi
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
        if [ -z "${REPO_CACHE_DIR:-}" ]; then
            echo -e "${RED}Error: REPO_CACHE_DIR is not set${NC}"
            exit 1
        fi
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
        if ! git clone "$REPOSITORY_URL" "$cache_path" >/dev/null 2>&1; then
            echo -e "${RED}Error: git clone failed for: $REPOSITORY_URL${NC}"
            exit 1
        fi
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

    if [ -n "${REPO_URL:-}" ]; then
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

ensure_github_payload_index_field() {
    local collection_name="$1"
    local qdrant_base_url="$2"
    local field_name="$3"
    local field_type="$4"
    local qdrant_status_code

    if ! qdrant_status_code="$(qdrant_curl -s -o /dev/null -w "%{http_code}" -X PUT \
        -H "Content-Type: application/json" \
        -d "{\"field_name\": \"$field_name\", \"field_schema\": {\"type\": \"$field_type\"}}" \
        "$qdrant_base_url/collections/$collection_name/index" 2>/dev/null)"; then
        echo -e "${RED}Failed to create ${field_type} index for field '$field_name' (request failed)${NC}"
        exit 1
    fi

    if [[ ! "$qdrant_status_code" =~ ^2[0-9][0-9]$ ]] && [ "$qdrant_status_code" != "409" ]; then
        echo -e "${RED}Failed to create ${field_type} index for field '$field_name' (HTTP $qdrant_status_code)${NC}"
        exit 1
    fi
}

ensure_github_payload_indexes() {
    local collection_name="$1"
    local qdrant_base_url="$2"

    echo -e "${YELLOW}Ensuring payload indexes for '$collection_name'...${NC}"

    local field_name
    for field_name in "${GITHUB_KEYWORD_INDEX_FIELDS[@]}"; do
        ensure_github_payload_index_field "$collection_name" "$qdrant_base_url" "$field_name" "keyword"
    done

    for field_name in "${GITHUB_INTEGER_INDEX_FIELDS[@]}"; do
        ensure_github_payload_index_field "$collection_name" "$qdrant_base_url" "$field_name" "integer"
    done

    echo -e "${GREEN}Payload indexes ensured${NC}"
}

# ── Qdrant collection queries (GitHub-specific) ─────────────────────

# Reads repository metadata (repoUrl, repoKey, commitHash) from the first
# point in a Qdrant collection. Returns pipe-delimited: url|key|commit.
read_collection_repository_metadata() {
    local collection_name="$1"
    local qdrant_base_url="$2"

    local scroll_query='{"limit":1,"with_payload":["repoUrl","repoKey","commitHash"],"with_vector":false}'
    local qdrant_scroll_body

    if ! qdrant_scroll_body="$(qdrant_curl -fsS -X POST \
        -H "Content-Type: application/json" \
        -d "$scroll_query" \
        "$qdrant_base_url/collections/$collection_name/points/scroll")"; then
        echo "Failed to read repository metadata from Qdrant collection $collection_name" >&2
        return 1
    fi

    echo "$qdrant_scroll_body" | jq -er '
        .result.points |
        if type != "array" or length == 0 then error("collection has no repository point") else . end |
        .[0].payload |
        if type != "object" then error("repository payload is missing") else . end |
        [(.repoUrl // "" | gsub("^\\s+|\\s+$"; "")),
         (.repoKey // "" | gsub("^\\s+|\\s+$"; "")),
         (.commitHash // "" | gsub("^\\s+|\\s+$"; ""))] |
        join("|")
    '
}

remote_head_commit() {
    local repository_url="$1"
    local remote_commit
    if ! remote_commit="$(git ls-remote "$repository_url" HEAD | awk 'NR == 1 {print $1}')" \
        || [[ ! "$remote_commit" =~ ^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$ ]]; then
        echo "Failed to resolve a full remote HEAD for $repository_url" >&2
        return 1
    fi
    printf '%s\n' "$remote_commit"
}

# Lists Qdrant collections for the exact active environment and embedding generation.
list_github_collections() {
    local qdrant_base_url="$1"
    local qdrant_collections_listing

    if ! qdrant_collections_listing="$(qdrant_curl -fsS "$qdrant_base_url/collections")"; then
        echo "Failed to list Qdrant collections" >&2
        return 1
    fi

    local active_collection_prefix
    active_collection_prefix="$(active_github_collection_prefix)" || return 1
    echo "$qdrant_collections_listing" | jq -er --arg prefix "$active_collection_prefix" '
        .result.collections |
        if type != "array" then error("Qdrant collections response is malformed") else . end |
        [ .[] |
          if (.name | type) != "string" then error("Qdrant collection name is malformed") else .name end |
          select(startswith($prefix)) ] |
        join("\n")
    '
}
