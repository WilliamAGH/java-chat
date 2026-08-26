#!/bin/bash

# Reports the Linux user-service embedding phase, progress, and active source.

set -euo pipefail

status_script_path="${BASH_SOURCE[0]}"
while [ -L "$status_script_path" ]; do
    status_link_target="$(readlink "$status_script_path")"
    case "$status_link_target" in
        /*) status_script_path="$status_link_target" ;;
        *) status_script_path="$(dirname "$status_script_path")/$status_link_target" ;;
    esac
done
SCRIPT_DIRECTORY="$(cd "$(dirname "$status_script_path")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIRECTORY/.."
readonly SCRIPT_DIRECTORY PROJECT_ROOT
readonly PROCESS_LOG="$PROJECT_ROOT/process_qdrant.log"
readonly LOCAL_QDRANT_COLLECTION_URL="http://127.0.0.1:8087/collections/java-chat-qwen3-embedding-4b-2560-docs"
readonly EMBEDDING_SERVICE="java-chat-local-embedding-staging.service"
readonly STAGING_INVOCATION_RECEIPT="$HOME/.local/state/java-chat/local-embedding-staging.invocation"
readonly WATCH_INTERVAL_SECONDS=55
readonly INVOCATION_READ_ATTEMPTS=3
readonly STATUS_USAGE_ERROR=64
readonly STATUS_DEPENDENCY_FAILURE=69
readonly STATUS_INVOCATION_RACE=75
readonly STATUS_STAGING_COMPLETE=10

case "${1:-}" in
    "") watch_mode=false ;;
    --watch) watch_mode=true ;;
    *)
        printf 'Usage: %s [--watch]\n' "$0" >&2
        exit "$STATUS_USAGE_ERROR"
        ;;
esac
if [ "$#" -gt 1 ]; then
    printf 'Usage: %s [--watch]\n' "$0" >&2
    exit "$STATUS_USAGE_ERROR"
fi

require_monitoring_command() {
    local monitoring_command="$1"
    if ! command -v "$monitoring_command" >/dev/null 2>&1; then
        printf 'Required monitoring command is unavailable: %s\n' "$monitoring_command" >&2
        exit "$STATUS_DEPENDENCY_FAILURE"
    fi
}

for monitoring_command in awk curl date dirname journalctl jq readlink sed sleep stat systemctl uname; do
    require_monitoring_command "$monitoring_command"
done
if [ "$(uname -s)" != Linux ]; then
    printf 'Local embedding service status requires Linux user systemd.\n' >&2
    exit "$STATUS_DEPENDENCY_FAILURE"
fi

resolve_staging_invocation_id() {
    local active_invocation_id
    if active_invocation_id="$(systemctl --user show \
        "$EMBEDDING_SERVICE" --property=InvocationID --value 2>/dev/null)"; then
        if [ -n "$active_invocation_id" ]; then
            printf '%s\n' "$active_invocation_id"
        elif [ -s "$STAGING_INVOCATION_RECEIPT" ]; then
            sed -n '1p' "$STAGING_INVOCATION_RECEIPT"
        fi
        return 0
    fi
    printf 'Could not resolve the local embedding service invocation.\n' >&2
    return "$STATUS_DEPENDENCY_FAILURE"
}

summarize_process_log() {
    awk '
        {
            remaining_log_line = $0
            while (match(remaining_log_line, /docSet=[^,)]*/)) {
                latest_documentation_set = substr(remaining_log_line, RSTART, RLENGTH)
                remaining_log_line = substr(remaining_log_line, RSTART + RLENGTH)
            }
        }
        /Qdrant postcondition required for docSet:/ {
            documentation_set = $0
            sub(/^.*Qdrant postcondition required for docSet: /, "", documentation_set)
            completed_documentation_sets[documentation_set] = 1
        }
        END {
            completed_count = 0
            for (documentation_set in completed_documentation_sets) {
                completed_count++
            }
            printf "%d|%s\n", completed_count, latest_documentation_set
        }
    ' "$PROCESS_LOG"
}

report_process_log_state() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
    if [ ! -f "$PROCESS_LOG" ]; then
        printf 'log=missing\n'
        return 0
    fi
    local process_log_epoch
    local process_log_bytes
    read -r process_log_epoch process_log_bytes < <(stat -c '%Y %s' "$PROCESS_LOG")
    printf 'log_modified=%s log_bytes=%s\n' \
        "$(date -u -d "@$process_log_epoch" '+%Y-%m-%dT%H:%M:%SZ')" \
        "$process_log_bytes"
}

read_staging_snapshot() {
    local staging_invocation_before
    local staging_invocation_after
    local staging_journal
    local invocation_read_attempt
    for ((invocation_read_attempt = 1; invocation_read_attempt <= INVOCATION_READ_ATTEMPTS; invocation_read_attempt++)); do
        staging_invocation_before="$(resolve_staging_invocation_id)" || return $?
        if [ -n "$staging_invocation_before" ]; then
            local journal_read_status
            if staging_journal="$(journalctl --user \
                -u "$EMBEDDING_SERVICE" \
                "_SYSTEMD_INVOCATION_ID=$staging_invocation_before" \
                --no-pager \
                -o cat \
                --grep='^(LOCAL_STAGING_RESUME|REPOSITORY_START|REPOSITORY_COMPLETE|LOCAL_STAGING_COMPLETE)( |$)' \
                2>/dev/null)"; then
                journal_read_status=0
            else
                journal_read_status=$?
            fi
            if [ "$journal_read_status" -eq 1 ]; then
                staging_journal=""
            elif [ "$journal_read_status" -ne 0 ]; then
                printf 'Could not read local embedding lifecycle markers.\n' >&2
                return "$STATUS_DEPENDENCY_FAILURE"
            fi
        else
            staging_journal=""
        fi
        staging_invocation_after="$(resolve_staging_invocation_id)" || return $?
        if [ "$staging_invocation_before" = "$staging_invocation_after" ]; then
            printf '%s\n' "$staging_journal"
            return 0
        fi
    done
    printf 'Staging invocation changed during status collection.\n' >&2
    return "$STATUS_INVOCATION_RACE"
}

