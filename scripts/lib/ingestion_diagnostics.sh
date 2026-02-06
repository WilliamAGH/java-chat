#!/bin/bash

# Ingestion failure diagnostics: log parsing, failure classification, and
# post-mortem reporting for GitHub repository ingestion pipelines.
#
# Dependencies: source common_qdrant.sh first (color constants,
# resolve_embedding_probe_configuration).
#
# Usage: source "$SCRIPT_DIR/lib/ingestion_diagnostics.sh"

strip_ansi_sequences() {
    sed -E 's/\x1B\[[0-9;]*[A-Za-z]//g'
}

clean_exception_prefixes() {
    sed -E \
        -e 's/^[[:space:]]+//' \
        -e 's/^Caused by: //' \
        -e 's/^com\.williamcallahan\.javachat\.service\.EmbeddingServiceUnavailableException: //' \
        -e 's/^java\.[A-Za-z0-9\.$]+: //'
}

detect_failure_source() {
    local log_file_path="$1"
    local failure_api="Application"

    if grep -Eq "OpenAiCompatibleEmbeddingClient|Embedding failed for batch|Remote embedding provider|Remote embedding response validation" "$log_file_path"; then
        failure_api="AI Embedding API"
    elif grep -Eq "Qdrant|QDRANT|StatusRuntimeException|Upserted .* hybrid points|Qdrant operation failed" "$log_file_path"; then
        failure_api="Qdrant API"
    elif grep -Eq "api.github.com|GitHub API|rate limit exceeded|gh repo view|git ls-remote|git fetch|git pull" "$log_file_path"; then
        failure_api="GitHub API"
    fi

    echo "$failure_api"
}

detect_rate_limit_context() {
    local log_file_path="$1"
    local rate_limit_line
    rate_limit_line="$(grep -Ei "HTTP 429|too many requests|rate limit|resource exhausted|x-ratelimit" "$log_file_path" | tail -n 1 || true)"

    if [ -z "$rate_limit_line" ]; then
        echo "No rate limit detected"
        return
    fi

    local rate_limited_api="unknown API"
    if echo "$rate_limit_line" | grep -Eqi "OpenAiCompatibleEmbeddingClient|embedding|openai|remote provider"; then
        rate_limited_api="AI Embedding API"
    elif echo "$rate_limit_line" | grep -Eqi "Qdrant|QDRANT|grpc|resource exhausted"; then
        rate_limited_api="Qdrant API"
    elif echo "$rate_limit_line" | grep -Eqi "github|api.github.com|gh "; then
        rate_limited_api="GitHub API"
    fi

    echo "$rate_limited_api: $(echo "$rate_limit_line" | strip_ansi_sequences)"
}

detect_endpoint_behavior() {
    local log_file_path="$1"

    if grep -Eq "all [0-9]+ dimensions are null|Remote embedding payload invalid|Null embedding value at index|missing embedding for index|missing embedding entries|response was null" "$log_file_path"; then
        echo "Remote embedding endpoint returned HTTP 200 with invalid embedding payload. Likely causes: wrong endpoint (expected /v1/embeddings), non-embedding model, or provider payload bug."
        return
    fi

    local http_failure_line
    http_failure_line="$(grep -E "Remote embedding provider returned HTTP [0-9]+" "$log_file_path" | tail -n 1 || true)"
    if [ -n "$http_failure_line" ]; then
        echo "$http_failure_line" | strip_ansi_sequences
        return
    fi

    echo "No explicit remote endpoint anomaly detected"
}

extract_batch_file_url_from_failure() {
    local log_file_path="$1"
    local batch_line
    batch_line="$(grep -E "Embedding failed for batch .*firstUrl=" "$log_file_path" | tail -n 1 || true)"
    if [ -z "$batch_line" ]; then
        echo "unknown"
        return
    fi
    echo "$batch_line" | sed -E 's/.*firstUrl=([^,]+).*/\1/'
}

extract_retry_backoff_schedule() {
    local log_file_path="$1"
    local schedule
    schedule="$(grep -Eo "retrying in [0-9]+ms" "$log_file_path" | sed -E 's/retrying in ([0-9]+)ms/\1ms/' | paste -sd "," - || true)"
    if [ -z "$schedule" ]; then
        echo "none"
        return
    fi
    echo "$schedule"
}

extract_null_embedding_index() {
    local log_file_path="$1"
    local null_index
    null_index="$(grep -Eo "Null embedding value at index [0-9]+" "$log_file_path" | tail -n 1 | awk '{print $6}' || true)"
    if [ -z "$null_index" ]; then
        null_index="$(grep -Eo "first null index [0-9]+" "$log_file_path" | tail -n 1 | awk '{print $4}' || true)"
    fi
    if [ -z "$null_index" ]; then
        echo "unknown"
        return
    fi
    echo "$null_index"
}

