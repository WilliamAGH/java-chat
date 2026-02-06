# Configuration

Java Chat is configured primarily via environment variables (loaded from `.env` by `Makefile` targets and most scripts).

For defaults, see `src/main/resources/application.properties`.

## Ports

- `PORT` (default `8085`) is restricted to `8085–8090` (see `server.port` and the app’s port initializer).

## LLM providers (streaming chat)

Streaming uses the OpenAI Java SDK (`OpenAIStreamingService`) and supports:

- **GitHub Models** via `GITHUB_TOKEN`
- **OpenAI** via `OPENAI_API_KEY`

If both keys are present, the service prefers OpenAI for streaming. There is no automatic cross-provider fallback; if the preferred provider fails or is rate-limited, the error is surfaced to the client rather than silently switching providers.

Common variables:

- `GITHUB_TOKEN` (GitHub Models auth)
- `GITHUB_MODELS_BASE_URL` (default `https://models.github.ai/inference/v1`)
- `GITHUB_MODELS_CHAT_MODEL` (default `gpt-5`)
- `OPENAI_API_KEY` (OpenAI auth)
- `OPENAI_BASE_URL` (default `https://api.openai.com/v1`)
- `OPENAI_MODEL` (default `gpt-5.2`)
- `OPENAI_REASONING_EFFORT` (optional, GPT‑5 family)
- `OPENAI_STREAMING_REQUEST_TIMEOUT_SECONDS` (default `600`)
- `OPENAI_STREAMING_READ_TIMEOUT_SECONDS` (default `75`)

### Provider notes

- GitHub Models uses `https://models.github.ai/inference` (the OpenAI SDK requires `/v1`, so the default is `.../inference/v1`).
- OpenAI uses `https://api.openai.com` (the OpenAI SDK requires `/v1`; the app normalizes URLs when needed).
- Avoid `azure.com`-style endpoints unless you are explicitly running an Azure OpenAI-compatible gateway; this project does not configure Azure by default.

### Rate limiting

- If you hit `429` errors on GitHub Models, either wait and retry or set `OPENAI_API_KEY` as an additional provider.

## Embeddings

Embeddings are configured with explicit provider selection (see `EmbeddingConfig`).
Runtime fallback and error swallowing are disallowed per [AGENTS.md](../AGENTS.md) ([RC1a], [RC1c], [RC1e]).
Using fallback embeddings or suppressing provider failures is an explicit [AGENTS.md](../AGENTS.md) violation.
If a provider is unreachable or returns an HTTP error, the failure is surfaced immediately
and ingestion/retrieval stops so invalid vectors are never cached.

Selection order:

1) Local embedding server when `APP_LOCAL_EMBEDDING_ENABLED=true`
2) Remote OpenAI-compatible provider when `REMOTE_EMBEDDING_SERVER_URL` and `REMOTE_EMBEDDING_API_KEY` are set
3) OpenAI embeddings when `OPENAI_API_KEY` is set

If none are configured, the application fails fast with an explicit error.

Reprocessing note:

- If you change embedding providers or suspect stale vectors, remove existing Qdrant collections and clear local dedup markers (`data/index/`) before re-ingesting so vectors are rebuilt with the new provider.

Common variables:

- `APP_LOCAL_EMBEDDING_ENABLED` (`true|false`)
- `LOCAL_EMBEDDING_SERVER_URL` (default `http://127.0.0.1:8088`)
- `APP_LOCAL_EMBEDDING_MODEL` (default `text-embedding-qwen3-embedding-8b`)
- `APP_LOCAL_EMBEDDING_DIMENSIONS` (default `4096`)
- `REMOTE_EMBEDDING_SERVER_URL`, `REMOTE_EMBEDDING_API_KEY`, `REMOTE_EMBEDDING_MODEL_NAME`, `REMOTE_EMBEDDING_DIMENSIONS` (optional)

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

- `QDRANT_COLLECTION_BOOKS` (default `java-chat-books`)
- `QDRANT_COLLECTION_DOCS` (default `java-docs`)
- `QDRANT_COLLECTION_ARTICLES` (default `java-articles`)
- `QDRANT_COLLECTION_PDFS` (default `java-pdfs`)
- `QDRANT_DENSE_VECTOR_NAME` (default `dense`) — named vector for dense embeddings
- `QDRANT_SPARSE_VECTOR_NAME` (default `bm25`) — named vector for BM25 sparse tokens
- `HYBRID_PREFETCH_LIMIT` (default `20`) — per-stage prefetch limit for RRF fusion queries
- `HYBRID_QUERY_TIMEOUT` (default `5s`) — timeout for hybrid search queries
- `APP_QDRANT_ENSURE_PAYLOAD_INDEXES` (default `true`) — create payload indexes on startup

### Local Qdrant

```bash
make compose-up
```

### Qdrant troubleshooting

- `QDRANT_HOST` must be a hostname only (no `http://` or `https://` prefix).
- Local compose maps Qdrant to allowed ports: gRPC `8086`, REST `8087` (`docker-compose-qdrant.yml`).
- Some scripts use REST for health checks; set `QDRANT_REST_PORT=8087` when using local compose.
- On startup, `QdrantIndexInitializer` validates that all four collections have matching dense vector dimensions. A dimension mismatch (e.g., after changing embedding providers) causes startup failure — delete the collections and re-ingest.

## RAG tuning

Common variables (see `app.rag.*` defaults in `application.properties`):

- `RAG_CHUNK_MAX_TOKENS`
- `RAG_CHUNK_OVERLAP_TOKENS`
- `RAG_TOP_K`
- `RAG_RETURN_K`
- `RAG_CITATIONS_K`
- `RAG_RERANKER_TIMEOUT` (default `12s`) — timeout budget for LLM reranking calls
