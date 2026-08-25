# Getting started

## Prerequisites

- Java 25 (project toolchain)
- Node.js 24.18.0 (frontend build/dev)
- Docker (optional, for local Qdrant)
- Wget2 and MuPDF's `mutool` (optional, for `make fetch-all`; see
  [scrape prerequisites](pipeline-commands.md#scrape-fetch-html-mirrors))

## Quick start (dev)

1) Create your env file:

```bash
cp .env.example .env
```

2) Edit `.env` for local execution. The checked-in example already uses `SPRING_PROFILE=local`, the shared
`java-chat-qwen3-embedding-4b-2560-*` collection names, and repository-local generation state roots.
Configure the shared gateway `OPENAI_BASE_URL` and `OPENAI_API_KEY` used by embeddings and gateway chat.

Set `OPENAI_API_KEY`, `OPENAI_BASE_URL`, and a non-blank gateway `OPENAI_MODEL` alias (the default is `gpt-5.4`).
Java Chat uses the shared gateway only and does not dispatch failed requests to another provider.
Leave `app.llm.reasoning-effort` unset to preserve the gateway/model default; set one of `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, or `max` only for an explicit override.

3) Start the fresh generation-specific Qdrant 1.18.3 Compose project (optional but required for full RAG):

```bash
make compose-up
```

- gRPC: `localhost:8086` (set `QDRANT_PORT=8086`)
- REST: `localhost:8087` (used by some scripts; set `QDRANT_REST_PORT=8087` if needed)

4) Run the app in dev mode (Svelte + Spring Boot):

```bash
make dev
```

The local `make run`, `make dev`, and `make dev-backend` commands create missing collection schemas only when
`QDRANT_HOST` is `localhost` or `127.0.0.1`. Any remote Qdrant connection remains fail-closed and requires the
generation collections to exist before startup.

Open:

- App: `http://localhost:8085/`
- Chat: `http://localhost:8085/chat`
- Guided learning: `http://localhost:8085/learn`

## Run packaged JAR

Build + run the packaged Spring Boot JAR (also builds the frontend):

```bash
make run
```

Health:

```bash
make health
```

## Documentation ingestion (optional)

To mirror upstream docs into `data/docs/` and index them into Qdrant hybrid collections:

```bash
make full-pipeline
```

Run this only after the local generation configuration and gateway/Qdrant preflight are valid. It fetches all
canonical full documentation sources and ingests them with dense + sparse (BM25) vectors across the four
shared-generation collection names on the local Qdrant instance. Quick mirrors remain explicitly opt-in.

For incremental vs full runs, doc set filtering, and all available flags:

- [Pipeline commands](pipeline-commands.md) — complete command reference
- [Ingestion](ingestion.md) — pipeline internals and deduplication

## Common commands

```bash
make help
make build
make test
make lint
make dev-backend
```
