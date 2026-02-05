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
make full-pipeline
```

Command reference: `docs/pipeline-commands.md` (scrape and ingestion targets, flags, and full vs incremental behavior).

## Documentation

Start with `docs/README.md`.

## Contributing

See `CONTRIBUTING.md`.

## License

See `LICENSE.md`.
