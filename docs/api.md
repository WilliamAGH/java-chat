# HTTP API

Base URL (local dev): `http://localhost:8085`

## Streaming chat (SSE)

### POST `/api/chat/stream`

Request body:

```json
{ "sessionId": "s1", "latest": "How do Java records work?" }
```

SSE event types (see `SseConstants`):

- `status` → shared status/error payload, used for progress and non-terminal warnings
- `provider` → `{"provider":"GitHub Models"}` (the one configured provider selected for this request)
- `text` → `{"text":"..."}`
- `citation` → JSON array of citations
- `error` → shared status/error payload for a terminal stream failure

`status` and `error` both serialize the following payload shape. The event type determines
whether it is a progress/warning notice or a terminal failure.

```json
{
  "message": "Citations could not be loaded",
  "details": "Citations could not be loaded",
  "code": "citation.partial-failure",
  "retryable": false,
  "stage": "citation"
}
```

| Field | Meaning |
| --- | --- |
| `message` | Human-readable summary. |
| `details` | Additional diagnostic detail; it can be `null`. |
| `code` | Stable machine-readable status/error code. It can be `null` when no classification applies. Clients must branch on this field rather than the message text. |
| `retryable` | Whether a client may retry the operation. It can be `null` for ordinary progress notices. |
| `stage` | Pipeline stage associated with the notice or failure, such as `citation` or `stream`. It can be `null` for ordinary progress notices. |

Stream-provider `error` events populate `code`, `retryable`, and `stage`; a citation warning
emits the same metadata on a `status` event. Treat `null` metadata as absent.

The first event for `POST /api/chat/stream` and `POST /api/guided/stream` is a `status` event with
`code: "stream.preparing"` and `stage: "retrieval"`. It confirms that the stream was admitted
before history and retrieval work begin; a later dependency failure still emits a terminal `error`
event.

Chat uses exactly one provider selected by `LLM_PRIMARY_PROVIDER` (`github_models` or `openai`).
The request never falls back to the other provider: an unavailable, rate-limited, or failed configured
provider terminates the stream with an `error` event.

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
- GET `/api/guided/content/stream?slug=...` (SSE `text` events, JSON-wrapped chunks)
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

## Ingestion

- POST `/api/ingest?maxPages=...`
- POST `/api/ingest/local?dir=...&maxFiles=...`
