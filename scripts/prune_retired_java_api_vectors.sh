#!/bin/bash

# Removes retired Oracle Javadoc vectors before a Java API mirror is re-ingested.
#
# The cleanup is intentionally narrow: it deletes only obsolete class-use pages and
# redirect-backed root-level type pages that cannot be canonical Javadoc citations.
# Usage: ./scripts/prune_retired_java_api_vectors.sh [--doc-sets=SOURCE_IDENTIFIER,...]

set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

readonly RETIRED_JAVA_API_VECTOR_COLLECTION_DEFAULT="java-docs"
readonly RETIRED_JAVA_API_VECTOR_SCROLL_LIMIT=256
readonly RETIRED_JAVA_API_VECTOR_DELETE_BATCH_SIZE=100
readonly RETIRED_JAVA_API_VECTOR_CONNECT_TIMEOUT_SECONDS=5
readonly RETIRED_JAVA_API_VECTOR_REQUEST_TIMEOUT_SECONDS=30

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIRECTORY/lib/common_qdrant.sh"

# Returns success when the effective CLI selector includes a Java API mirror.
# A blank selector and "all" both include Java API mirrors.
java_api_vector_prune_required() {
    local doc_set_filter="$1"
    local compact_filter="${doc_set_filter//[[:space:]]/}"
    if [ -z "$compact_filter" ]; then
        return 0
    fi

    local selector
    local trimmed_selector
    local canonical_java_api_selected=false
    local -a selectors=()
    IFS=',' read -r -a selectors <<< "$doc_set_filter"
    for selector in "${selectors[@]}"; do
        trimmed_selector="${selector#"${selector%%[![:space:]]*}"}"
        trimmed_selector="${trimmed_selector%"${trimmed_selector##*[![:space:]]}"}"
        if [[ "$trimmed_selector" =~ ^[Aa][Ll][Ll]$ ]]; then
            return 0
        fi
        case "$trimmed_selector" in
            java/java21-complete|java/java24-complete|java/java25-complete)
                canonical_java_api_selected=true
                ;;
            java/java*-complete)
                printf 'Unknown Java API documentation selector: %s\n' "$trimmed_selector" >&2
                return 2
                ;;
        esac
    done

    if [ "$canonical_java_api_selected" = true ]; then
        return 0
    fi
    return 1
}

# Deletes only a temporary directory whose path is owned by this prune run.
cleanup_retired_java_api_vector_directory() {
    local temporary_root="$1"
    local temporary_directory="$2"
    case "$temporary_directory" in
        "$temporary_root"/retired-java-api-vectors.*)
            if [ -d "$temporary_directory" ]; then
                rm -rf -- "$temporary_directory"
            fi
            ;;
        *)
            printf 'Refusing to remove an unrecognized retired Java API vector temporary directory\n' >&2
            return 1
            ;;
    esac
}

# Sends one JSON request to Qdrant and requires a successful JSON status response.
retired_java_api_vector_qdrant_request() {
    local request_method="$1"
    local request_url="$2"
    local request_body="$3"
    local operation_name="$4"
    local qdrant_response

    if ! qdrant_response="$(qdrant_curl \
        -sS \
        --connect-timeout "$RETIRED_JAVA_API_VECTOR_CONNECT_TIMEOUT_SECONDS" \
        --max-time "$RETIRED_JAVA_API_VECTOR_REQUEST_TIMEOUT_SECONDS" \
        -X "$request_method" \
        -H "Content-Type: application/json" \
        -d "$request_body" \
        -w $'\n%{http_code}' \
        "$request_url")"; then
        printf 'Qdrant %s request failed\n' "$operation_name" >&2
        return 1
    fi

    local http_status="${qdrant_response##*$'\n'}"
    local response_body="${qdrant_response%$'\n'*}"
    if [[ ! "$http_status" =~ ^2[0-9][0-9]$ ]]; then
        printf 'Qdrant %s request returned HTTP %s\n' "$operation_name" "$http_status" >&2
        return 1
    fi
    if ! jq -e '.status == "ok"' >/dev/null <<< "$response_body"; then
        printf 'Qdrant %s request returned an invalid status payload\n' "$operation_name" >&2
        return 1
    fi

    printf '%s' "$response_body"
}

