SHELL := /bin/bash

APP_NAME := java-chat
MVNW := ./mvnw
# Compute JAR lazily so it's resolved after the build runs
# Use a function instead of variable to evaluate at runtime
get_jar = $(shell ls -t target/*.jar 2>/dev/null | head -n 1)

# Runtime arguments mapped from GitHub Models env vars
# - Requires GITHUB_TOKEN (PAT with models:read)
# - Base URL and model names have sensible defaults
# - CRITICAL: GitHub Models endpoint is https://models.github.ai/inference (NOT azure.com)
RUN_ARGS := \
  --spring.ai.openai.api-key="$$GITHUB_TOKEN" \
  --spring.ai.openai.base-url="$${GITHUB_MODELS_BASE_URL:-https://models.github.ai/inference}" \
  --spring.ai.openai.chat.options.model="$${GITHUB_MODELS_CHAT_MODEL:-gpt-5}" \
  --spring.ai.openai.embedding.options.model="$${GITHUB_MODELS_EMBED_MODEL:-text-embedding-3-small}"

.PHONY: help clean build test run dev compose-up compose-down compose-logs compose-ps health ingest citations fetch-all process-all full-pipeline

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_.-]+:.*?## ' Makefile | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

clean: ## Clean build outputs
	$(MVNW) clean

build: ## Build the project (skip tests)
	$(MVNW) -DskipTests package

test: ## Run tests (loads .env if present)
	@if [ -f .env ]; then set -a; source .env; set +a; fi; \
	  $(MVNW) test

run: build ## Run the packaged jar (loads .env if present)
	@if [ -f .env ]; then set -a; source .env; set +a; fi; \
	  [ -n "$$GITHUB_TOKEN" ] || (echo "ERROR: GITHUB_TOKEN is not set. See README for setup." >&2; exit 1); \
	  SERVER_PORT=$${PORT:-$${port:-8085}}; \
	  if [ $$SERVER_PORT -lt 8085 ] || [ $$SERVER_PORT -gt 8090 ]; then echo "Requested port $$SERVER_PORT is outside allowed range 8085-8090; using 8085" >&2; SERVER_PORT=8085; fi; \
	  echo "Ensuring port $$SERVER_PORT is free..." >&2; \
	  PIDS=$$(lsof -ti tcp:$$SERVER_PORT 2>/dev/null || true); echo "Found PIDs on port $$SERVER_PORT: '$$PIDS'" >&2; if [ -n "$$PIDS" ]; then echo "Killing process(es) on port $$SERVER_PORT: $$PIDS" >&2; kill -9 $$PIDS 2>/dev/null || true; sleep 2; fi; \
	  echo "Binding app to port $$SERVER_PORT" >&2; \
	  # Add conservative JVM memory limits to prevent OS-level SIGKILL (exit 137) under memory pressure
	  # Tuned for local dev: override via JAVA_OPTS env if needed
	  JAVA_OPTS="$${JAVA_OPTS:- -XX:+IgnoreUnrecognizedVMOptions -Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxRAMPercentage=70 -XX:MaxDirectMemorySize=256m}"; \
	  java $$JAVA_OPTS -Djava.net.preferIPv4Stack=true -jar $(call get_jar) --server.port=$$SERVER_PORT $(RUN_ARGS) & disown

dev: ## Live dev (DevTools hot reload) with profile=dev (loads .env if present)
	@if [ -f .env ]; then set -a; source .env; set +a; fi; \
	  [ -n "$$GITHUB_TOKEN" ] || (echo "ERROR: GITHUB_TOKEN is not set. See README for setup." >&2; exit 1); \
	  SERVER_PORT=$${PORT:-$${port:-8085}}; \
	  LIVERELOAD_PORT=$${LIVERELOAD_PORT:-35730}; \
	  if [ $$SERVER_PORT -lt 8085 ] || [ $$SERVER_PORT -gt 8090 ]; then echo "Requested port $$SERVER_PORT is outside allowed range 8085-8090; using 8085" >&2; SERVER_PORT=8085; fi; \
	  echo "Ensuring ports $$SERVER_PORT and $$LIVERELOAD_PORT are free..." >&2; \
	  for port in $$SERVER_PORT $$LIVERELOAD_PORT; do \
	    PIDS=$$(lsof -ti tcp:$$port 2>/dev/null || true); \
	    if [ -n "$$PIDS" ]; then echo "Killing process(es) on port $$port: $$PIDS" >&2; kill -9 $$PIDS 2>/dev/null || true; sleep 1; fi; \
	  done; \
	  echo "Binding app (dev) to port $$SERVER_PORT, LiveReload on $$LIVERELOAD_PORT" >&2; \
	  SPRING_PROFILES_ACTIVE=dev $(MVNW) spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx2g -Dspring.devtools.restart.enabled=true -Djava.net.preferIPv4Stack=true" -Dspring-boot.run.arguments="--server.port=$$SERVER_PORT --spring.devtools.livereload.port=$$LIVERELOAD_PORT $(RUN_ARGS)"

compose-up: ## Start local Qdrant via Docker Compose (detached)
	@for p in 8086 8087; do \
	  PIDS=$$(lsof -ti tcp:$$p || true); \
	  if [ -n "$$PIDS" ]; then echo "Freeing port $$p by killing: $$PIDS" >&2; kill -9 $$PIDS || true; sleep 1; fi; \
	done; \
	docker compose up -d

compose-down: ## Stop Docker Compose services
	docker compose down

compose-logs: ## Tail logs for Docker Compose services
	docker compose logs -f

compose-ps: ## List Docker Compose services
	docker compose ps

health: ## Check app health endpoint
	curl -sS http://localhost:$${PORT:-8085}/actuator/health

ingest: ## Ingest first 1000 docs (adjust maxPages=)
	curl -sS -X POST "http://localhost:$${PORT:-8085}/api/ingest?maxPages=1000"

citations: ## Try a citations lookup
	curl -sS "http://localhost:$${PORT:-8085}/api/chat/citations?q=records"

fetch-all: ## Fetch all documentation with deduplication
	./scripts/fetch_all_docs.sh

process-all: ## Process and upload all docs to Qdrant with deduplication
	./scripts/process_all_to_qdrant.sh

full-pipeline: ## Complete pipeline: fetch docs, process, and upload to Qdrant
	@echo "Starting full documentation pipeline..."
	@echo "Step 1: Fetching documentation..."
	@./scripts/fetch_all_docs.sh
	@echo ""
	@echo "Step 2: Processing and uploading to Qdrant..."
	@./scripts/process_all_to_qdrant.sh
	@echo ""
	@echo "✅ Full pipeline complete!"

