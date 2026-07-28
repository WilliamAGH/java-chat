# Configuration

Java Chat reads credentials from environment variables. Scripts load `.env` when present, but `.env` is optional when values are exported in the invoking shell. Non-secret provider endpoints, models, and defaults are owned by the application property files.

For defaults, see `src/main/resources/application.properties`.

## Ports

- `PORT` (default `8085`) is restricted to `8085–8090` (see `server.port` and the app’s port initializer).
- CLI ingestion profiles (`cli`, `cli-github`) run in non-web mode and do not bind an HTTP port.

## LLM gateway (streaming chat)

Streaming uses the OpenAI Java SDK (`OpenAIStreamingService`) against the one explicitly
shared OpenAI-compatible gateway. Credential, rate-limit, and upstream failures are
surfaced; Java Chat has no alternate chat provider.

Common variables:

- `OPENAI_API_KEY` (shared-gateway auth)
- `OPENAI_BASE_URL` (`https://api.llm-gateway.iocloudhost.net/v1` for Java Chat deployments)
- `OPENAI_MODEL` (required value `gpt-5.4`; any other value fails startup)
- `OPENAI_STREAMING_REQUEST_TIMEOUT_SECONDS` (default `90`; bounds the complete SDK call while Java Chat enforces a 20-second visible-output deadline)

Non-secret generation policy is owned by `app.llm` in `application.properties`: `temperature`, `reasoning-effort`, `completion-output-token-budget`, `enrichment-output-token-budget`, `reranker-temperature`, `reranker-output-token-budget`, and `configured-provider-backoff-seconds`. Invalid values fail startup. Supported reasoning-effort subsets vary by model, so check the [OpenAI model page](https://developers.openai.com/api/docs/models) for the configured model.

### Shared LLM gateway

Configure chat through the shared gateway with:

```dotenv
OPENAI_API_KEY=lgw-...
OPENAI_BASE_URL=https://api.llm-gateway.iocloudhost.net/v1
OPENAI_MODEL=gpt-5.4
```

Java Chat sends chat and embedding requests to the same configured gateway URL and credential.
`OPENAI_MODEL` and `LLM_GATEWAY_TIER` affect chat only. Embeddings always use the
application-owned `app.embeddings.model` and intent-specific tier described below.

### Gateway notes

- The configured gateway base URL must end in `/v1`; the SDK appends `/embeddings` for embedding calls.
- A missing gateway URL fails startup; Java Chat never falls back to `api.openai.com`.

### Rate limiting

- A gateway `429` opens the provider-declared retry window and prevents new request admission until it expires.

## Embeddings

Gateway embeddings are configured by `EmbeddingConfig`.
Runtime fallback and error swallowing are disallowed per [AGENTS.md](../AGENTS.md) ([RC1a], [RC1c], [RC1e]).
Using fallback embeddings or suppressing provider failures is an explicit [AGENTS.md](../AGENTS.md) violation.
If a provider is unreachable or returns an HTTP error, the failure is surfaced immediately
and ingestion/retrieval stops so invalid vectors are never cached.

The normal runtime path uses `OPENAI_BASE_URL`, `OPENAI_API_KEY`, and `app.embeddings.model=qwen/qwen3-embedding-4b`. The model's native 2,560-dimensional output is required exactly. Retrieval requests send `X-Tier: production-z`; ingestion, probes, and warmups send `X-Tier: batch`. `OPENAI_MODEL` is never used for embeddings.

The local embedding server remains an explicit development-only mode enabled by
`APP_LOCAL_EMBEDDING_ENABLED=true`. There is no remote-provider fallback, separate remote
credential, dimension remapping, padding, or alternate 8B route.

Reprocessing note:

- Every embedding generation uses new collection names and new ingestion-state roots. Preserve prior-generation collections and state for rollback; never alter an existing collection's vector size.

Common variables:

- `APP_LOCAL_EMBEDDING_ENABLED` (`true|false`)
- `LOCAL_EMBEDDING_SERVER_URL` (default `http://127.0.0.1:8088`)
- `APP_LOCAL_EMBEDDING_MODEL` (default `qwen/qwen3-embedding-4b`)
- `APP_LOCAL_EMBEDDING_DIMENSIONS` (default `2560`)
- `APP_LOCAL_EMBEDDING_BATCH_SIZE` (default `32`)
- `app.embeddings.model` (fixed deployment value `qwen/qwen3-embedding-4b`)
- `app.embeddings.dimensions` (fixed deployment value `2560`)

Environment precedence is universal across Make targets and ingestion scripts:
1) CLI arguments (script flags / Make variables passed inline)
2) Variables exported in the invoking shell/command
3) `.env`
4) Application/script defaults

