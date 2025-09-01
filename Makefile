SHELL := /bin/bash

APP_NAME := java-chat
MVNW := ./mvnw
JAR := $(shell ls -t target/*.jar 2>/dev/null | head -n 1)

# Runtime arguments mapped from GitHub Models env vars
# - Requires GITHUB_TOKEN (PAT with models:read)
# - Base URL and model names have sensible defaults
RUN_ARGS := \
  --spring.ai.openai.api-key="$$GITHUB_TOKEN" \
  --spring.ai.openai.base-url="$${GITHUB_MODELS_BASE_URL:-https://models.github.ai/inference}" \
  --spring.ai.openai.chat.options.model="$${GITHUB_MODELS_CHAT_MODEL:-openai/gpt-5-mini}" \
  --spring.ai.openai.embedding.options.model="$${GITHUB_MODELS_EMBED_MODEL:-openai/text-embedding-3-large}"

.PHONY: help clean build test run dev compose-up compose-down compose-logs compose-ps health ingest citations

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_.-]+:.*?## ' Makefile | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

clean: ## Clean build outputs
	$(MVNW) clean

build: ## Build the project (skip tests)
	$(MVNW) -DskipTests package

test: ## Run tests
	$(MVNW) test

run: build ## Run the packaged jar (loads .env if present)
	@if [ -f .env ]; then set -a; source .env; set +a; fi; \
	  [ -n "$$GITHUB_TOKEN" ] || (echo "ERROR: GITHUB_TOKEN is not set. See README for setup." >&2; exit 1); \
	  java -Djava.net.preferIPv4Stack=true -jar $(JAR) $(RUN_ARGS)

dev: ## Live dev (DevTools hot reload) with profile=dev (loads .env if present)
	@if [ -f .env ]; then set -a; source .env; set +a; fi; \
	  [ -n "$$GITHUB_TOKEN" ] || (echo "ERROR: GITHUB_TOKEN is not set. See README for setup." >&2; exit 1); \
	  SPRING_PROFILES_ACTIVE=dev $(MVNW) spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.devtools.restart.enabled=true -Djava.net.preferIPv4Stack=true" -Dspring-boot.run.arguments='$(RUN_ARGS)'

compose-up: ## Start local Qdrant via Docker Compose (detached)
	docker compose up -d

compose-down: ## Stop Docker Compose services
	docker compose down

compose-logs: ## Tail logs for Docker Compose services
	docker compose logs -f

compose-ps: ## List Docker Compose services
	docker compose ps

health: ## Check app health endpoint
	curl -sS http://localhost:8080/actuator/health

ingest: ## Ingest first 1000 docs (adjust maxPages=)
	curl -sS -X POST "http://localhost:8080/api/ingest?maxPages=1000"

citations: ## Try a citations lookup
	curl -sS "http://localhost:8080/api/chat/citations?q=records"


