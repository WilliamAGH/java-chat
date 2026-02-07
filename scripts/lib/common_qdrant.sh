#!/bin/bash

# Shared shell functions for Qdrant ingestion pipeline scripts.
# Source this file instead of duplicating env loading, connectivity checks,
# build logic, and process lifecycle management across pipeline scripts.
#
# Usage: source "$SCRIPT_DIR/lib/common_qdrant.sh"

COMMON_QDRANT_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=shell_bootstrap.sh
source "$COMMON_QDRANT_LIB_DIR/shell_bootstrap.sh"
# shellcheck source=env_loader.sh
source "$COMMON_QDRANT_LIB_DIR/env_loader.sh"
# shellcheck source=embedding_preflight.sh
source "$COMMON_QDRANT_LIB_DIR/embedding_preflight.sh"

require_command jq "brew install jq"

# Wraps curl with conditional Qdrant API key header injection.
# All positional arguments are forwarded to curl unchanged.
#
# Usage: qdrant_curl -s -o /dev/null -w "%{http_code}" "$url"
qdrant_curl() {
    if [ -n "${QDRANT_API_KEY:-}" ]; then
        curl -H "api-key: $QDRANT_API_KEY" "$@"
    else
        curl "$@"
    fi
}

# Loads the .env file from PROJECT_ROOT, preserving any pre-existing environment
# variable overrides so callers can override individual settings via the command line.
# Continues when .env is missing so pure-environment execution still works.
#
# Requires: PROJECT_ROOT set by the calling script.
load_env_file() {
    if [ -f "$PROJECT_ROOT/.env" ]; then
        if ! preserve_process_env_then_source_file "$PROJECT_ROOT/.env"; then
            echo -e "${RED}Failed to load environment variables${NC}"
            exit 1
        fi

        echo -e "${GREEN}Environment variables loaded${NC}"
    else
        echo -e "${YELLOW}.env file not found; using exported environment variables and defaults${NC}"
    fi
}

# Applies script-level defaults after environment loading so precedence remains:
# exported env > .env > defaults.
apply_pipeline_defaults() {
    export QDRANT_HOST="${QDRANT_HOST:-localhost}"
    export QDRANT_PORT="${QDRANT_PORT:-6334}"
    export QDRANT_SSL="${QDRANT_SSL:-false}"
    export APP_LOCAL_EMBEDDING_ENABLED="${APP_LOCAL_EMBEDDING_ENABLED:-false}"

    if [ "${QDRANT_SSL}" = "true" ] || [ "${QDRANT_SSL}" = "1" ]; then
        # Cloud/Qdrant-managed HTTPS endpoints typically use default TLS port.
        # Keep any explicitly provided REST port, but do not force a local default.
        if [ -n "${QDRANT_REST_PORT:-}" ]; then
            export QDRANT_REST_PORT
        fi
    else
        export QDRANT_REST_PORT="${QDRANT_REST_PORT:-8087}"
    fi
}

