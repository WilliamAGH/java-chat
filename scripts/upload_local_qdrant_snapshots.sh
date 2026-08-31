#!/bin/bash

# Copies the completed local Qdrant generation to the new self-hosted server.
# The active documentation collection is deferred until the existing staging queue
# publishes its durable completion marker.

set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIRECTORY/lib/common_qdrant.sh"

readonly LOCAL_QDRANT_REST_BASE="http://127.0.0.1:8087"
readonly DOCUMENTATION_COLLECTION="java-chat-qwen3-embedding-4b-2560-docs"
readonly DOCUMENTATION_STAGING_COMPLETE_MARKER="$HOME/.local/state/java-chat/queued-platform-documentation-staging.complete"
readonly QDRANT_RECONCILIATION_COMPLETE_MARKER="$HOME/.local/state/java-chat/production-qdrant-reconcile.complete"
readonly TARGET_VERIFICATION_ATTEMPTS=60
readonly TARGET_VERIFICATION_INTERVAL_SECONDS=5
readonly QDRANT_CONTROL_PLANE_TIMEOUT_SECONDS=30
readonly SNAPSHOT_CREATION_TIMEOUT_SECONDS=1800
readonly SNAPSHOT_TRANSFER_MINIMUM_BYTES_PER_SECOND=1024
readonly SNAPSHOT_TRANSFER_STALL_SECONDS=120

: "${QDRANT_API_KEY:?QDRANT_API_KEY is required for the self-hosted Qdrant target}"
: "${QDRANT_HOST:?QDRANT_HOST is required for the self-hosted Qdrant target}"
: "${QDRANT_REST_PORT:?QDRANT_REST_PORT is required for the self-hosted Qdrant target}"
: "${QDRANT_SSL:?QDRANT_SSL is required for the self-hosted Qdrant target}"
if [[ ! "$QDRANT_REST_PORT" =~ ^[1-9][0-9]*$ ]] || [ "$QDRANT_REST_PORT" -ge 65535 ]; then
    echo "QDRANT_REST_PORT must be a valid port below 65535" >&2
    exit 1
fi
case "$QDRANT_SSL" in
    true) readonly TARGET_QDRANT_SCHEME="https" ;;
    false) readonly TARGET_QDRANT_SCHEME="http" ;;
    *)
        echo "QDRANT_SSL must be exactly true or false" >&2
        exit 1
        ;;
esac
readonly TARGET_QDRANT_REST_BASE="$TARGET_QDRANT_SCHEME://$QDRANT_HOST:$QDRANT_REST_PORT"
for environment_name in $(compgen -e); do
    case "$environment_name" in
        COMMON_QDRANT_LIB_DIR|HOME|PATH|QDRANT_API_KEY|QDRANT_HOST|QDRANT_REST_PORT|QDRANT_SSL|SCRIPT_DIRECTORY) ;;
        *) unset "$environment_name" ;;
    esac
done

target_qdrant_curl() {
    curl --header @<(printf 'api-key: %s\n' "$QDRANT_API_KEY") "$@"
}

collection_signature() {
    jq -Sce '{
        config: .result.config,
        payloadSchema: .result.payload_schema
    }'
}

exact_collection_point_count() {
    local collection_base_url="$1"
    shift
    "$@" \
        --fail \
        --silent \
        --show-error \
        --connect-timeout 10 \
        --max-time "$QDRANT_CONTROL_PLANE_TIMEOUT_SECONDS" \
        --request POST \
        --header "Content-Type: application/json" \
        --data '{"exact":true}' \
        "$collection_base_url/points/count" \
        | jq -er '.result.count'
}

