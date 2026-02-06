# Getting started

## Prerequisites

- Java 25 (project toolchain)
- Node.js (frontend build/dev)
- Docker (optional, for local Qdrant)
- `wget` (optional, for `make fetch-all`)

## Quick start (dev)

1) Create your env file:

```bash
cp .env.example .env
```

2) Edit `.env` and set `GITHUB_TOKEN` (GitHub Models) or `OPENAI_API_KEY` (OpenAI).

3) Start local Qdrant (optional but recommended for full RAG):

```bash
make compose-up
```

- gRPC: `localhost:8086` (set `QDRANT_PORT=8086`)
- REST: `localhost:8087` (used by some scripts; set `QDRANT_REST_PORT=8087` if needed)

4) Run the app in dev mode (Svelte + Spring Boot):

```bash
make dev
```

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

This fetches all documentation sources and ingests them with dense + sparse (BM25) vectors across four Qdrant collections.

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
