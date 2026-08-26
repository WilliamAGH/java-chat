#!/bin/bash

# Reports the durable local embedding phase, progress, and active source.

set -euo pipefail

readonly PROJECT_ROOT="${JAVA_CHAT_PROJECT_ROOT:-$HOME/Developer/git/java-chat}"
readonly PROCESS_LOG="$PROJECT_ROOT/process_qdrant.log"
readonly LOCAL_QDRANT_COLLECTION_URL="http://127.0.0.1:8087/collections/java-chat-qwen3-embedding-4b-2560-docs"
readonly EMBEDDING_SERVICE="java-chat-local-embedding-staging.service"
readonly STAGING_INVOCATION_RECEIPT="$HOME/.local/state/java-chat/local-embedding-staging.invocation"
readonly WATCH_INTERVAL_SECONDS=55
readonly INVOCATION_READ_ATTEMPTS=3

case "${1:-}" in
    "") watch_mode=false ;;
    --watch) watch_mode=true ;;
    *)
        printf 'Usage: %s [--watch]\n' "$0" >&2
        exit 64
        ;;
esac
if [ "$#" -gt 1 ]; then
    printf 'Usage: %s [--watch]\n' "$0" >&2
    exit 64
fi

require_monitoring_command() {
    local monitoring_command="$1"
    if ! command -v "$monitoring_command" >/dev/null 2>&1; then
        printf 'Required monitoring command is unavailable: %s\n' "$monitoring_command" >&2
        exit 69
    fi
}

for monitoring_command in awk curl date journalctl jq sed sleep stat systemctl uname; do
    require_monitoring_command "$monitoring_command"
done

resolve_staging_invocation_id() {
    local active_invocation_id
    active_invocation_id="$(systemctl --user show \
        "$EMBEDDING_SERVICE" --property=InvocationID --value 2>/dev/null || true)"
    if [ -n "$active_invocation_id" ]; then
        printf '%s\n' "$active_invocation_id"
    elif [ -s "$STAGING_INVOCATION_RECEIPT" ]; then
        sed -n '1p' "$STAGING_INVOCATION_RECEIPT"
    fi
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
    ' "$PROCESS_LOG" 2>/dev/null || printf '0|\n'
}

report_embedding_status() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
    if [ -f "$PROCESS_LOG" ]; then
        if [ "$(uname -s)" = Darwin ]; then
            stat -f 'log=%Sm bytes=%z' -t '%Y-%m-%dT%H:%M:%SZ' "$PROCESS_LOG"
        else
            stat -c 'log=%y bytes=%s' "$PROCESS_LOG"
        fi
    else
        printf 'log=missing\n'
    fi

    local staging_journal
    local process_log_summary
    local staging_invocation_before
    local staging_invocation_after
    local invocation_read_attempt
    local stable_snapshot=false
    for ((invocation_read_attempt = 1; invocation_read_attempt <= INVOCATION_READ_ATTEMPTS; invocation_read_attempt++)); do
        staging_invocation_before="$(resolve_staging_invocation_id)"
        process_log_summary="$(summarize_process_log)"
        if [ -n "$staging_invocation_before" ]; then
            staging_journal="$(journalctl --user \
                -u "$EMBEDDING_SERVICE" \
                "_SYSTEMD_INVOCATION_ID=$staging_invocation_before" \
                --no-pager \
                -o cat \
                --grep='^(LOCAL_STAGING_RESUME|REPOSITORY_START|REPOSITORY_COMPLETE|LOCAL_STAGING_COMPLETE)( |$)' \
                2>/dev/null || true)"
        else
            staging_journal=""
        fi
        staging_invocation_after="$(resolve_staging_invocation_id)"
        if [ "$staging_invocation_before" = "$staging_invocation_after" ]; then
            stable_snapshot=true
            break
        fi
    done
    if [ "$stable_snapshot" != true ]; then
        printf 'Staging invocation changed during status collection.\n' >&2
        return 75
    fi

    local latest_documentation_set
    local documentation_completed
    IFS='|' read -r documentation_completed latest_documentation_set <<< "$process_log_summary"
    latest_documentation_set="${latest_documentation_set:-docSet=initializing}"
    printf 'latest=%s ' "$latest_documentation_set"
    curl -fsS "$LOCAL_QDRANT_COLLECTION_URL" \
        | jq -r '"points=\(.result.points_count) indexed=\(.result.indexed_vectors_count) status=\(.result.status)"'

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
        active_documentation_set="${active_documentation_set:-initializing}"
        active_repository=waiting_for_documentation
    else
        embedding_phase=inactive
        active_documentation_set=none
        active_repository=none
    fi

    printf 'phase=%s documentation=%s/%s active_doc_set=%s\n' \
        "$embedding_phase" "$documentation_completed" "$documentation_total" "$active_documentation_set"
    printf 'repositories=%s/%s started=%s active_repository=%s staging_complete=%s\n' \
        "$repositories_completed" "$repository_total" "$repositories_started" "$active_repository" "$staging_complete"
    if [ "$staging_complete" = true ]; then
        return 10
    fi
}

while true; do
    if report_embedding_status; then
        :
    else
        report_status=$?
        if [ "$report_status" -eq 10 ]; then
            exit 0
        fi
        exit "$report_status"
    fi
    if [ "$watch_mode" = false ]; then
        exit 0
    fi
    sleep "$WATCH_INTERVAL_SECONDS"
done