# Writes every matching Qdrant point identifier to the supplied JSON-lines file.
collect_retired_java_api_vector_ids() {
    local qdrant_base_url="$1"
    local qdrant_collection="$2"
    local point_identifiers_file="$3"
    local next_page_offset=""
    local previous_page_offset=""
    local has_next_page_offset=false

    : > "$point_identifiers_file"
    while true; do
        local scroll_request_body
        if [ "$has_next_page_offset" = true ]; then
            scroll_request_body="$(jq -cn \
                --argjson pageOffset "$next_page_offset" \
                --argjson scrollLimit "$RETIRED_JAVA_API_VECTOR_SCROLL_LIMIT" \
                '{limit: $scrollLimit, with_payload: ["url"], with_vector: false, offset: $pageOffset}')"
        else
            scroll_request_body="$(jq -cn \
                --argjson scrollLimit "$RETIRED_JAVA_API_VECTOR_SCROLL_LIMIT" \
                '{limit: $scrollLimit, with_payload: ["url"], with_vector: false}')"
        fi

        local scroll_response
        if ! scroll_response="$(retired_java_api_vector_qdrant_request \
            "POST" \
            "$qdrant_base_url/collections/$qdrant_collection/points/scroll" \
            "$scroll_request_body" \
            "scroll")"; then
            return 1
        fi
        if ! jq -e '
            (.result | type == "object")
            and (.result.points | type == "array")
            and ((.result.next_page_offset? | type) as $offsetType
                | $offsetType == "null" or $offsetType == "number" or $offsetType == "string")
        ' >/dev/null <<< "$scroll_response"; then
            printf 'Qdrant scroll response has an invalid result shape\n' >&2
            return 1
        fi
        if ! jq -c '
            def retired_java_api_url:
                (.payload.url? | type == "string")
                and (
                    (.payload.url | test("^https://docs\\.oracle\\.com/en/java/javase/[0-9]+/docs/api/(?:.*/)?class-use/"))
                    or (.payload.url | test("^https://docs\\.oracle\\.com/en/java/javase/[0-9]+/docs/api/[A-Z][A-Za-z0-9_$]*\\.html(?:[?#].*)?$"))
                );
            .result.points[]
            | select(retired_java_api_url)
            | (.id | type) as $pointIdentifierType
            | if $pointIdentifierType == "number" or $pointIdentifierType == "string" then
                  .id
              else
                  error("retired Java API point has no supported identifier")
              end
        ' <<< "$scroll_response" >> "$point_identifiers_file"; then
            printf 'Qdrant scroll response contains an invalid retired Java API point\n' >&2
            return 1
        fi

        local offset_is_null
        offset_is_null="$(jq -r '.result.next_page_offset? == null' <<< "$scroll_response")"
        if [ "$offset_is_null" = "true" ]; then
            break
        fi

        next_page_offset="$(jq -c '.result.next_page_offset' <<< "$scroll_response")"
        if [ "$next_page_offset" = "$previous_page_offset" ]; then
            printf 'Qdrant scroll response repeated its pagination offset\n' >&2
            return 1
        fi
        previous_page_offset="$next_page_offset"
        has_next_page_offset=true
    done

    LC_ALL=C sort -u "$point_identifiers_file" -o "$point_identifiers_file"
}

# Deletes one bounded group of Qdrant point identifiers and waits for completion.
delete_retired_java_api_vector_batch() {
    local qdrant_base_url="$1"
    local qdrant_collection="$2"
    shift 2
    local point_identifiers=("$@")
    local delete_request_body

    delete_request_body="$(printf '%s\n' "${point_identifiers[@]}" | jq -sc '{points: .}')"
    if ! retired_java_api_vector_qdrant_request \
        "POST" \
        "$qdrant_base_url/collections/$qdrant_collection/points/delete?wait=true" \
        "$delete_request_body" \
        "delete" \
        >/dev/null; then
        return 1
    fi
}

# Removes all collected point identifiers in bounded batches.
delete_retired_java_api_vectors() {
    local qdrant_base_url="$1"
    local qdrant_collection="$2"
    local point_identifiers_file="$3"
    local point_identifier
    local -a point_identifiers=()

    while IFS= read -r point_identifier; do
        point_identifiers+=("$point_identifier")
        if [ "${#point_identifiers[@]}" -eq "$RETIRED_JAVA_API_VECTOR_DELETE_BATCH_SIZE" ]; then
            if ! delete_retired_java_api_vector_batch \
                "$qdrant_base_url" "$qdrant_collection" "${point_identifiers[@]}"; then
                return 1
            fi
            point_identifiers=()
        fi
    done < "$point_identifiers_file"

    if [ "${#point_identifiers[@]}" -gt 0 ]; then
        delete_retired_java_api_vector_batch \
            "$qdrant_base_url" "$qdrant_collection" "${point_identifiers[@]}"
    fi
}

