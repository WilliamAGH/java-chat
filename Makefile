SHELL := /bin/bash

APP_NAME := java-chat
GRADLEW := ./gradlew
QDRANT_COMPOSE_FILE := docker-compose-qdrant.yml

# Terminal colors for output prefixing
RED    := \033[0;31m
GREEN  := \033[0;32m
YELLOW := \033[0;33m
CYAN   := \033[0;36m
NC     := \033[0m

# Compute JAR lazily so it's resolved after the build runs
# Use a function instead of variable to evaluate at runtime
# Exclude -plain.jar which is the non-bootable archive
get_jar = $(shell ls -t build/libs/*.jar 2>/dev/null | grep -v '\-plain\.jar' | head -n 1)

# Export color codes and paths for use in scripts
export RED GREEN YELLOW CYAN NC
export PROJECT_ROOT := $(shell pwd)
export JAR_PATH = $(call get_jar)

.PHONY: help clean build test lint format run dev dev-backend compose-up compose-down compose-logs compose-ps health ingest citations fetch-all process-all full-pipeline frontend-install frontend-build

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_.-]+:.*?## ' Makefile | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

clean: ## Clean build outputs
	$(GRADLEW) clean

build: frontend-build ## Build the project (skip tests)
	$(GRADLEW) build -x test

test: ## Run tests (loads .env if present)
	@if [ -f .env ]; then set -a; source .env; set +a; fi; \
	  $(GRADLEW) test

lint: ## Run static analysis (Java: SpotBugs + PMD, Frontend: svelte-check)
	$(GRADLEW) spotbugsMain pmdMain
	cd frontend && npm run check

format: ## Apply Java formatting (Palantir via Spotless)
	$(GRADLEW) spotlessApply

run: build ## Run the packaged jar (loads .env if present)
	@if [ -f .env ]; then set -a; source .env; set +a; fi; \
	  if [ -z "$$GITHUB_TOKEN" ] && [ -z "$$OPENAI_API_KEY" ]; then \
	    echo "ERROR: Set GITHUB_TOKEN or OPENAI_API_KEY. See README and docs/configuration.md." >&2; \
	    exit 1; \
	  fi; \
	  SERVER_PORT=$${PORT:-$${port:-8085}}; \
	  if [ $$SERVER_PORT -lt 8085 ] || [ $$SERVER_PORT -gt 8090 ]; then echo "Requested port $$SERVER_PORT is outside allowed range 8085-8090; using 8085" >&2; SERVER_PORT=8085; fi; \
	  echo "Ensuring port $$SERVER_PORT is free..." >&2; \
	  PIDS=$$(lsof -ti tcp:$$SERVER_PORT 2>/dev/null || true); echo "Found PIDs on port $$SERVER_PORT: '$$PIDS'" >&2; if [ -n "$$PIDS" ]; then echo "Killing process(es) on port $$SERVER_PORT: $$PIDS" >&2; kill -9 $$PIDS 2>/dev/null || true; sleep 2; fi; \
	  echo "Binding app to port $$SERVER_PORT" >&2; \
	  APP_ARGS=(--server.port=$$SERVER_PORT); \
	  if [ -n "$$GITHUB_TOKEN" ]; then \
	    APP_ARGS+=( \
	      --spring.ai.openai.api-key="$$GITHUB_TOKEN" \
	      --spring.ai.openai.base-url="$${GITHUB_MODELS_BASE_URL:-https://models.github.ai/inference}" \
	      --spring.ai.openai.chat.options.model="$${GITHUB_MODELS_CHAT_MODEL:-gpt-5}" \
	      --spring.ai.openai.embedding.options.model="$${GITHUB_MODELS_EMBED_MODEL:-text-embedding-3-small}" \
	    ); \
	  fi; \
	  # Add conservative JVM memory limits to prevent OS-level SIGKILL (exit 137) under memory pressure
	  # Tuned for local dev: override via JAVA_OPTS env if needed
	  JAVA_OPTS="$${JAVA_OPTS:- -XX:+IgnoreUnrecognizedVMOptions -Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxRAMPercentage=70 -XX:MaxDirectMemorySize=256m -Dio.netty.handler.ssl.noOpenSsl=true -Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true}"; \
	  java $$JAVA_OPTS -Djava.net.preferIPv4Stack=true -jar $(call get_jar) "$${APP_ARGS[@]}" & disown

dev: frontend-build ## Start both Spring Boot and Vite dev servers (Ctrl+C stops both)
	@echo "$(YELLOW)Starting full-stack development environment...$(NC)"
	@echo "$(CYAN)Frontend: http://localhost:5173/$(NC)"
	@echo "$(YELLOW)Backend API: http://localhost:8085/api/$(NC)"
	@echo ""
	@if [ -f .env ]; then set -a; source .env; set +a; fi; \
	  if [ -z "$$GITHUB_TOKEN" ] && [ -z "$$OPENAI_API_KEY" ]; then \
	    echo "ERROR: Set GITHUB_TOKEN or OPENAI_API_KEY. See README and docs/configuration.md." >&2; \
	    exit 1; \
	  fi; \
	  trap 'kill 0' INT TERM; \
	  (cd frontend && npm run dev 2>&1 | awk '{print "\033[36m[vite]\033[0m " $$0; fflush()}') & \
	  (if [ -f .env ]; then set -a; source .env; set +a; fi; \
	   APP_ARGS=(--server.port=8085); \
	   if [ -n "$$GITHUB_TOKEN" ]; then \
	     APP_ARGS+=( \
	       --spring.ai.openai.api-key="$$GITHUB_TOKEN" \
	       --spring.ai.openai.base-url="$${GITHUB_MODELS_BASE_URL:-https://models.github.ai/inference}" \
	       --spring.ai.openai.chat.options.model="$${GITHUB_MODELS_CHAT_MODEL:-gpt-5}" \
	       --spring.ai.openai.embedding.options.model="$${GITHUB_MODELS_EMBED_MODEL:-text-embedding-3-small}" \
	     ); \
	   elif [ -n "$$OPENAI_API_KEY" ]; then \
	     APP_ARGS+=( \
	       --spring.ai.openai.api-key="$$OPENAI_API_KEY" \
	     ); \
	   fi; \
	   SPRING_PROFILES_ACTIVE=dev $(GRADLEW) bootRun \
	   --args="$${APP_ARGS[*]}" \
	   -Dorg.gradle.jvmargs="-Xmx2g -Dspring.devtools.restart.enabled=true -Djava.net.preferIPv4Stack=true -Dio.netty.handler.ssl.noOpenSsl=true -Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true" 2>&1 \
	   | awk '{print "\033[33m[java]\033[0m " $$0; fflush()}') & \
	  wait

dev-backend: ## Run only Spring Boot backend (dev profile)
	@if [ -f .env ]; then set -a; source .env; set +a; fi; \
	  if [ -z "$$GITHUB_TOKEN" ] && [ -z "$$OPENAI_API_KEY" ]; then \
	    echo "ERROR: Set GITHUB_TOKEN or OPENAI_API_KEY. See README and docs/configuration.md." >&2; \
	    exit 1; \
	  fi; \
	  SERVER_PORT=$${PORT:-$${port:-8085}}; \
	  LIVERELOAD_PORT=$${LIVERELOAD_PORT:-35730}; \
	  if [ $$SERVER_PORT -lt 8085 ] || [ $$SERVER_PORT -gt 8090 ]; then echo "Requested port $$SERVER_PORT is outside allowed range 8085-8090; using 8085" >&2; SERVER_PORT=8085; fi; \
	  echo "Ensuring ports $$SERVER_PORT and $$LIVERELOAD_PORT are free..." >&2; \
	  for port in $$SERVER_PORT $$LIVERELOAD_PORT; do \
	    PIDS=$$(lsof -ti tcp:$$port 2>/dev/null || true); \
	    if [ -n "$$PIDS" ]; then echo "Killing process(es) on port $$port: $$PIDS" >&2; kill -9 $$PIDS 2>/dev/null || true; sleep 1; fi; \
	  done; \
	  echo "Binding app (dev) to port $$SERVER_PORT, LiveReload on $$LIVERELOAD_PORT" >&2; \
	  APP_ARGS=(--server.port=$$SERVER_PORT --spring.devtools.livereload.port=$$LIVERELOAD_PORT); \
	  if [ -n "$$GITHUB_TOKEN" ]; then \
	    APP_ARGS+=( \
	      --spring.ai.openai.api-key="$$GITHUB_TOKEN" \
	      --spring.ai.openai.base-url="$${GITHUB_MODELS_BASE_URL:-https://models.github.ai/inference}" \
	      --spring.ai.openai.chat.options.model="$${GITHUB_MODELS_CHAT_MODEL:-gpt-5}" \
	      --spring.ai.openai.embedding.options.model="$${GITHUB_MODELS_EMBED_MODEL:-text-embedding-3-small}" \
	    ); \
	  elif [ -n "$$OPENAI_API_KEY" ]; then \
	    APP_ARGS+=( \
	      --spring.ai.openai.api-key="$$OPENAI_API_KEY" \
	    ); \
	  fi; \
	  SPRING_PROFILES_ACTIVE=dev $(GRADLEW) bootRun \
	    --args="$${APP_ARGS[*]}" \
	    -Dorg.gradle.jvmargs="-Xmx2g -Dspring.devtools.restart.enabled=true -Djava.net.preferIPv4Stack=true -Dio.netty.handler.ssl.noOpenSsl=true -Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true"

frontend-install: ## Install frontend dependencies
	cd frontend && npm install

frontend-build: frontend-install ## Build frontend for production
	cd frontend && npm run build

compose-up: ## Start local Qdrant via Docker Compose (detached)
	@for p in 8086 8087; do \
	  PIDS=$$(lsof -ti tcp:$$p || true); \
	  if [ -n "$$PIDS" ]; then echo "Freeing port $$p by killing: $$PIDS" >&2; kill -9 $$PIDS || true; sleep 1; fi; \
	done; \
	docker compose -f $(QDRANT_COMPOSE_FILE) up -d

compose-down: ## Stop Docker Compose services
	docker compose -f $(QDRANT_COMPOSE_FILE) down

compose-logs: ## Tail logs for Docker Compose services
	docker compose -f $(QDRANT_COMPOSE_FILE) logs -f

compose-ps: ## List Docker Compose services
	docker compose -f $(QDRANT_COMPOSE_FILE) ps

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
