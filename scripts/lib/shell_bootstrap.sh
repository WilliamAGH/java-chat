#!/bin/bash

# Shared shell bootstrap for all scripts in the java-chat project.
# Provides terminal colors, timestamped logging, and command-existence checks.
#
# Source this file after resolving SCRIPT_DIR and PROJECT_ROOT:
#   source "$SCRIPT_DIR/lib/shell_bootstrap.sh"
#
# Color constants use ${VAR:-default} so Makefile-exported values take precedence.
# The log() function writes to LOG_FILE (if set) and to stdout.

# ============================================================================
# Terminal Colors (inherit from environment when exported by Make or parent)
# ============================================================================
RED="${RED:-\033[0;31m}"
GREEN="${GREEN:-\033[0;32m}"
YELLOW="${YELLOW:-\033[1;33m}"
BLUE="${BLUE:-\033[0;34m}"
CYAN="${CYAN:-\033[0;36m}"
MAGENTA="${MAGENTA:-\033[0;35m}"
BOLD="${BOLD:-\033[1m}"
NC="${NC:-\033[0m}"

# ============================================================================
# Logging
# ============================================================================

# Writes a timestamped message to LOG_FILE (when set) and to stdout via echo -e.
# Usage: log "message with ${GREEN}color${NC}"
log() {
    local message="$1"
    if [ -n "${LOG_FILE:-}" ]; then
        echo "[$(date)] $message" >> "$LOG_FILE"
    fi
    echo -e "$message"
}

# ============================================================================
# Prerequisite Checks
# ============================================================================

# Exits with an error when a required CLI tool is missing.
# Usage: require_command jq "brew install jq"
require_command() {
    local command_name="$1"
    local install_hint="${2:-}"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo -e "${RED}Error: '$command_name' not found.${NC}" >&2
        if [ -n "$install_hint" ]; then
            echo -e "${RED}Install with: $install_hint${NC}" >&2
        fi
        exit 1
    fi
}
