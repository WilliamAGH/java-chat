# Architecture

Java Chat is a Java-learning assistant focused on fast streaming answers and verifiable citations from ingested documentation.

## High-level components

- **Frontend**: Svelte 5 + Vite (built into `src/main/resources/static/`)
- **Backend**: Spring Boot (Web + WebFlux + Actuator)
- **Streaming**: Server-Sent Events (SSE) with typed event payloads
- **Retrieval**: Spring AI `VectorStore` (Qdrant) with local fallback search
- **LLM streaming**: OpenAI Java SDK (`OpenAIStreamingService`) supporting GitHub Models and OpenAI
- **Embeddings**: Strict provider selection with no runtime fallback (see [configuration](configuration.md#embeddings))

## Request flow (chat)

1) UI calls `POST /api/chat/stream`
2) Backend retrieves candidate documents from Qdrant (`RetrievalService`)
3) Documents are reranked (`RerankerService`) and converted into citations
4) Prompt is built with retrieval context (`ChatService`)
5) Response streams via SSE (`SseSupport`) and emits a final `citation` event

## Document ingestion (RAG indexing)

The ingestion pipeline uses:

- `scripts/fetch_all_docs.sh` to mirror docs into `data/docs/`
- `com.williamcallahan.javachat.cli.DocumentProcessor` (Spring `cli` profile) to ingest local docs
- `LocalStoreService` to store parsed chunks (`data/parsed/`) and hash markers (`data/index/`)

See [ingestion.md](ingestion.md) and [local store directories](domains/local-store-directories.md).

## Related design docs

See also:

- [All parsing and markdown logic](domains/all-parsing-and-markdown-logic.md)
- [Adding LLM source attribution](domains/adding-llm-source-attribution.md)
