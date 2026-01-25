# Java Chat

AI-powered Java learning with **streaming answers**, **citations**, and **guided lessons** grounded in ingested documentation (RAG).

Built with Spring Boot + WebFlux, Svelte, and Qdrant.

## Features

- Streaming chat over SSE (`/api/chat/stream`) with a final `citation` event
- Guided learning mode (`/learn`) with lesson-scoped chat (`/api/guided/*`)
- Documentation ingestion pipeline (fetch → chunk → embed → dedupe → index)
- Embedding fallbacks: local embedding server → remote/OpenAI → hash fallback

## Quick start

```bash
cp .env.example .env
# edit .env and set GITHUB_TOKEN (and optionally OPENAI_API_KEY)
make compose-up   # optional local Qdrant
make dev
```

Open `http://localhost:8085/`.

## Index documentation (RAG)

```bash
make full-pipeline
```

See `docs/ingestion.md`.

## Documentation

Start with `docs/README.md`.

## Contributing

Issues and PRs welcome.
