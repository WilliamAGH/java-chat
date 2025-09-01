# Java Chat (Spring Boot, Java 21)

A modern, streaming RAG chat for Java learners, grounded in Java 24 documentation with precise citations. Backend-only (Spring WebFlux + Spring AI + Qdrant). Uses GitHub Models via OpenAI-compatible API.

## Quick start

```bash
# 1) Set env vars (example - use your real values)
# Option A: Create a .env file in repo root:
# GITHUB_TOKEN=ghp_xxx
# GITHUB_MODELS_BASE_URL=https://models.github.ai/inference
# GITHUB_MODELS_CHAT_MODEL=openai/gpt-5-mini
# GITHUB_MODELS_EMBED_MODEL=openai/text-embedding-3-large
# QDRANT_HOST=abc-123-xyz.us-east1-0.aws.cloud.qdrant.io
# QDRANT_PORT=6334
# QDRANT_API_KEY=YOUR_QDRANT_API_KEY
# QDRANT_SSL=true
# QDRANT_COLLECTION=java24-docs

# Option B: export in shell
export GITHUB_TOKEN=ghp_xxx
export GITHUB_MODELS_BASE_URL=https://models.github.ai/inference
export GITHUB_MODELS_CHAT_MODEL=openai/gpt-5-mini
export GITHUB_MODELS_EMBED_MODEL=openai/text-embedding-3-large
export QDRANT_HOST=YOUR_QDRANT_ENDPOINT # e.g. abc-123-xyz.us-east1-0.aws.cloud.qdrant.io
export QDRANT_PORT=6334
export QDRANT_API_KEY=YOUR_QDRANT_API_KEY
export QDRANT_SSL=true
export QDRANT_COLLECTION=java24-docs

# 2) Build
./mvnw -DskipTests package

# 3) Run (Makefile auto-loads .env if present and passes GitHub Models props)
make run
```

Health check: GET http://localhost:8080/actuator/health

## Makefile (recommended)

Common workflows are scripted via `Makefile`:

```bash
# Discover commands
make help

# Build / Test
make build
make test

# Run packaged jar
make run

# Live dev (Spring DevTools hot reload)
make dev

# Local Qdrant via Docker Compose (optional)
make compose-up    # start
make compose-logs  # tail logs
make compose-ps    # list services
make compose-down  # stop

# Convenience API helpers
make health
make ingest        # ingest first 1000 docs
make citations     # sample citations query
```

## Configuration

All config is env-driven. See `src/main/resources/application.properties` for defaults. Key vars:
- `GITHUB_TOKEN`: Personal Access Token with models:read.
- `GITHUB_MODELS_BASE_URL`: `https://models.github.ai/inference`.
- `GITHUB_MODELS_CHAT_MODEL`: default `openai/gpt-5-mini` (set per availability; override if needed).
- `GITHUB_MODELS_EMBED_MODEL`: e.g. `openai/text-embedding-3-large`.
- `QDRANT_HOST`, `QDRANT_PORT`, `QDRANT_API_KEY`, `QDRANT_SSL`, `QDRANT_COLLECTION`.
  - Use host only (no scheme). Example: `abc-123.us-west-1-0.aws.cloud.qdrant.io`
  - Port should be `6334` for gRPC.
- `DOCS_ROOT_URL`: default `https://docs.oracle.com/en/java/javase/24/`.
 - `DOCS_JDK_VERSION`: default `24`.
 - `DOCS_SNAPSHOT_DIR`: default `data/snapshots` (raw HTML)
 - `DOCS_PARSED_DIR`: default `data/parsed` (chunk text)
 - `DOCS_INDEX_DIR`: default `data/index` (ingest hash markers)

## Ingest Java 24 docs

This fetches pages under the root URL, chunks, embeds, and upserts into Qdrant. By design, it does not respect robots.txt (public docs only). Rate-limit externally if needed.

```bash
# Ingest first 1,000 pages
curl -X POST "http://localhost:8080/api/ingest?maxPages=1000"
```

Dedup & re-ingest:
- Each chunk stores metadata: `url`, `title`, `chunkIndex`, `package`, `hash`.
- Additions are idempotent by local hash index in `DOCS_INDEX_DIR`. If a chunk hash is new, we upsert to Qdrant and persist the parsed text.
- Raw HTML snapshots are saved under `DOCS_SNAPSHOT_DIR`.
- TODO: Offload snapshots and parsed chunks to DigitalOcean Spaces (S3-compatible) via AWS SDK; keep local for dev.

## Chat API (streaming)

- POST `/api/chat/stream` (SSE)
  - Body: `{ "sessionId": "s1", "latest": "How do I use records?" }`
  - Streams text tokens; on completion, stores the assistant response in session memory.

- GET `/api/chat/citations?q=your+query`
  - Returns top citations (URL, title, snippet) for the query.

- GET `/api/chat/export/last?sessionId=s1`
  - Returns the last assistant message (markdown).

- GET `/api/chat/export/session?sessionId=s1`
  - Returns the full session conversation as markdown.

## Retrieval & quality

