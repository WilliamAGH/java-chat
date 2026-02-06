# Common Makefile variables and functions for java-chat
# Include this in the main Makefile with: include config/make/common.mk

# ============================================================================
# Shell and Build Tools
# ============================================================================
SHELL := /bin/bash
GRADLEW := ./gradlew

# ============================================================================
# Application Configuration
# ============================================================================
APP_NAME := java-chat
QDRANT_COMPOSE_FILE := docker-compose-qdrant.yml

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

# Load .env file if present
# Usage: $(call load_env)
define load_env
if [ -f .env ]; then set -a; source .env; set +a; fi
endef

# Validate API keys - exits with error if neither is set
# Usage: $(call validate_api_keys)
define validate_api_keys
if [ -z "$$GITHUB_TOKEN" ] && [ -z "$$OPENAI_API_KEY" ]; then \
  echo "ERROR: Set GITHUB_TOKEN or OPENAI_API_KEY. See README and docs/configuration.md." >&2; \
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

# Free a specific port by killing any process using it
# Usage: $(call free_port,8085)
define free_port
PIDS=$$(lsof -ti tcp:$(1) 2>/dev/null || true); \
if [ -n "$$PIDS" ]; then \
  echo "Killing process(es) on port $(1): $$PIDS" >&2; \
  kill -9 $$PIDS 2>/dev/null || true; \
  sleep 1; \
fi
endef

# Build Spring app arguments based on available API keys
# Sets APP_ARGS array with appropriate --spring.ai.* arguments
# Usage: $(call build_app_args,PORT)
define build_app_args
	APP_ARGS=(--server.port=$(1)); \
	if [ -n "$$GITHUB_TOKEN" ]; then \
	  APP_ARGS+=( \
	    --spring.ai.openai.base-url="$${GITHUB_MODELS_BASE_URL:-https://models.github.ai/inference}" \
	    --spring.ai.openai.chat.options.model="$${GITHUB_MODELS_CHAT_MODEL:-gpt-5}" \
	    --spring.ai.openai.embedding.options.model="$${GITHUB_MODELS_EMBED_MODEL:-text-embedding-3-small}" \
	  ); \
	fi
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