### Embedding preflight checks

Before indexing, ingestion scripts verify the model alias through `/v1/models`, then issue `X-Tier: batch` embedding probes with batches of 1 and 4. Each response must preserve input count/order and contain exactly 2,560 numeric values per vector. Any HTTP, model, shape, null-value, or dimension failure stops ingestion.

## Qdrant

The app uses Qdrant directly via the gRPC client with four hybrid collections (dense + sparse vectors).
See [pipeline-commands.md](pipeline-commands.md#hybrid-qdrant-setup) for the collection layout and retrieval flow.

### Connection variables

- `QDRANT_HOST` (default `localhost`) — hostname only, no `http://` prefix
- `QDRANT_PORT` (gRPC; default `6334`, local compose maps to `8086`)
- `QDRANT_REST_PORT` (REST; local compose maps to `8087`, used by scripts for health checks)
- `QDRANT_API_KEY` (required for Qdrant Cloud; empty for local)
- `QDRANT_SSL` (`true` for cloud, `false` for local)

### Hybrid collection variables

- `QDRANT_COLLECTION_BOOKS` (default `java-chat-qwen3-embedding-4b-2560-books`)
- `QDRANT_COLLECTION_DOCS` (default `java-chat-qwen3-embedding-4b-2560-docs`)
- `QDRANT_COLLECTION_ARTICLES` (default `java-chat-qwen3-embedding-4b-2560-articles`)
- `QDRANT_COLLECTION_PDFS` (default `java-chat-qwen3-embedding-4b-2560-pdfs`)
- `QDRANT_DENSE_VECTOR_NAME` (default `dense`) — named vector for dense embeddings
- `QDRANT_SPARSE_VECTOR_NAME` (default `bm25`) — named vector for BM25 sparse tokens
- `HYBRID_PREFETCH_LIMIT` (default `20`) — per-stage prefetch limit for RRF fusion queries
- `HYBRID_RRF_K` (default `60`) — reciprocal-rank-fusion k parameter used by Qdrant query fusion
- `HYBRID_QUERY_TIMEOUT` (default `10s`) — timeout for hybrid search queries
- `APP_QDRANT_ENSURE_PAYLOAD_INDEXES` (default `true`) — create payload indexes on startup

### Local Qdrant

```bash
make compose-up
```

### Qdrant troubleshooting

- `QDRANT_HOST` must be a hostname only (no `http://` or `https://` prefix).
- Local compose maps Qdrant to allowed ports: gRPC `8086`, REST `8087` (`infra/docker-compose-qdrant.yml`).
- Some scripts use REST for health checks; set `QDRANT_REST_PORT=8087` when using local compose.
- Local Compose runs Qdrant 1.18.3 in project `java-chat-qwen3-embedding-4b-2560` with a fresh generation-specific volume. It never opens the old 1.16.2 volume.
- On startup, `QdrantIndexInitializer` requires named `dense` 2,560/Cosine and `bm25`/IDF vectors, on-disk payloads, and required payload indexes. A mismatch fails readiness.

## GitHub repository ingestion

GitHub source ingestion uses `scripts/process_github_repo.sh` and supports local-path mode, URL mode, and `--sync-existing` for the exact shared prefix `github-qwen3-embedding-4b-2560-`.

Common variables:

- `REPO_PATH` — local repository clone path for one-off ingestion
- `REPO_URL` — GitHub URL for URL ingestion mode (`https://github.com/owner/repository`)
- `REPO_CACHE_DIR` — local clone cache root for URL mode (default `data/repos/github`)
- `REPO_CACHE_PATH` — exact local clone path for a specific URL ingestion run (single repo mode)
- `SYNC_EXISTING` — set to `1` to sync collections under the shared generation prefix
- `QDRANT_COLLECTION_DOCS` — the shared documentation collection is the only schema source for a new GitHub collection

## RAG tuning

Common variables (see `app.rag.*` defaults in `application.properties`):

- `RAG_CHUNK_MAX_TOKENS`
- `RAG_CHUNK_OVERLAP_TOKENS`
- `RAG_TOP_K`
- `RAG_RETURN_K`
- `RAG_CITATIONS_K`
- `RAG_RERANKER_TIMEOUT` (default `8s`) — positive timeout budget for LLM reranking calls; must be less than `20s`
