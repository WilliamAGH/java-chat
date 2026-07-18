#!/bin/bash

# Embedding provider preflight checks shared by ingestion scripts.
#
# Dependencies:
#   - Color constants from common_qdrant.sh: RED, GREEN, YELLOW, BLUE, NC
#   - Environment variables loaded before invocation

normalize_embedding_probe_endpoint() {
    local raw_base_url="$1"
    local trimmed_base_url="${raw_base_url%/}"
    case "$trimmed_base_url" in
        */v1) echo "$trimmed_base_url/embeddings" ;;
        */embeddings) echo "$trimmed_base_url" ;;
        *) echo "$trimmed_base_url/v1/embeddings" ;;
    esac
}

trim_embedding_credential() {
    local embedding_credential="$1"
    embedding_credential="${embedding_credential#"${embedding_credential%%[![:space:]]*}"}"
    embedding_credential="${embedding_credential%"${embedding_credential##*[![:space:]]}"}"
    printf '%s' "$embedding_credential"
}

# Reads a non-secret embedding setting from the application configuration that owns it.
read_embedding_application_property() {
    local property_name="$1"
    local application_properties_file="${PROJECT_ROOT:-}/src/main/resources/application.properties"

    if [ -z "${PROJECT_ROOT:-}" ] || [ ! -f "$application_properties_file" ]; then
        echo "Embedding application properties are unavailable: $application_properties_file" >&2
        return 1
    fi

    awk -F '=' -v expected_property_name="$property_name" '
        $0 !~ /^[[:space:]]*#/ && $1 == expected_property_name {
            configured_value = $0
            sub(/^[^=]*=/, "", configured_value)
            sub(/\r$/, "", configured_value)
            print configured_value
            found_property = 1
            exit
        }
        END {
            exit found_property ? 0 : 1
        }
    ' "$application_properties_file"
}

resolve_embedding_probe_configuration() {
    local provider_label_ref="$1"
    local endpoint_ref="$2"
    local model_ref="$3"
    local key_ref="$4"

    local resolved_provider_label=""
    local resolved_endpoint=""
    local resolved_model_name=""
    local resolved_api_key=""
    local remote_embedding_server_url
    local remote_embedding_model
    local open_ai_embedding_base_url
    local open_ai_embedding_model
    local remote_embedding_api_key
    local open_ai_api_key

    if ! remote_embedding_server_url="$(read_embedding_application_property "app.remote-embedding.server-url")" \
        || ! remote_embedding_model="$(read_embedding_application_property "app.remote-embedding.model")" \
        || ! open_ai_embedding_base_url="$(read_embedding_application_property "app.embeddings.open-ai-base-url")" \
        || ! open_ai_embedding_model="$(read_embedding_application_property "app.embeddings.open-ai-model")"; then
        return 1
    fi

    remote_embedding_api_key="$(trim_embedding_credential "${REMOTE_EMBEDDING_API_KEY:-}")"
    open_ai_api_key="$(trim_embedding_credential "${OPENAI_API_KEY:-}")"

    if [ -n "$remote_embedding_api_key" ]; then
        resolved_provider_label="remote_openai_compatible"
        if [ -n "$remote_embedding_server_url" ]; then
            resolved_endpoint="$(normalize_embedding_probe_endpoint "$remote_embedding_server_url")"
        fi
        resolved_model_name="$remote_embedding_model"
        resolved_api_key="$remote_embedding_api_key"
    elif [ -n "$open_ai_api_key" ]; then
        resolved_provider_label="openai_embeddings"
        resolved_endpoint="$(normalize_embedding_probe_endpoint "$open_ai_embedding_base_url")"
        resolved_model_name="$open_ai_embedding_model"
        resolved_api_key="$open_ai_api_key"
    fi

    printf -v "$provider_label_ref" "%s" "$resolved_provider_label"
    printf -v "$endpoint_ref" "%s" "$resolved_endpoint"
    printf -v "$model_ref" "%s" "$resolved_model_name"
    printf -v "$key_ref" "%s" "$resolved_api_key"
}