# Validates that all required environment variables are set.
# Exits with error listing any missing variables.
#
# Arguments: variable names to check
validate_required_vars() {
    local missing_vars=()
    for var in "$@"; do
        if [ -z "${!var:-}" ]; then
            missing_vars+=("$var")
        fi
    done

    if [ ${#missing_vars[@]} -gt 0 ]; then
        echo -e "${RED}Missing required environment variables:${NC}"
        printf '%s\n' "${missing_vars[@]}"
        exit 1
    fi
    echo -e "${GREEN}All required environment variables present${NC}"
}

# Computes the Qdrant REST base URL from environment variables.
# Supports SSL and custom port configurations.
qdrant_rest_base_url() {
    if [ "${QDRANT_SSL:-false}" = "true" ] || [ "${QDRANT_SSL:-false}" = "1" ]; then
        if [ -n "${QDRANT_REST_PORT:-}" ]; then
            echo "https://${QDRANT_HOST}:${QDRANT_REST_PORT}"
        else
            echo "https://${QDRANT_HOST}"
        fi
    else
        echo "http://${QDRANT_HOST}:${QDRANT_REST_PORT:-8087}"
    fi
}

# Validates Qdrant connectivity by hitting the /collections endpoint.
# Returns 0 on success, 1 on failure.
#
# Requires: QDRANT_HOST, QDRANT_REST_PORT (or defaults) set in environment.
check_qdrant_connection() {
    local log_fn="${1:-echo -e}"
    $log_fn "${YELLOW}Checking Qdrant connection...${NC}"

    local base_url
    base_url=$(qdrant_rest_base_url)
    local url="${base_url}/collections"

    local response
    response="$(qdrant_curl -s --connect-timeout 5 --max-time 20 -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || true)"
    if [ -z "$response" ]; then
        response="000"
    fi

    if [ "$response" != "200" ]; then
        $log_fn "${RED}Qdrant connection failed (HTTP $response)${NC}"
        $log_fn "${YELLOW}URL: $url${NC}"
        if { [ "${QDRANT_SSL:-false}" = "true" ] || [ "${QDRANT_SSL:-false}" = "1" ]; } && [ "${QDRANT_REST_PORT:-}" = "8087" ]; then
            $log_fn "${YELLOW}Hint: Qdrant Cloud usually uses HTTPS default port (443) or 6333, not 8087. Unset QDRANT_REST_PORT or set it explicitly.${NC}"
        fi
        return 1
    fi

    $log_fn "${GREEN}Qdrant connection successful${NC}"
    return 0
}

# Loads environment, validates required variables, and checks Qdrant/embedding connectivity.
# Exits with error if any prerequisite fails.
#
# Arguments: required environment variable names to validate
initialize_pipeline() {
    load_env_file
    apply_pipeline_defaults
    validate_required_vars "$@"
    echo ""

    if ! check_qdrant_connection "echo -e"; then
        echo -e "${RED}Cannot proceed without Qdrant connectivity${NC}"
        exit 1
    fi

    if ! check_embedding_server "echo -e"; then
        echo -e "${RED}Embedding provider check failed${NC}"
        exit 1
    fi
}

# Builds the application using gradlew buildForScripts.
# Exits with error if the build fails.
#
# Requires: PROJECT_ROOT set by the calling script.
build_application() {
    local log_file="${1:-/dev/null}"
    echo -e "${YELLOW}Building application...${NC}"
    cd "$PROJECT_ROOT" || exit 1
    if ! ./gradlew buildForScripts --no-configuration-cache --quiet >> "$log_file" 2>&1; then
        echo -e "${RED}Build failed. Last 20 lines of build output:${NC}"
        tail -20 "$log_file" 2>/dev/null || true
        exit 1
    fi
    echo -e "${GREEN}Build succeeded${NC}"
}

# Locates the runnable JAR in build/libs, excluding plain JARs.
# Prints the JAR path to stdout; exits with error if not found.
locate_app_jar() {
    local jar_path
    jar_path="$(ls -t "$PROJECT_ROOT"/build/libs/*.jar 2>/dev/null | grep -v '\-plain\.jar' | head -n 1)"
    if [ -z "$jar_path" ]; then
        echo -e "${RED}Failed to locate runnable jar in build/libs/${NC}" >&2
        exit 1
    fi
    echo "$jar_path"
}

# Manages PID file lifecycle: kills any existing process, registers cleanup trap.
# Sets APP_PID to empty initially; the caller must set it after launching the Java process.
#
# Arguments:
#   $1 - PID file path
setup_pid_and_cleanup() {
    local pid_file="$1"
    if [ -z "$pid_file" ]; then
        echo -e "${RED}setup_pid_and_cleanup requires a PID file path${NC}" >&2
        exit 1
    fi

    COMMON_PID_FILE="$pid_file"
    if [ -f "$COMMON_PID_FILE" ]; then
        local existing_pid
        existing_pid=$(cat "$COMMON_PID_FILE" 2>/dev/null || true)
        if [ -n "$existing_pid" ] && kill -0 "$existing_pid" 2>/dev/null; then
            echo -e "${YELLOW}Stopping previous processor (PID: $existing_pid)...${NC}"
            kill -TERM "$existing_pid" 2>/dev/null || true
            sleep 2
        fi
        rm -f "$COMMON_PID_FILE"
    fi

    APP_PID=""
    trap '_common_cleanup' INT TERM
}

# Internal cleanup handler used by setup_pid_and_cleanup.
_common_cleanup() {
    echo ""
    echo -e "${YELLOW}Received interrupt signal. Shutting down...${NC}"
    if [ -n "${APP_PID:-}" ] && kill -0 "$APP_PID" 2>/dev/null; then
        kill -TERM "$APP_PID" 2>/dev/null || true
    fi
    rm -f "${COMMON_PID_FILE:-}"
    exit 0
}

# Monitors a background Java process for completion by watching a log file
# for the "DOCUMENT PROCESSING COMPLETE" marker.
# Exits successfully when the process completes (with or without marker).
# Exits with error if the process fails unexpectedly with exit code > 0.
#
# Tracks progress via actual Java log patterns:
#   "Files to process: N"              -> total files declared
#   "[INDEXING] Processing file with"   -> files started
#   "EMBEDDING GENERATION - Completed"  -> chunk batches finished
#   "Processed N files in"             -> doc set finished
#
# Arguments:
#   $1 - PID of the Java process
#   $2 - log file to monitor
#   $3 - PID file to clean up on exit
monitor_java_process() {
    local java_pid="$1"
    local log_file="$2"
    local pid_file="$3"
    local start_time
    start_time=$(date +%s)
    local last_display_hash=""
    local elapsed=0

    while true; do
        if grep -q "DOCUMENT PROCESSING COMPLETE" "$log_file" 2>/dev/null; then
            local total_files_declared
            total_files_declared=$({ grep -o 'Files to process: [0-9]*' "$log_file" 2>/dev/null || true; } \
                | awk -F': ' '{s+=$2} END {print s+0}')
            local total_files_started
            total_files_started=$(grep -c "Processing file with" "$log_file" 2>/dev/null || true)
            total_files_started=${total_files_started:-0}
            echo ""
            echo -e "${GREEN}Processing completed${NC} ($total_files_started/$total_files_declared files, ${elapsed}s)"
            break
        fi

        if ! kill -0 "$java_pid" 2>/dev/null; then
            # Process has terminated. Check if it completed successfully or failed.
            local exit_status
            wait "$java_pid" 2>/dev/null
            exit_status=$?
            
            # Exit code 143 means the process was terminated by signal 15 (SIGTERM), which is normal.
            # Exit code 0 means successful completion (even without the marker).
            if [ "$exit_status" -eq 0 ] || [ "$exit_status" -eq 143 ]; then
                local total_files_declared
                total_files_declared=$({ grep -o 'Files to process: [0-9]*' "$log_file" 2>/dev/null || true; } \
                    | awk -F': ' '{s+=$2} END {print s+0}')
                local total_files_started
                total_files_started=$(grep -c "Processing file with" "$log_file" 2>/dev/null || true)
                total_files_started=${total_files_started:-0}
                echo ""
                echo -e "${GREEN}Processing completed${NC} ($total_files_started/$total_files_declared files, ${elapsed}s)"
                break
            else
                echo ""
                echo -e "${RED}Application failed with exit code $exit_status${NC}"
                rm -f "$pid_file"
                exit 1
            fi
        fi

        local current_time
        current_time=$(date +%s)
        elapsed=$((current_time - start_time))

        local files_declared
        files_declared=$({ grep -o 'Files to process: [0-9]*' "$log_file" 2>/dev/null || true; } \
            | awk -F': ' '{s+=$2} END {print s+0}')
        local files_started
        files_started=$(grep -c "Processing file with" "$log_file" 2>/dev/null || true)
        files_started=${files_started:-0}
        local chunks_done
        chunks_done=$(grep -c "EMBEDDING GENERATION - Completed" "$log_file" 2>/dev/null || true)
        chunks_done=${chunks_done:-0}

        local display_hash="${files_started}:${chunks_done}"
        if [ "$display_hash" != "$last_display_hash" ] || [ $((elapsed % 10)) -eq 0 ]; then
            local progress_text=""
            if [ "$files_declared" -gt 0 ]; then
                local percent=$((files_started * 100 / files_declared))
                progress_text="Files: ${files_started}/${files_declared} (${percent}%) | Chunks: ${chunks_done}"
            elif [ "$files_started" -gt 0 ]; then
                progress_text="Files: ${files_started} | Chunks: ${chunks_done}"
            else
                progress_text="Waiting for processing to start..."
            fi
            echo -ne "\r${YELLOW}${progress_text} (${elapsed}s)${NC}          "
            last_display_hash="$display_hash"
        fi

        sleep 2
    done
}

# Returns the point count for a Qdrant collection, or "unknown" on failure.
collection_point_count() {
    local collection_name="$1"
    local qdrant_base_url="$2"
    local collection_state
    collection_state="$(qdrant_curl -s \
        "$qdrant_base_url/collections/$collection_name" 2>/dev/null || echo "")"
    if [ -z "$collection_state" ]; then
        echo "unknown"
        return
    fi
    echo "$collection_state" \
        | jq -r '.result.points_count // "unknown"' \
        2>/dev/null || echo "unknown"
}

# Creates a Qdrant collection by cloning vector config from a reference collection.
create_collection_from_reference() {
    local collection_name="$1"
    local qdrant_base_url="$2"
    local reference_collection="$3"
    local reference_collection_state
    reference_collection_state="$(qdrant_curl -s \
        "$qdrant_base_url/collections/$reference_collection" 2>/dev/null || echo "")"
    if [ -z "$reference_collection_state" ]; then
        echo -e "${RED}Failed to read reference collection '$reference_collection'${NC}"
        exit 1
    fi
    local vectors_config
    vectors_config="$(echo "$reference_collection_state" | jq -c '{
        vectors: .result.config.params.vectors // {},
        sparse_vectors: .result.config.params.sparse_vectors // {},
        on_disk_payload: true
    }' 2>/dev/null || echo "")"
    if [ -z "$vectors_config" ]; then
        echo -e "${RED}Failed to extract vector config from reference collection${NC}"
        exit 1
    fi
    local create_response
    create_response="$(qdrant_curl -s -w "\n%{http_code}" \
        -X PUT \
        -H "Content-Type: application/json" \
        -d "$vectors_config" \
        "$qdrant_base_url/collections/$collection_name" 2>/dev/null || echo "")"
    local create_http_code
    create_http_code="$(echo "$create_response" | tail -1)"
    local create_body
    create_body="$(echo "$create_response" | sed '$d')"
    if [ "$create_http_code" != "200" ]; then
        echo -e "${RED}Failed to create collection (HTTP $create_http_code): $create_body${NC}"
        exit 1
    fi
    echo -e "${GREEN}Collection '$collection_name' created${NC}"
}
