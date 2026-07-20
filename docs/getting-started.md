# Getting started

## Prerequisites

- Java 25 (project toolchain)
- Node.js 24.15.0 (frontend build/dev)
- Docker (optional, for local Qdrant)
- `wget` (optional, for `make fetch-all`)

## Quick start (dev)

1) Create your env file:

```bash
cp .env.example .env
```

2) Edit `.env` for local execution. The checked-in example already uses `SPRING_PROFILE=local`, the
`java-chat-local-qwen3-embedding-4b-2560-*` collection names, and repository-local generation state roots.
Configure the shared gateway `OPENAI_BASE_URL` and `OPENAI_API_KEY` used by embeddings and gateway chat.

Select one chat provider with its matching chat credential:

- GitHub Models: `LLM_PRIMARY_PROVIDER=github_models` and `GITHUB_TOKEN`
- OpenAI: `LLM_PRIMARY_PROVIDER=openai` and `OPENAI_API_KEY`

Java Chat does not dispatch a failed request to another provider.

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
canonical full documentation sources and ingests them with dense + sparse (BM25) vectors across four isolated
local collections. Quick mirrors remain explicitly opt-in.

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
