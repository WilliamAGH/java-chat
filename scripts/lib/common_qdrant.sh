#!/bin/bash

# Shared shell functions for Qdrant ingestion pipeline scripts.
# Source this file instead of duplicating env loading, connectivity checks,
# build logic, and process lifecycle management across pipeline scripts.
#
# Usage: source "$SCRIPT_DIR/lib/common_qdrant.sh"

# Color constants (inherit from environment if already set)
RED="${RED:-\033[0;31m}"
GREEN="${GREEN:-\033[0;32m}"
YELLOW="${YELLOW:-\033[1;33m}"
BLUE="${BLUE:-\033[0;34m}"
CYAN="${CYAN:-\033[0;36m}"
NC="${NC:-\033[0m}"

# Loads the .env file from PROJECT_ROOT, preserving any preset APP_LOCAL_EMBEDDING_ENABLED.
# Exits with error if .env is not found.
#
# Requires: PROJECT_ROOT set by the calling script.
load_env_file() {
    if [ -f "$PROJECT_ROOT/.env" ]; then
        PRESET_APP_LOCAL_EMBEDDING_ENABLED="${APP_LOCAL_EMBEDDING_ENABLED:-}"
        set -a
        # shellcheck disable=SC1091
        source "$PROJECT_ROOT/.env"
        set +a
        if [ -n "$PRESET_APP_LOCAL_EMBEDDING_ENABLED" ]; then
            APP_LOCAL_EMBEDDING_ENABLED="$PRESET_APP_LOCAL_EMBEDDING_ENABLED"
            export APP_LOCAL_EMBEDDING_ENABLED
        fi
        echo -e "${GREEN}Environment variables loaded${NC}"
    else
        echo -e "${RED}.env file not found${NC}"
        exit 1
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

    local curl_opts=(-s -o /dev/null -w "%{http_code}")
    if [ -n "${QDRANT_API_KEY:-}" ]; then
        curl_opts+=(-H "api-key: $QDRANT_API_KEY")
    fi

    local response
    response=$(curl "${curl_opts[@]}" "$url" || echo "000")

    if [ "$response" != "200" ]; then
        $log_fn "${RED}Qdrant connection failed (HTTP $response)${NC}"
        $log_fn "${YELLOW}URL: $url${NC}"
        return 1
    fi

    $log_fn "${GREEN}Qdrant connection successful${NC}"
    return 0
}

# Validates the embedding server is reachable (local or remote).
# Returns 0 if healthy or using a remote provider, 1 if local server is unreachable.
check_embedding_server() {
    local log_fn="${1:-echo -e}"
    if [ "${APP_LOCAL_EMBEDDING_ENABLED:-false}" = "true" ]; then
        $log_fn "${YELLOW}Checking local embedding server...${NC}"
        local url="${LOCAL_EMBEDDING_SERVER_URL:-http://127.0.0.1:1234}/v1/models"
        local response
        response=$(curl -sS --max-time 10 -o /dev/null -w "%{http_code}" "$url" || echo "000")
        if [ "$response" = "200" ]; then
            $log_fn "${GREEN}Local embedding server is healthy${NC}"
            return 0
        fi
        $log_fn "${RED}Local embedding server not responding (HTTP $response)${NC}"
        return 1
    fi

    $log_fn "${BLUE}Using remote embedding provider${NC}"
    return 0
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
        echo -e "${RED}Build failed${NC}"
        exit 1
    fi
    echo -e "${GREEN}Build succeeded${NC}"
}

# Locates the runnable JAR in build/libs, excluding plain JARs.
# Prints the JAR path to stdout; exits with error if not found.
locate_app_jar() {
    local app_jar
    app_jar=$(ls -1 "$PROJECT_ROOT/build/libs/"*.jar 2>/dev/null | grep -v -- "-plain.jar" | head -1 || true)
    if [ -z "$app_jar" ]; then
        echo -e "${RED}Failed to locate runnable jar${NC}" >&2
        exit 1
    fi
    echo "$app_jar"
}

# Manages PID file lifecycle: kills any existing process, registers cleanup trap.
# Sets APP_PID to empty initially; the caller must set it after launching the Java process.
#
# Requires: PID_FILE set by the calling script.
setup_pid_and_cleanup() {
    if [ -f "$PID_FILE" ]; then
        local existing_pid
        existing_pid=$(cat "$PID_FILE" 2>/dev/null || true)
        if [ -n "$existing_pid" ] && kill -0 "$existing_pid" 2>/dev/null; then
            echo -e "${YELLOW}Stopping previous processor (PID: $existing_pid)...${NC}"
            kill -TERM "$existing_pid" 2>/dev/null || true
            sleep 2
        fi
        rm -f "$PID_FILE"
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
    rm -f "$PID_FILE"
    exit 0
}

# Monitors a background Java process for completion by watching a log file
# for the "DOCUMENT PROCESSING COMPLETE" marker.
# Exits with error if the process terminates unexpectedly.
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
    local last_files=0
    local files_count=0
    local elapsed=0

    while true; do
        if grep -q "DOCUMENT PROCESSING COMPLETE" "$log_file" 2>/dev/null; then
            echo ""
            echo -e "${GREEN}Processing completed${NC} ($files_count files, ${elapsed}s)"
            break
        fi

        if ! kill -0 "$java_pid" 2>/dev/null; then
            echo ""
            echo -e "${RED}Application terminated unexpectedly${NC}"
            rm -f "$pid_file"
            exit 1
        fi

        local current_time
        current_time=$(date +%s)
        elapsed=$((current_time - start_time))
        files_count=$(grep -c "Completed processing" "$log_file" 2>/dev/null || true)
        files_count=$(echo "${files_count:-0}" | tr -dc '0-9')
        files_count=${files_count:-0}

        if [ "$files_count" -gt "$last_files" ]; then
            echo -ne "\r${YELLOW}Files: $files_count (${elapsed}s)${NC}     "
            last_files=$files_count
        fi

        sleep 2
    done
}