- Chunking: ~900 tokens with 150 overlap (GPT-5-compatible segmentation).
- Vector search: Qdrant similarity. Next steps: enable hybrid (BM25 + vector) and MMR diversity.
- Re-ranker: planned BGE reranker (DJL) or LLM rerank for top-k. Citations pinned to top-3 by score.

## Citations & learning UX

Responses are grounded with citations and “background tooltips”:
- Citation metadata: `package/module`, `JDK version`, `resource/framework + version`, `URL`, `title`.
- Background: tooltips with bigger-picture context, hints, and reminders to aid understanding.

Data structures (server):
- Citation: `{ url, title, anchor, snippet }` (see `com.williamcallahan.javachat.model.Citation`).
- TODO: `Enrichment` payload with fields: `packageName`, `jdkVersion`, `resource`, `resourceVersion`, `hints[]`, `reminders[]`, `background[]`.

UI (server-rendered static placeholder):
- Return JSON with `citations` and `enrichment`. The client should render:
  - Compact “source pills” with domain icon, title, and external-link affordance (open in new tab).
  - Hover tooltips for background context (multi-paragraph allowed, markdown-safe).
  - Clear, modern layout (Shadcn-inspired). Future: SPA frontend if needed.

## Models

- Chat: `openai/gpt-5-mini` via GitHub Models free tier (fast, good quality).
- Embeddings: `openai/text-embedding-3-large` for high recall/precision.

Notes: GitHub Models are OpenAI-compatible; your PAT enables usage. See GitHub’s docs: [Prototyping with AI models](https://docs.github.com/en/github-models/use-github-models/prototyping-with-ai-models). Also see GitHub’s blog: [Solving the inference problem for OSS with GitHub Models](https://github.blog/ai-and-ml/llms/solving-the-inference-problem-for-open-source-ai-projects-with-github-models/).

### Troubleshooting (GitHub Models)

- If startup fails with: `OpenAI API key must be set`, ensure `GITHUB_TOKEN` is exported or present in `.env` and re-run `make run`.
- Makefile passes runtime args: `--spring.ai.openai.api-key=$GITHUB_TOKEN`, base URL, and model names. You can also run directly:
  ```bash
  java -jar target/java-chat-0.0.1-SNAPSHOT.jar \
    --spring.ai.openai.api-key="$GITHUB_TOKEN" \
    --spring.ai.openai.base-url="${GITHUB_MODELS_BASE_URL:-https://models.github.ai/inference}" \
    --spring.ai.openai.chat.options.model="${GITHUB_MODELS_CHAT_MODEL:-openai/gpt-5-mini}" \
    --spring.ai.openai.embedding.options.model="${GITHUB_MODELS_EMBED_MODEL:-openai/text-embedding-3-large}"
  ```
*Reference: GitHub Models docs — [Prototype with AI models](https://docs.github.com/en/github-models/use-github-models/prototyping-with-ai-models).* 
TODO: Add backup provider if rate-limited.

## Maintenance

- Re-ingesting docs: rerun `/api/ingest?maxPages=...` after a docs update.
- Qdrant housekeeping: snapshot/backup via Qdrant Cloud; set collection to HNSW + MMR/hybrid as needed.
- Env changes: restart app to pick up new model names or hosts.
- Logs/metrics: Spring Boot Actuator endpoints enabled for health/info/metrics.
 - Observability TODO: add tracing and custom metrics (query time, tokens, hit rates).

### Troubleshooting (Qdrant Cloud)

- Error `Invalid host or port` or `Expected closing bracket for IPv6 address`:
  - Ensure `QDRANT_HOST` has no `https://` prefix; it must be the hostname only.
  - Ensure `QDRANT_PORT=6334` and `QDRANT_SSL=true`.
  - Makefile forces IPv4 (`-Djava.net.preferIPv4Stack=true`) to avoid macOS IPv6 resolver quirks.

## Roadmap

- [ ] Hybrid retrieval (BM25 + vector), MMR, and re-ranker integration.
- [ ] Enrichment payload + endpoint for tooltips/hints/reminders with package/JDK metadata.
- [ ] Content hashing + upsert-by-hash for dedup and change detection.
- [ ] Minimal SPA with modern source pills, tooltips, and copy actions.
- [ ] Persist user chats + embeddings (future, configurable).
 - [ ] Slash-commands (/search, /explain, /example) with semantic routing.
 - [ ] Per-session rate limiting.
 - [ ] DigitalOcean Spaces S3 offload for snapshots & parsed text.
 - [ ] Docker Compose app service + optional local embedding model.
## Stack details

- Spring Boot 3.5.x (WebFlux, Actuator)
- Spring AI 1.0.1 (OpenAI client, VectorStore Qdrant)
- Qdrant (HNSW vector DB); `docker-compose.yml` includes a local dev service
- JSoup (HTML parsing), JTokkit (tokenization), Fastutil (utils)

Docker Compose (Qdrant only, optional fallback when you outgrow the free Qdrant Cloud plan or for offline dev):
```bash
docker compose up -d
# Then set QDRANT_HOST=localhost QDRANT_PORT=6334
```