report_embedding_status() {
    report_process_log_state

    local staging_journal
    staging_journal="$(read_staging_snapshot)" || return $?
    local resume_marker
    resume_marker="$(printf '%s\n' "$staging_journal" | awk '$1 == "LOCAL_STAGING_RESUME" { marker = $0 } END { print marker }')"
    local documentation_total=0
    local repository_total=0
    local staging_field
    for staging_field in $resume_marker; do
        case "$staging_field" in
            DOCS=*) documentation_total="${staging_field#DOCS=}" ;;
            REPOS=*) repository_total="${staging_field#REPOS=}" ;;
        esac
    done

    local process_log_summary='0|'
    if [ -n "$resume_marker" ] && [ -f "$PROCESS_LOG" ]; then
        local resume_timestamp
        local resume_epoch
        local process_log_epoch
        resume_timestamp="$(awk '{ print $2; exit }' <<< "$resume_marker")"
        resume_epoch="$(date -d "$resume_timestamp" '+%s')"
        process_log_epoch="$(stat -c '%Y' "$PROCESS_LOG")"
        if [ "$process_log_epoch" -ge "$resume_epoch" ]; then
            if ! process_log_summary="$(summarize_process_log)"; then
                printf 'Could not summarize the active document-processing log.\n' >&2
                return "$STATUS_DEPENDENCY_FAILURE"
            fi
        fi
    fi
    local documentation_completed
    local latest_documentation_set
    IFS='|' read -r documentation_completed latest_documentation_set <<< "$process_log_summary"
    latest_documentation_set="${latest_documentation_set:-docSet=initializing}"

    local repository_marker_summary
    repository_marker_summary="$(printf '%s\n' "$staging_journal" | awk '
        BEGIN {
            repositories_started = 0
            repositories_completed = 0
            active_repository = "none"
            staging_complete = "false"
        }
        $1 == "REPOSITORY_START" {
            repositories_started++
            active_repository = $3
        }
        $1 == "REPOSITORY_COMPLETE" {
            repositories_completed++
            if (active_repository == $3) {
                active_repository = "none"
            }
        }
        $1 == "LOCAL_STAGING_COMPLETE" {
            staging_complete = "true"
            active_repository = "none"
        }
        END {
            printf "%d|%d|%s|%s\n", repositories_started, repositories_completed, active_repository, staging_complete
        }
    ')"
    local repositories_started
    local repositories_completed
    local active_repository
    local staging_complete
    IFS='|' read -r repositories_started repositories_completed active_repository staging_complete \
        <<< "$repository_marker_summary"

    local embedding_phase
    local active_documentation_set
    if [ "$staging_complete" = true ]; then
        embedding_phase=complete
        documentation_completed="$documentation_total"
        active_documentation_set=complete
        active_repository=none
    elif [ "$repositories_started" -gt 0 ]; then
        embedding_phase=repositories
        documentation_completed="$documentation_total"
        active_documentation_set=complete
        if [ "$active_repository" = none ]; then
            active_repository=between_repositories
        else
            active_repository="${active_repository#data/repos/github/}"
        fi
    elif [ -n "$resume_marker" ]; then
        embedding_phase=documentation
        active_documentation_set="${latest_documentation_set#docSet=}"
        active_repository=waiting_for_documentation
    else
        embedding_phase=inactive
        documentation_completed=0
        latest_documentation_set=docSet=none
        active_documentation_set=none
        active_repository=none
    fi

    local qdrant_summary
    local qdrant_status=0
    if qdrant_summary="$(curl -fsS "$LOCAL_QDRANT_COLLECTION_URL" \
        | jq -r '"points=\(.result.points_count) indexed=\(.result.indexed_vectors_count) status=\(.result.status)"')" \
        && [ -n "$qdrant_summary" ]; then
        printf 'latest=%s %s\n' "$latest_documentation_set" "$qdrant_summary"
    else
        printf 'latest=%s qdrant=unavailable\n' "$latest_documentation_set"
        qdrant_status="$STATUS_DEPENDENCY_FAILURE"
    fi
    printf 'phase=%s documentation=%s/%s active_doc_set=%s\n' \
        "$embedding_phase" "$documentation_completed" "$documentation_total" "$active_documentation_set"
    printf 'repositories=%s/%s started=%s active_repository=%s staging_complete=%s\n' \
        "$repositories_completed" "$repository_total" "$repositories_started" "$active_repository" "$staging_complete"
    if [ "$qdrant_status" -ne 0 ]; then
        return "$qdrant_status"
    fi
    if [ "$staging_complete" = true ]; then
        return "$STATUS_STAGING_COMPLETE"
    fi
}

while true; do
    if report_embedding_status; then
        :
    else
        report_status=$?
        if [ "$report_status" -eq "$STATUS_STAGING_COMPLETE" ]; then
            exit 0
        fi
        exit "$report_status"
    fi
    if [ "$watch_mode" = false ]; then
        exit 0
    fi
    sleep "$WATCH_INTERVAL_SECONDS"
done