extract_upstream_http_status() {
    local log_file_path="$1"
    local status_code
    status_code="$(grep -Eo "Remote embedding provider returned HTTP [0-9]+" "$log_file_path" | tail -n 1 | awk '{print $6}' || true)"
    if [ -n "$status_code" ]; then
        echo "$status_code"
        return
    fi
    if grep -Eq "all [0-9]+ dimensions are null|Remote embedding payload invalid|Null embedding value at index|missing embedding for index|missing embedding entries|response was null" "$log_file_path"; then
        echo "200 (payload invalid)"
        return
    fi
    echo "unknown"
}

extract_failure_headline() {
    local log_file_path="$1"
    grep -E "Embedding failed for batch|Remote embedding provider returned HTTP|Remote embedding response validation failed|Remote embedding call failed|Qdrant operation failed|Application run failed|GitHub repo processing completed with [0-9]+ failed file\(s\)" "$log_file_path" \
        | grep -v "Caused by:" \
        | tail -n 1 || true
}

extract_root_cause() {
    local log_file_path="$1"
    local root_cause_line
    root_cause_line="$(grep -E "Caused by:" "$log_file_path" | tail -n 1 || true)"
    if [ -n "$root_cause_line" ]; then
        echo "$root_cause_line" | strip_ansi_sequences | clean_exception_prefixes
    else
        echo "unknown"
    fi
}

classify_ingestion_error_code() {
    local failure_source="$1"
    local null_value_index="$2"
    local upstream_http_status="$3"

    if [ "$failure_source" = "AI Embedding API" ] && [ "$upstream_http_status" = "200 (payload invalid)" ]; then
        echo "EMBEDDING_RESPONSE_INVALID_PAYLOAD"
    elif [ "$failure_source" = "AI Embedding API" ] && [ "$null_value_index" != "unknown" ]; then
        echo "EMBEDDING_RESPONSE_NULL_VALUE"
    elif [ "$failure_source" = "AI Embedding API" ] && [[ "$upstream_http_status" =~ ^[0-9]+$ ]]; then
        echo "EMBEDDING_UPSTREAM_HTTP_ERROR"
    elif [ "$failure_source" = "Qdrant API" ]; then
        echo "QDRANT_UPSERT_FAILURE"
    elif [ "$failure_source" = "GitHub API" ]; then
        echo "GITHUB_SOURCE_FAILURE"
    else
        echo "INGESTION_FAILURE_UNKNOWN"
    fi
}

print_retry_trace() {
    local log_file_path="$1"
    local retry_trace
    retry_trace="$(grep -E "transient HTTP|retrying in [0-9]+ms|Retryable OpenAI SDK failure|response validation failed|STEP 1: EMBEDDING GENERATION - Failed" "$log_file_path" | tail -n 12 || true)"
    if [ -n "$retry_trace" ]; then
        echo ""
        echo "Recent retry/error trace:"
        echo "$retry_trace" | strip_ansi_sequences
    fi
}

# Prints a structured failure summary after an ingestion run fails.
# Uses resolve_embedding_probe_configuration from common_qdrant.sh to
# resolve embedding provider details (replaces the former duplicate
# resolve_embedding_configuration function).
print_processing_failure_summary() {
    local log_file_path="$1"

    echo ""
    echo -e "${RED}Failure diagnostics:${NC}"

    local failure_source
    failure_source="$(detect_failure_source "$log_file_path")"
    local upstream_http_status
    upstream_http_status="$(extract_upstream_http_status "$log_file_path")"
    local null_value_index
    null_value_index="$(extract_null_embedding_index "$log_file_path")"

    local provider_label=""
    local embedding_endpoint=""
    local embedding_model=""
    local embedding_key=""
    resolve_embedding_probe_configuration provider_label embedding_endpoint embedding_model embedding_key

    local failure_headline
    failure_headline="$(extract_failure_headline "$log_file_path")"
    if [ -n "$failure_headline" ]; then
        echo "  Failure: $(echo "$failure_headline" | strip_ansi_sequences | clean_exception_prefixes)"
    fi

    local error_code
    error_code="$(classify_ingestion_error_code "$failure_source" "$null_value_index" "$upstream_http_status")"

    echo "  Error code: $error_code"
    echo "  API source: $failure_source"
    echo "  Provider profile: ${provider_label:-unknown}"
    echo "  Endpoint: ${embedding_endpoint:-unknown}"
    echo "  Model: ${embedding_model:-unknown}"
    echo "  Upstream HTTP: $upstream_http_status"
    echo "  Rate limit: $(detect_rate_limit_context "$log_file_path")"
    echo "  Endpoint behavior: $(detect_endpoint_behavior "$log_file_path")"
    echo "  Failing file URL: $(extract_batch_file_url_from_failure "$log_file_path")"
    echo "  Root cause: $(extract_root_cause "$log_file_path")"
    echo "  Retry backoff schedule: $(extract_retry_backoff_schedule "$log_file_path")"

    print_retry_trace "$log_file_path"

    echo ""
    echo "Log file: $log_file_path"
}