verify_target_collection() {
    local collection_name="$1"
    local source_collection_point_count="$2"
    local source_collection_signature="$3"

    local verification_attempt
    for ((verification_attempt = 1; verification_attempt <= TARGET_VERIFICATION_ATTEMPTS; verification_attempt++)); do
        local target_collection_body
        local target_collection_point_count
        if target_collection_body="$(target_qdrant_curl \
            --fail \
            --silent \
            --show-error \
            --connect-timeout 10 \
            --max-time 5 \
            "$TARGET_QDRANT_REST_BASE/collections/$collection_name" 2>/dev/null)" \
            && target_collection_point_count="$(exact_collection_point_count \
                "$TARGET_QDRANT_REST_BASE/collections/$collection_name" \
                target_qdrant_curl 2>/dev/null)" \
            && [ "$target_collection_point_count" = "$source_collection_point_count" ] \
            && [ "$(collection_signature <<<"$target_collection_body")" = "$source_collection_signature" ] \
            && [ "$(jq -er '.result.status' <<<"$target_collection_body")" = "green" ]; then
            printf 'TARGET_COLLECTION_VERIFIED %s points=%s\n' \
                "$collection_name" "$source_collection_point_count"
            return 0
        fi
        if [ "$verification_attempt" -lt "$TARGET_VERIFICATION_ATTEMPTS" ]; then
            sleep "$TARGET_VERIFICATION_INTERVAL_SECONDS"
        fi
    done

    echo "Target collection did not converge: $collection_name" >&2
    return 1
}

create_collection_snapshot() {
    local collection_name="$1"
    curl --fail --silent --show-error \
        --connect-timeout 10 \
        --max-time "$SNAPSHOT_CREATION_TIMEOUT_SECONDS" \
        --request POST \
        "$LOCAL_QDRANT_REST_BASE/collections/$collection_name/snapshots?wait=true" \
        | jq -ce '.result'
}

delete_collection_snapshot() {
    local collection_name="$1"
    local snapshot_name="$2"
    curl --fail --silent --show-error \
        --connect-timeout 10 \
        --max-time "$QDRANT_CONTROL_PLANE_TIMEOUT_SECONDS" \
        --request DELETE \
        "$LOCAL_QDRANT_REST_BASE/collections/$collection_name/snapshots/$snapshot_name?wait=true" \
        >/dev/null
}

local_collection_names() {
    curl --fail --silent --show-error \
        --connect-timeout 10 \
        --max-time "$QDRANT_CONTROL_PLANE_TIMEOUT_SECONDS" \
        "$LOCAL_QDRANT_REST_BASE/collections" \
        | jq -r '.result.collections[].name' \
        | sort
}

migrate_collection_snapshot() (
    local collection_name="$1"

    local source_collection_body
    source_collection_body="$(curl --fail --silent --show-error \
        --connect-timeout 10 \
        --max-time "$QDRANT_CONTROL_PLANE_TIMEOUT_SECONDS" \
        "$LOCAL_QDRANT_REST_BASE/collections/$collection_name")"
    local source_collection_point_count
    source_collection_point_count="$(exact_collection_point_count \
        "$LOCAL_QDRANT_REST_BASE/collections/$collection_name" \
        curl)"
    if [ "$source_collection_point_count" -eq 0 ]; then
        printf 'SOURCE_COLLECTION_EMPTY %s\n' "$collection_name"
        return 0
    fi
    local source_collection_signature
    source_collection_signature="$(collection_signature <<<"$source_collection_body")"

    local snapshot_metadata
    snapshot_metadata="$(create_collection_snapshot "$collection_name")"
    local snapshot_name
    snapshot_name="$(jq -er '.name' <<<"$snapshot_metadata")"
    trap 'delete_collection_snapshot "$collection_name" "$snapshot_name"' EXIT
    local snapshot_checksum
    snapshot_checksum="$(jq -er '.checksum' <<<"$snapshot_metadata")"

    local snapshot_upload_body
    if ! snapshot_upload_body="$(
        set -o pipefail
        curl --fail --silent --show-error \
            --connect-timeout 10 \
            --speed-limit "$SNAPSHOT_TRANSFER_MINIMUM_BYTES_PER_SECOND" \
            --speed-time "$SNAPSHOT_TRANSFER_STALL_SECONDS" \
            "$LOCAL_QDRANT_REST_BASE/collections/$collection_name/snapshots/$snapshot_name" \
            | target_qdrant_curl \
                --fail \
                --silent \
                --show-error \
                --connect-timeout 10 \
                --speed-limit "$SNAPSHOT_TRANSFER_MINIMUM_BYTES_PER_SECOND" \
                --speed-time "$SNAPSHOT_TRANSFER_STALL_SECONDS" \
                --request POST \
                --form "snapshot=@-;filename=$snapshot_name;type=application/octet-stream" \
                "$TARGET_QDRANT_REST_BASE/collections/$collection_name/snapshots/upload?wait=true&priority=snapshot&checksum=$snapshot_checksum"
    )"; then
        return 1
    fi
    if [ "$(jq -er '.status' <<<"$snapshot_upload_body")" != "ok" ]; then
        echo "Snapshot upload failed for $collection_name" >&2
        return 1
    fi

    local verification_status=0
    verify_target_collection \
        "$collection_name" \
        "$source_collection_point_count" \
        "$source_collection_signature" || verification_status=$?
    delete_collection_snapshot "$collection_name" "$snapshot_name"
    trap - EXIT
    return "$verification_status"
)

