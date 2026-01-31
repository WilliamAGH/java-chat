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

Embeddings are configured with a fallback chain (see `EmbeddingFallbackConfig`):

1) Local embedding server (when enabled)
2) Remote OpenAI-compatible embedding provider (optional)
3) OpenAI embeddings (optional; requires `OPENAI_API_KEY`)
4) Hash-based fallback (deterministic, not semantic)

Common variables:

- `APP_LOCAL_EMBEDDING_ENABLED` (`true|false`)
- `LOCAL_EMBEDDING_SERVER_URL` (default `http://127.0.0.1:8088`)
- `APP_LOCAL_EMBEDDING_MODEL` (default `text-embedding-qwen3-embedding-8b`)
- `APP_LOCAL_EMBEDDING_DIMENSIONS` (default `4096`)
- `APP_LOCAL_EMBEDDING_USE_HASH_WHEN_DISABLED` (default `true`)
- `REMOTE_EMBEDDING_SERVER_URL`, `REMOTE_EMBEDDING_API_KEY`, `REMOTE_EMBEDDING_MODEL_NAME`, `REMOTE_EMBEDDING_DIMENSIONS` (optional)

## Qdrant

The app uses Qdrant via Spring AI’s Qdrant vector store starter.

Common variables:

- `QDRANT_HOST` (default `localhost`)
- `QDRANT_PORT` (gRPC; local compose maps to `8086`)
- `QDRANT_REST_PORT` (REST; local compose maps to `8087`, mainly for scripts)
- `QDRANT_API_KEY` (required for cloud; empty for local)
- `QDRANT_SSL` (`true` for cloud, `false` for local)
- `QDRANT_COLLECTION` (default `java-chat`)

Local Qdrant:

```bash
make compose-up
```

### Qdrant troubleshooting

- `QDRANT_HOST` must be a hostname only (no `http://` or `https://` prefix).
- Local compose maps Qdrant to allowed ports: gRPC `8086`, REST `8087` (`docker-compose-qdrant.yml`).
- Some scripts use REST for health checks; set `QDRANT_REST_PORT=8087` when using local compose.

## RAG tuning

Common variables (see `app.rag.*` defaults in `application.properties`):

- `RAG_CHUNK_MAX_TOKENS`
- `RAG_CHUNK_OVERLAP_TOKENS`
- `RAG_TOP_K`
- `RAG_RETURN_K`
- `RAG_CITATIONS_K`
