# Java Chat

[![Java Chat Application](src/main/resources/static/images/java-chat-app.png)](https://javachat.ai)

Learn programming in Java and more with **streaming answers**, **citations**, and **guided lessons** grounded with a deep knowledge base of ingested documentation (RAG).

Built with Spring Boot + WebFlux, Svelte, and Qdrant.

## Features

- Streaming chat over SSE (`/api/chat/stream`) with a final `citation` event
- Guided learning mode (`/learn`) with lesson-scoped chat (`/api/guided/*`)
- Documentation ingestion pipeline (fetch → chunk → embed → dedupe → index)
- Chunking uses JTokkit's CL100K_BASE tokenizer (GPT-3.5/4 style) for token counting
- Embeddings use strict provider selection and fail fast when the provider is unavailable (no runtime fallbacks)

## Quick start

### Prerequisites

This project uses **Gradle Toolchains** with **Temurin JDK 25** and **mise** (or **asdf**) for reproducible builds.

#### Option 1: Using mise (recommended)

```bash
# Install mise if you don't have it: https://mise.jdnow.dev/
mise install
```

#### Option 2: Using asdf

```bash
# Install asdf if you don't have it: https://asdf-vm.com/
asdf plugin add java https://github.com/halcyon/asdf-java.git
asdf install
```

**What happens**: Gradle Toolchains will auto-download Temurin JDK 25 on first build if not present locally. The `mise`/`asdf` setup ensures your shell and IDE (IntelliJ) use the correct Java version.

### Running

```bash
cp .env.example .env
# edit .env and set GITHUB_TOKEN or OPENAI_API_KEY
make compose-up   # optional local Qdrant
make dev
```

Open `http://localhost:8085/`.

## Index documentation (RAG)

```bash
make full-pipeline          # fetch all docs + ingest into Qdrant
make process-all            # ingest only (incremental, upload to Qdrant)
REPO_URL=https://github.com/owner/repository make process-github-repo
SYNC_EXISTING=1 make process-github-repo
```

Ingestion writes dense + BM25 sparse vectors to four hybrid Qdrant collections, queried via RRF fusion.

Full command reference (scrape flags, doc set filtering, HTTP API, full re-ingest):
**[docs/pipeline-commands.md](docs/pipeline-commands.md)**

GitHub source repository ingestion details:
**[docs/github-repository-ingestion.md](docs/github-repository-ingestion.md)**

## Documentation

Start with `docs/README.md`.

## Contributing

See `CONTRIBUTING.md`.

## License

See `LICENSE.md`.