# Performs the strict vector cleanup in a scoped shell so its validated temporary
# directory is removed on every success and failure path.
prune_retired_java_api_vectors_in_collection() (
    local qdrant_base_url="$1"
    local qdrant_collection="$2"
    local temporary_root
    if ! temporary_root="$(cd "${TMPDIR:-/tmp}" && pwd)"; then
        printf 'Failed to resolve the temporary directory root for retired Java API vector pruning\n' >&2
        return 1
    fi
    local temporary_directory
    if ! temporary_directory="$(mktemp -d "$temporary_root/retired-java-api-vectors.XXXXXX")"; then
        printf 'Failed to create a temporary directory for retired Java API vector pruning\n' >&2
        return 1
    fi
    case "$temporary_directory" in
        "$temporary_root"/retired-java-api-vectors.*)
            ;;
        *)
            printf 'mktemp returned an unrecognized retired Java API vector temporary directory\n' >&2
            return 1
            ;;
    esac
    trap 'cleanup_retired_java_api_vector_directory "$temporary_root" "$temporary_directory"' EXIT

    local retired_point_identifiers_file="$temporary_directory/retired-point-identifiers.jsonl"
    local verification_point_identifiers_file="$temporary_directory/verification-point-identifiers.jsonl"
    if ! collect_retired_java_api_vector_ids \
        "$qdrant_base_url" "$qdrant_collection" "$retired_point_identifiers_file"; then
        return 1
    fi

    local retired_vector_count
    retired_vector_count="$(wc -l < "$retired_point_identifiers_file" | tr -d ' ')"
    if [ "$retired_vector_count" -gt 0 ]; then
        if ! delete_retired_java_api_vectors \
            "$qdrant_base_url" "$qdrant_collection" "$retired_point_identifiers_file"; then
            return 1
        fi
    fi

    if ! collect_retired_java_api_vector_ids \
        "$qdrant_base_url" "$qdrant_collection" "$verification_point_identifiers_file"; then
        return 1
    fi

    local remaining_retired_vector_count
    remaining_retired_vector_count="$(wc -l < "$verification_point_identifiers_file" | tr -d ' ')"
    if [ "$remaining_retired_vector_count" -ne 0 ]; then
        printf 'Retired Java API vector prune verification failed: %s matching vectors remain\n' \
            "$remaining_retired_vector_count" >&2
        return 1
    fi

    printf 'Retired Java API vector prune completed: %s vectors removed from %s.\n' \
        "$retired_vector_count" "$qdrant_collection"
)

# Performs the strict pre-ingestion cleanup and proves no selected vectors remain.
prune_retired_java_api_vectors() {
    local doc_set_filter="${1:-${DOCS_SETS:-}}"
    local selection_status
    if java_api_vector_prune_required "$doc_set_filter"; then
        selection_status=0
    else
        selection_status=$?
    fi
    case "$selection_status" in
        0)
            ;;
        1)
            printf 'Retired Java API vector prune skipped: no Java API mirror is selected.\n'
            return 0
            ;;
        *)
            return "$selection_status"
            ;;
    esac

    load_env_file
    apply_pipeline_defaults
    if ! check_qdrant_connection "echo -e"; then
        printf 'Cannot prune retired Java API vectors without Qdrant connectivity\n' >&2
        return 1
    fi

    local qdrant_collection="${QDRANT_COLLECTION_DOCS:-$RETIRED_JAVA_API_VECTOR_COLLECTION_DEFAULT}"
    local qdrant_base_url
    qdrant_base_url="$(qdrant_rest_base_url)"
    prune_retired_java_api_vectors_in_collection "$qdrant_base_url" "$qdrant_collection"
}

retired_java_api_vector_prune_main() {
    local doc_set_filter="${DOCS_SETS:-}"
    local argument
    for argument in "$@"; do
        case "$argument" in
            --doc-sets=*)
                doc_set_filter="${argument#*=}"
                ;;
            --help|-h)
                printf 'Usage: %s [--doc-sets=SOURCE_IDENTIFIER,...]\n' "$0"
                return 0
                ;;
            *)
                printf 'Unknown option: %s\n' "$argument" >&2
                return 1
                ;;
        esac
    done

    prune_retired_java_api_vectors "$doc_set_filter"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    retired_java_api_vector_prune_main "$@"
fi
