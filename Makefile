# Java Chat Makefile
# See config/make/common.mk for shared variables and functions

include config/make/common.mk

.PHONY: all help clean build test lint lint-ast format hooks run dev dev-backend compose-up compose-down compose-logs compose-ps health ingest citations fetch-all fetch-force fetch-quick process-all process-upload process-local process-doc-sets full-pipeline frontend-install frontend-build

all: help ## Default target (alias)

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_.-]+:.*?## ' Makefile | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

clean: ## Clean build outputs
	$(GRADLEW) clean

build: frontend-build ## Build the project (skip tests)
	$(GRADLEW) build -x test

test: ## Run tests (loads .env if present)
	@$(call load_env); \
	  $(GRADLEW) test

lint: lint-ast ## Run static analysis (Java: SpotBugs + PMD + ast-grep, Frontend: svelte-check)
	$(GRADLEW) spotbugsMain pmdMain
	cd frontend && npm run check

lint-ast: ## Run ast-grep rules for Java naming and type safety
	@command -v ast-grep >/dev/null 2>&1 || { echo "$(RED)Error: 'ast-grep' not found. Install: brew install ast-grep$(NC)" >&2; exit 1; }
	@echo "$(CYAN)Running ast-grep rules...$(NC)"
	@ast-grep scan -c config/sgconfig.yml src/main/java/

format: ## Apply Java formatting (Palantir via Spotless)
	$(GRADLEW) spotlessApply

hooks: ## Install git hooks via prek
	@command -v prek >/dev/null 2>&1 || { echo "Error: 'prek' not found. Install it first: https://prek.j178.dev/" >&2; exit 1; }
	prek install --install-hooks -c config/prek.toml

run: build ## Run the packaged jar (loads .env if present)
	@$(call load_env); \
	  $(call validate_api_keys); \
	  SERVER_PORT=$$($(call get_server_port)); \
	  echo "Ensuring port $$SERVER_PORT is free..." >&2; \
	  $(call free_port,$$SERVER_PORT); \
	  echo "Binding app to port $$SERVER_PORT" >&2; \
	  $(call build_app_args,$$SERVER_PORT); \
	  JAVA_OPTS="$${JAVA_OPTS:- $(DEFAULT_JAVA_OPTS)}"; \
	  java $$JAVA_OPTS -Djava.net.preferIPv4Stack=true -jar $(call get_jar) "$${APP_ARGS[@]}" & disown

dev: frontend-build ## Start both Spring Boot and Vite dev servers (Ctrl+C stops both)
	@echo "$(YELLOW)Starting full-stack development environment...$(NC)"
	@echo "$(CYAN)Frontend: http://localhost:5173/$(NC)"
	@echo "$(YELLOW)Backend API: http://localhost:$(DEFAULT_PORT)/api/$(NC)"
	@echo ""
	@$(call load_env); \
	  $(call validate_api_keys); \
	  trap 'kill 0' INT TERM; \
	  (cd frontend && npm run dev 2>&1 | awk '{print "\033[36m[vite]\033[0m " $$0; fflush()}') & \
	  ($(call load_env); \
	   $(call build_app_args,$(DEFAULT_PORT)); \
	   SPRING_PROFILES_ACTIVE=dev $(GRADLEW) bootRun \
	   --args="$${APP_ARGS[*]}" \
	   -Dorg.gradle.jvmargs="$(GRADLE_JVM_ARGS)" 2>&1 \
	   | awk '{print "\033[33m[java]\033[0m " $$0; fflush()}') & \
	  wait

dev-backend: ## Run only Spring Boot backend (dev profile)
	@$(call load_env); \
	  $(call validate_api_keys); \
	  SERVER_PORT=$$($(call get_server_port)); \
	  LIVERELOAD_PORT=$${LIVERELOAD_PORT:-$(DEFAULT_LIVERELOAD_PORT)}; \
	  echo "Ensuring ports $$SERVER_PORT and $$LIVERELOAD_PORT are free..." >&2; \
	  $(call free_port,$$SERVER_PORT); \
	  $(call free_port,$$LIVERELOAD_PORT); \
	  echo "Binding app (dev) to port $$SERVER_PORT, LiveReload on $$LIVERELOAD_PORT" >&2; \
	  $(call build_app_args,$$SERVER_PORT); \
	  APP_ARGS+=(--spring.devtools.livereload.port=$$LIVERELOAD_PORT); \
	  SPRING_PROFILES_ACTIVE=dev $(GRADLEW) bootRun \
	    --args="$${APP_ARGS[*]}" \
	    -Dorg.gradle.jvmargs="$(GRADLE_JVM_ARGS)"

frontend-install: ## Install frontend dependencies
	cd frontend && npm install

frontend-build: frontend-install ## Build frontend for production
	cd frontend && npm run build

compose-up: ## Start local Qdrant via Docker Compose (detached)
	@for p in 8086 8087; do \
	  $(call free_port,$$p); \
	done; \
	docker compose -f $(QDRANT_COMPOSE_FILE) up -d

compose-down: ## Stop Docker Compose services
	docker compose -f $(QDRANT_COMPOSE_FILE) down

compose-logs: ## Tail logs for Docker Compose services
	docker compose -f $(QDRANT_COMPOSE_FILE) logs -f

compose-ps: ## List Docker Compose services
	docker compose -f $(QDRANT_COMPOSE_FILE) ps

health: ## Check app health endpoint
	curl -sS http://localhost:$${PORT:-$(DEFAULT_PORT)}/actuator/health

ingest: ## Ingest first 1000 docs (adjust maxPages=)
	curl -sS -X POST "http://localhost:$${PORT:-$(DEFAULT_PORT)}/api/ingest?maxPages=1000"

citations: ## Try a citations lookup
	curl -sS "http://localhost:$${PORT:-$(DEFAULT_PORT)}/api/chat/citations?q=records"

fetch-all: ## Fetch all documentation with deduplication
	./scripts/fetch_all_docs.sh

fetch-force: ## Fetch all documentation (full refresh; forces refetch)
	./scripts/fetch_all_docs.sh --force

fetch-quick: ## Fetch documentation including quick landing mirrors
	./scripts/fetch_all_docs.sh --include-quick

process-all: ## Process and upload all docs to Qdrant with deduplication
	./scripts/process_all_to_qdrant.sh

process-upload: ## Process docs and upload to Qdrant
	./scripts/process_all_to_qdrant.sh --upload

process-local: ## Process docs and cache embeddings locally (no Qdrant)
	./scripts/process_all_to_qdrant.sh --local-only

process-doc-sets: ## Process and upload selected doc sets (set DOCS_SETS=...)
	@if [ -z "$$DOCS_SETS" ]; then echo "Set DOCS_SETS=comma,separated,docsets"; exit 1; fi
	./scripts/process_all_to_qdrant.sh --upload --doc-sets="$$DOCS_SETS"

full-pipeline: ## Complete pipeline: fetch docs, process, and upload to Qdrant
	@echo "Starting full documentation pipeline..."
	@echo "Step 1: Fetching documentation..."
	@./scripts/fetch_all_docs.sh
	@echo ""
	@echo "Step 2: Processing and uploading to Qdrant..."
	@./scripts/process_all_to_qdrant.sh
	@echo ""
	@echo "Full pipeline complete!"