while [ ! -e "$DOCUMENTATION_STAGING_COMPLETE_MARKER" ]; do
    printf 'WAITING_FOR_DOCUMENTATION_STAGING %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    sleep 60
done
acquire_qdrant_writer_lease

local_root_body="$(curl \
    --fail \
    --silent \
    --show-error \
    --connect-timeout 10 \
    --max-time 30 \
    "$LOCAL_QDRANT_REST_BASE/")"
target_root_body="$(target_qdrant_curl \
    --fail \
    --silent \
    --show-error \
    --connect-timeout 10 \
    --max-time 30 \
    "$TARGET_QDRANT_REST_BASE/")"
local_telemetry_body="$(curl \
    --fail \
    --silent \
    --show-error \
    --connect-timeout 10 \
    --max-time 30 \
    "$LOCAL_QDRANT_REST_BASE/telemetry?details_level=0")"
target_telemetry_body="$(target_qdrant_curl \
    --fail \
    --silent \
    --show-error \
    --connect-timeout 10 \
    --max-time 30 \
    "$TARGET_QDRANT_REST_BASE/telemetry?details_level=0")"
if [ "$(jq -er '.title' <<<"$local_root_body")" != "qdrant - vector search engine" ]; then
    echo "Configured local source must identify Qdrant" >&2
    exit 1
fi
if [ "$(jq -er '.title' <<<"$target_root_body")" != "qdrant - vector search engine" ]; then
    echo "Configured self-hosted target must identify Qdrant" >&2
    exit 1
fi
target_qdrant_version="$(jq -er '.version' <<<"$target_root_body")"
source_qdrant_version="$(jq -er '.version' <<<"$local_root_body")"
if [ "$target_qdrant_version" != "$source_qdrant_version" ]; then
    echo "Target Qdrant $target_qdrant_version must match source $source_qdrant_version for snapshot recovery" >&2
    exit 1
fi
local_qdrant_instance_id="$(jq -er '.result.id' <<<"$local_telemetry_body")"
target_qdrant_instance_id="$(jq -er '.result.id' <<<"$target_telemetry_body")"
if [ "$target_qdrant_instance_id" = "$local_qdrant_instance_id" ]; then
    echo "Source and target must be different Qdrant instances" >&2
    exit 1
fi
final_collection_names="$(local_collection_names)"
if ! grep -Fxq "$DOCUMENTATION_COLLECTION" <<<"$final_collection_names"; then
    echo "Completed local inventory lacks the documentation collection" >&2
    exit 1
fi
while IFS= read -r collection_name; do
    [ -n "$collection_name" ] || continue
    migrate_collection_snapshot "$collection_name"
done <<<"$final_collection_names"

local_collection_count="$(printf '%s\n' "$final_collection_names" | sed '/^$/d' | wc -l | tr -d ' ')"
touch "$QDRANT_RECONCILIATION_COMPLETE_MARKER"
printf 'QDRANT_SNAPSHOT_MIGRATION_COMPLETE %s collections=%s target_version=%s\n' \
    "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$local_collection_count" "$target_qdrant_version"
