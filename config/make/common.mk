# Common Makefile variables and functions for java-chat
# Include this in the main Makefile with: include config/make/common.mk

# ============================================================================
# Shell and Build Tools
# ============================================================================
SHELL := /bin/bash
GRADLEW := ./gradlew -Dorg.gradle.vfs.watch=false  # override global ~/.gradle/gradle.properties and stale daemon
LOCKED_GRADLEW := ./scripts/with_build_state_lock.sh $(GRADLEW)

# ============================================================================
# Application Configuration
# ============================================================================
APP_NAME := java-chat
QDRANT_COMPOSE_FILE := infra/docker-compose-qdrant.yml

# Port configuration
DEFAULT_PORT := 8085
MIN_PORT := 8085
MAX_PORT := 8090
DEFAULT_LIVERELOAD_PORT := 35730

# ============================================================================
# Terminal Colors
# ============================================================================
RED    := \033[0;31m
GREEN  := \033[0;32m
YELLOW := \033[0;33m
CYAN   := \033[0;36m
NC     := \033[0m

# Export color codes for use in scripts
export RED GREEN YELLOW CYAN NC
export PROJECT_ROOT := $(shell pwd)

# ============================================================================
# JAR Path Resolution
# ============================================================================
# Compute JAR lazily so it's resolved after the build runs
# Use a function instead of variable to evaluate at runtime
# Exclude -plain.jar which is the non-bootable archive
get_jar = $(shell ls -t build/libs/*.jar 2>/dev/null | grep -v '\-plain\.jar' | head -n 1)
export JAR_PATH = $(call get_jar)

# ============================================================================
# Common Shell Functions (for use in recipes)
# ============================================================================

# Require a CLI tool; exits with contextual error when missing.
# Usage: @$(call require_cmd,ast-grep,brew install ast-grep)
define require_cmd
command -v $(1) >/dev/null 2>&1 || { echo "$(RED)Error: '$(1)' not found. Install with: $(2)$(NC)" >&2; exit 1; }
endef

# Load .env file if present
# Usage: $(call load_env)
define load_env
if [ -f .env ]; then source scripts/lib/env_loader.sh; preserve_process_env_then_source_file .env; fi
endef

# Validate the shared-gateway chat credential.
# Usage: $(call validate_api_keys)
define validate_api_keys
if [ -z "$$OPENAI_API_KEY" ]; then \
  echo "ERROR: Set OPENAI_API_KEY for the shared LLM gateway. See docs/configuration.md." >&2; \
  exit 1; \
fi
endef

# Get validated server port within allowed range
# Usage: SERVER_PORT=$$($(call get_server_port))
define get_server_port
port=$${PORT:-$${port:-$(DEFAULT_PORT)}}; \
if [ $$port -lt $(MIN_PORT) ] || [ $$port -gt $(MAX_PORT) ]; then \
  echo "Requested port $$port is outside allowed range $(MIN_PORT)-$(MAX_PORT); using $(DEFAULT_PORT)" >&2; \
  port=$(DEFAULT_PORT); \
fi; \
echo $$port
endef

# Require a specific TCP port to be unoccupied before starting an owned service.
# Usage: $(call require_port_available,8085)
define require_port_available
if ! command -v lsof >/dev/null 2>&1; then \
  echo "ERROR: Cannot verify availability of port $(1): lsof is unavailable." >&2; \
  exit 1; \
fi; \
PORT_INSPECTION_REPORT=$$(lsof -nP -tiTCP:$(1) -sTCP:LISTEN 2>&1); \
PORT_INSPECTION_STATUS=$$?; \
if [ "$$PORT_INSPECTION_STATUS" -ne 0 ] && [ -n "$$PORT_INSPECTION_REPORT" ]; then \
  echo "ERROR: Unable to verify availability of port $(1): $$PORT_INSPECTION_REPORT" >&2; \
  exit 1; \
fi; \
if [ "$$PORT_INSPECTION_STATUS" -gt 1 ]; then \
  echo "ERROR: Unable to verify availability of port $(1); lsof exited with status $$PORT_INSPECTION_STATUS." >&2; \
  exit 1; \
fi; \
if [ -n "$$PORT_INSPECTION_REPORT" ]; then \
  echo "ERROR: Port $(1) is already in use by process ID(s): $$PORT_INSPECTION_REPORT. Stop the owning process and retry." >&2; \
  exit 1; \
fi
endef

# Build Spring application arguments.
# Usage: $(call build_app_args,PORT)
define build_app_args
	APP_ARGS=(--server.port=$(1))
endef

# Allows local developer commands to bootstrap only a loopback Qdrant instance.
# Usage: $(call append_local_qdrant_bootstrap_argument)
define append_local_qdrant_bootstrap_argument
	case "$${QDRANT_HOST:-localhost}" in \
	  localhost|127.0.0.1) APP_ARGS+=(--app.qdrant.ensure-collections=true) ;; \
	esac
endef

# ============================================================================
# JVM Configuration
# ============================================================================
# Conservative JVM memory limits to prevent OS-level SIGKILL (exit 137) under memory pressure
# --sun-misc-unsafe-memory-access=allow suppresses gRPC/Netty Unsafe warnings
# See: https://netty.io/wiki/java-24-and-sun.misc.unsafe.html
DEFAULT_JAVA_OPTS := -XX:+IgnoreUnrecognizedVMOptions \
	-Xms512m -Xmx1g \
	-XX:+UseG1GC \
	-XX:MaxRAMPercentage=70 \
	-XX:MaxDirectMemorySize=256m \
	-Dio.netty.handler.ssl.noOpenSsl=true \
	-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true \
	--sun-misc-unsafe-memory-access=allow

# Gradle bootRun JVM args (for development)
GRADLE_JVM_ARGS := -Xmx2g \
	-Dspring.devtools.restart.enabled=true \
	-Djava.net.preferIPv4Stack=true \
	-Dio.netty.handler.ssl.noOpenSsl=true \
	-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true
