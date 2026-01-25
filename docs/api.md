# HTTP API

Base URL (local dev): `http://localhost:8085`

## Streaming chat (SSE)

### POST `/api/chat/stream`

Request body:

```json
{ "sessionId": "s1", "latest": "How do Java records work?" }
```

SSE event types (see `SseConstants`):

- `status` → `{"message":"...","details":"..."}`
- `text` → `{"text":"..."}`
- `citation` → JSON array of citations
- `error` → `{"message":"...","details":"..."}`

Example:

```bash
curl -N -H "Content-Type: application/json" \
  -d '{"sessionId":"s1","latest":"Explain Java records"}' \
  http://localhost:8085/api/chat/stream
```

### Other chat endpoints

- GET `/api/chat/citations?q=...`
- GET `/api/chat/health/embeddings`
- POST `/api/chat/clear?sessionId=...`
- GET `/api/chat/session/validate?sessionId=...`
- GET `/api/chat/export/last?sessionId=...`
- GET `/api/chat/export/session?sessionId=...`
- GET `/api/chat/diagnostics/retrieval?q=...`

## Guided learning

- GET `/api/guided/toc`
- GET `/api/guided/lesson?slug=...`
- GET `/api/guided/citations?slug=...`
- GET `/api/guided/enrich?slug=...`
- GET `/api/guided/content/stream?slug=...` (SSE, raw markdown)
- GET `/api/guided/content?slug=...` (JSON)
- GET `/api/guided/content/html?slug=...` (HTML)
- POST `/api/guided/stream` (SSE; request includes `sessionId`, `slug`, `latest`)

## Markdown rendering

- POST `/api/markdown/render`
- POST `/api/markdown/preview`
- POST `/api/markdown/render/structured`
- GET `/api/markdown/cache/stats`
- POST `/api/markdown/cache/clear`

## Enrichment

- GET `/api/enrich?q=...`
- GET `/api/chat/enrich?q=...` (alias)

## Ingestion + embeddings cache

- POST `/api/ingest?maxPages=...`
- POST `/api/ingest/local?dir=...&maxFiles=...`
- GET `/api/embeddings-cache/stats`
- POST `/api/embeddings-cache/upload?batchSize=...`
- POST `/api/embeddings-cache/snapshot`
- POST `/api/embeddings-cache/export?filename=...`
- POST `/api/embeddings-cache/import?filename=...`