validate_embedding_probe_payload() {
    local embedding_endpoint="$1"
    local embedding_key="$2"
    local embedding_model="$3"
    local probe_text="$4"
    local probe_label="$5"
    local log_fn="$6"

    local max_attempts=3
    local retry_delay_seconds=1

    for attempt_number in $(seq 1 "$max_attempts"); do
        local probe_body
        probe_body="$(jq -n \
            --arg model "$embedding_model" \
            --arg text "$probe_text" \
            '{model:$model,input:[$text]}')"

        local response_body_file
        response_body_file="$(mktemp)"
        local response_headers_file
        response_headers_file="$(mktemp)"
        local http_status_code
        http_status_code="$(curl -sS --connect-timeout 5 --max-time 30 \
            -D "$response_headers_file" \
            -o "$response_body_file" \
            -w "%{http_code}" \
            -H "Authorization: Bearer $embedding_key" \
            -H "Content-Type: application/json" \
            --data "$probe_body" \
            "$embedding_endpoint" || true)"
        if [ -z "$http_status_code" ]; then
            http_status_code="000"
        fi

        local probe_ok="false"
        local failure_reason=""
        if [ "$http_status_code" = "200" ]; then
            local embedding_dimensions
            embedding_dimensions="$(jq -r '.data[0].embedding|length // 0' "$response_body_file" 2>/dev/null || echo "0")"
            local null_value_count
            null_value_count="$(jq -r '[.data[0].embedding[]|select(.==null)]|length' "$response_body_file" 2>/dev/null || echo "-1")"
            local non_numeric_value_count
            non_numeric_value_count="$(jq -r '[.data[0].embedding[]|select((.!=null) and (type!="number"))]|length' "$response_body_file" 2>/dev/null || echo "-1")"

            if [ "$embedding_dimensions" -le 0 ]; then
                failure_reason="missing embedding vector in response payload"
            elif [ "$null_value_count" -gt 0 ]; then
                failure_reason="embedding payload contains $null_value_count null value(s) out of $embedding_dimensions"
            elif [ "$non_numeric_value_count" -gt 0 ]; then
                failure_reason="embedding payload contains $non_numeric_value_count non-numeric value(s)"
            else
                probe_ok="true"
            fi
        else
            failure_reason="HTTP $http_status_code"
            local rate_limit_header
            rate_limit_header="$(grep -i '^retry-after:' "$response_headers_file" | tail -n 1 | tr -d '\r' || true)"
            if [ -n "$rate_limit_header" ]; then
                failure_reason="$failure_reason ($rate_limit_header)"
            fi
        fi

        if [ "$probe_ok" = "true" ]; then
            rm -f "$response_body_file" "$response_headers_file"
            return 0
        fi

        if [ "$attempt_number" -lt "$max_attempts" ]; then
            $log_fn "${YELLOW}Embedding probe '$probe_label' failed on attempt $attempt_number/$max_attempts; retrying in ${retry_delay_seconds}s (${failure_reason})${NC}"
            sleep "$retry_delay_seconds"
            retry_delay_seconds=$((retry_delay_seconds * 2))
            rm -f "$response_body_file" "$response_headers_file"
            continue
        fi

        local response_excerpt
        response_excerpt="$(head -c 300 "$response_body_file" | tr '\n' ' ')"
        rm -f "$response_body_file" "$response_headers_file"
        $log_fn "${RED}Embedding probe '$probe_label' failed after $max_attempts attempt(s): ${failure_reason}${NC}"
        if [ -n "$response_excerpt" ]; then
            $log_fn "${YELLOW}Probe response excerpt: ${response_excerpt}${NC}"
        fi
        return 1
    done
}

# Validates embedding provider connectivity and payload shape.
# Returns 0 on successful probes, 1 when provider config or probes fail.
#
# EMBEDDING_CODE_PROBE_MODE:
#   strict (default) -> fail if code-like probe fails
#   warn             -> emit warning and continue when code-like probe fails
check_embedding_server() {
    local log_fn="${1:-echo -e}"

    if [ "${APP_LOCAL_EMBEDDING_ENABLED:-false}" = "true" ]; then
        $log_fn "${YELLOW}Checking local embedding server...${NC}"
        local url="${LOCAL_EMBEDDING_SERVER_URL:-http://127.0.0.1:8088}/v1/models"
        local response
        response="$(curl -sS --connect-timeout 5 --max-time 10 -o /dev/null -w "%{http_code}" "$url" || true)"
        if [ -z "$response" ]; then
            response="000"
        fi
        if [ "$response" = "200" ]; then
            $log_fn "${GREEN}Local embedding server is healthy${NC}"
            return 0
        fi
        $log_fn "${RED}Local embedding server not responding (HTTP $response)${NC}"
        return 1
    fi

    local provider_label=""
    local embedding_endpoint=""
    local embedding_model=""
    local embedding_key=""
    if ! resolve_embedding_probe_configuration provider_label embedding_endpoint embedding_model embedding_key; then
        $log_fn "${RED}Embedding application configuration is unavailable${NC}"
        return 1
    fi

    if [ -z "$embedding_endpoint" ] || [ -z "$embedding_model" ] || [ -z "$embedding_key" ]; then
        $log_fn "${RED}Remote embedding provider not fully configured${NC}"
        $log_fn "${YELLOW}Configure app.remote-embedding.server-url plus REMOTE_EMBEDDING_API_KEY, or OPENAI_API_KEY plus app.embeddings.open-ai-model${NC}"
        return 1
    fi
    if [[ "$embedding_endpoint" == *"models.github.ai"* ]]; then
        $log_fn "${RED}Invalid embedding endpoint: GitHub Models does not provide embeddings API${NC}"
        $log_fn "${YELLOW}Use app.remote-embedding.server-url or app.embeddings.open-ai-base-url for a provider that supports /v1/embeddings${NC}"
        return 1
    fi

    $log_fn "${BLUE}Using remote embedding provider ($provider_label)${NC}"
    $log_fn "${BLUE}Embedding endpoint: $embedding_endpoint${NC}"
    $log_fn "${BLUE}Embedding model: $embedding_model${NC}"

    if ! validate_embedding_probe_payload \
        "$embedding_endpoint" \
        "$embedding_key" \
        "$embedding_model" \
        "embedding preflight health check" \
        "plain-text" \
        "$log_fn"; then
        return 1
    fi

    if validate_embedding_probe_payload \
        "$embedding_endpoint" \
        "$embedding_key" \
        "$embedding_model" \
        $'public class ProbeExample {\n  private int count = 1;\n  public void increment() { count++; }\n}' \
        "code-like" \
        "$log_fn"; then
        $log_fn "${GREEN}Remote embedding endpoint probes passed${NC}"
        return 0
    fi

    local probe_mode="${EMBEDDING_CODE_PROBE_MODE:-strict}"
    if [ "$probe_mode" = "warn" ]; then
        $log_fn "${YELLOW}Warning: remote embedding endpoint failed code-like probe; source ingestion may fail on some files${NC}"
        $log_fn "${YELLOW}Continuing because EMBEDDING_CODE_PROBE_MODE=warn was set explicitly.${NC}"
        return 0
    fi

    $log_fn "${RED}Remote embedding endpoint failed code-like probe; refusing ingestion (strict mode)${NC}"
    $log_fn "${YELLOW}If you intentionally want to continue despite this risk, set EMBEDDING_CODE_PROBE_MODE=warn for this run.${NC}"
    return 1
}
