# Java Chat (Spring Boot, Java 21)

A modern, streaming RAG chat for Java learners, grounded in Java 24/25 documentation with precise citations. Backend-only (Spring WebFlux + Spring AI + Qdrant). Uses OpenAI API with local embeddings (LM Studio) and Qdrant Cloud for vector storage.

## 🚀 Latest Updates

- **Complete Documentation Coverage**: Successfully ingested 22,756+ documents from Java 24/25 and Spring ecosystem
- **Local Embeddings**: Integrated with LM Studio using text-embedding-qwen3-embedding-8b model (4096 dimensions)
- **Qdrant Cloud Integration**: Connected to cloud-hosted vector database with 22,756+ indexed vectors
- **Consolidated Pipeline**: Single-command fetch and process pipeline with SHA-256 hash-based deduplication
- **Smart Deduplication**: Prevents redundant processing and re-uploading of documents
- **Comprehensive Documentation**: Java 24 (10,743 files), Java 25 (10,510 files), Java 25 EA (1,257+ files), Spring AI (218 files)

## Quick start

### 🚀 One-Command Setup (Recommended)
```bash
# Complete setup: fetch all docs and process to Qdrant
make full-pipeline
```

This single command will:
1. Fetch all Java 24/25/EA and Spring documentation (skips existing)
2. Process documents with embeddings
3. Upload to Qdrant with deduplication
4. Start the application on port 8085

### Manual Setup
```bash
# 1) Set env vars (example - use your real values)
# Create a .env file in repo root (see .env.example for all options):
# 
# Authentication - You can use one or both:
# 
# GitHub Models (free tier available):
# GITHUB_TOKEN=your_github_personal_access_token
# 
# OpenAI API (separate, independent):
# OPENAI_API_KEY=sk-xxx
# 
# How the app uses these:
# 1. Spring AI tries GITHUB_TOKEN first, then OPENAI_API_KEY
# 2. On auth failure, fallback tries direct OpenAI or GitHub Models
#
# Optional: Local embeddings (if using LM Studio)
# APP_LOCAL_EMBEDDING_ENABLED=true
# LOCAL_EMBEDDING_SERVER_URL=http://127.0.0.1:8088
# APP_LOCAL_EMBEDDING_MODEL=text-embedding-qwen3-embedding-8b
# APP_LOCAL_EMBEDDING_DIMENSIONS=4096  # Note: 4096 for qwen3-embedding-8b
#
# Optional: Qdrant Cloud (for vector storage)
# QDRANT_HOST=xxx.us-west-1-0.aws.cloud.qdrant.io
# QDRANT_PORT=8086
# QDRANT_SSL=true
# QDRANT_API_KEY=your-qdrant-api-key
# QDRANT_COLLECTION=java-chat

# 2) Fetch documentation (checks for existing)
make fetch-all

# 3) Process and run (auto-processes new docs)
make run
```

Health check: GET http://localhost:8085/actuator/health
Embeddings health: GET http://localhost:8085/api/chat/health/embeddings

## Makefile (recommended)

Common workflows are scripted via `Makefile`:

```bash
# Discover commands
make help

# Build / Test
make build
make test

# Run packaged jar
make run

# Live dev (Spring DevTools hot reload)
make dev

# Local Qdrant via Docker Compose (optional)
make compose-up    # start
make compose-logs  # tail logs
make compose-ps    # list services
make compose-down  # stop

# Convenience API helpers
make health
make ingest        # ingest first 1000 docs
make citations     # sample citations query
```

## Configuration

All config is env-driven. See `src/main/resources/application.properties` for defaults. Key vars:

### API Configuration
- `GITHUB_TOKEN`: GitHub personal access token for GitHub Models
- `OPENAI_API_KEY`: OpenAI API key (separate, independent service)
- `OPENAI_MODEL`: Model name, default `gpt-4o-mini` (used by all endpoints)
- `OPENAI_TEMPERATURE`: default `0.7`
- `OPENAI_BASE_URL`: Spring AI base URL (default: `https://models.github.ai/inference`)

**How APIs are used:**
1. **Spring AI** (primary): Uses `OPENAI_BASE_URL` with `GITHUB_TOKEN` (preferred) or `OPENAI_API_KEY`
2. **Direct fallbacks** (on 401 auth errors):
   - If `OPENAI_API_KEY` exists: Direct OpenAI API at `https://api.openai.com`
   - If only `GITHUB_TOKEN` exists: GitHub Models at `https://models.github.ai/inference`

### Local Embeddings (LM Studio)
- `APP_LOCAL_EMBEDDING_ENABLED`: `true` to use local embeddings server
- `LOCAL_EMBEDDING_SERVER_URL`: URL of your local embeddings server (default: `http://127.0.0.1:8088`)
- `APP_LOCAL_EMBEDDING_DIMENSIONS`: `4096` (actual dimensions for qwen3-embedding-8b model)
- Recommended model: `text-embedding-qwen3-embedding-8b` (4096 dimensions)
- Note: LM Studio may show tokenizer warnings which are harmless

### Qdrant Vector Database
- `QDRANT_HOST`: Cloud host (e.g., `xxx.us-west-1-0.aws.cloud.qdrant.io`) or `localhost` for Docker
- `QDRANT_PORT`: `8086` for gRPC (mapped from Docker's 6334)
- `QDRANT_API_KEY`: Your Qdrant Cloud API key (empty for local)
- `QDRANT_SSL`: `true` for cloud, `false` for local
- `QDRANT_COLLECTION`: default `java-chat`

### Documentation Sources
- `DOCS_ROOT_URL`: default `https://docs.oracle.com/en/java/javase/24/`
- `DOCS_SNAPSHOT_DIR`: default `data/snapshots` (raw HTML)
- `DOCS_PARSED_DIR`: default `data/parsed` (chunk text)
- `DOCS_INDEX_DIR`: default `data/index` (ingest hash markers)

## Documentation Ingestion

### 🎯 Consolidated Pipeline (Recommended)

We provide a unified pipeline that handles all documentation fetching and processing with intelligent deduplication:

```bash
# Complete pipeline: fetch all docs and process to Qdrant
make full-pipeline

# Or run steps separately:
make fetch-all     # Fetch all documentation (checks for existing)
make process-all   # Process and upload to Qdrant (deduplicates)
```

### Available Documentation
The pipeline automatically fetches and processes:
- **Java 24 API**: Complete Javadocs (10,743 files ✅)
- **Java 25 API**: Complete API docs (10,510 files ✅)
- **Java 25 EA**: Early access from download.java.net (1,257+ files ✅)
- **Spring Boot**: Full reference and API documentation (10,379 files)
- **Spring Framework**: Core Spring docs (13,342 files)
- **Spring AI**: AI/ML integration docs (218 files ✅)

**Current Status**: Successfully indexed 22,756+ documents in Qdrant Cloud with automatic SHA-256 deduplication

### Fetching Documentation

#### Consolidated Fetch (Recommended)
```bash
# Fetch ALL documentation with deduplication checking
./scripts/fetch_all_docs.sh

# Features:
# - Checks for existing documentation before fetching
# - Downloads only missing documentation
# - Creates metadata file with statistics
# - Logs all operations for debugging
```

#### Legacy Scripts (for specific needs)
```bash
# Individual fetchers if you need specific docs
./scripts/fetch_java_complete.sh      # Java 24 only
./scripts/fetch_java25_ea_complete.sh # Java 25 EA only
./scripts/fetch_spring_complete.sh    # Spring ecosystem only
```

### Processing and Uploading to Qdrant

#### Consolidated Processing (Recommended)
```bash
# Process all documentation with deduplication
./scripts/process_all_to_qdrant.sh

# Features:
# - SHA-256 hash-based deduplication
# - Tracks processed files in hash database
# - Prevents redundant embedding generation
# - Prevents duplicate uploads to Qdrant
# - Shows real-time progress
# - Generates processing statistics
```

#### Important Usage Notes

**Resumable Processing**: The script is designed to handle interruptions gracefully:
- If the connection is lost or the process is killed, simply re-run the script
- It will automatically skip all previously indexed documents (via hash markers in `data/index/`)
- Progress is preserved in Qdrant - vectors are never lost
- Each successful chunk creates a persistent marker file

**How Resume Works**:
1. **Hash Markers**: Each successfully indexed chunk creates a file in `data/index/` named with its SHA-256 hash
2. **On Restart**: The system checks for existing hash files before processing any chunk
3. **Skip Logic**: If `data/index/{hash}` exists, the chunk is skipped (already in Qdrant)
4. **Atomic Operations**: Markers are only created AFTER successful Qdrant insertion

**Monitoring Progress**:
```bash
# Check current vector count in Qdrant
source .env && curl -s -H "api-key: $QDRANT_API_KEY" \
  "https://$QDRANT_HOST/collections/$QDRANT_COLLECTION" | \
  grep -o '"points_count":[0-9]*' | cut -d: -f2

# Count processed chunks (hash markers)
ls data/index/ | wc -l

# Monitor real-time progress (create monitor_progress.sh)
#!/bin/bash
source .env
while true; do
    count=$(curl -s -H "api-key: $QDRANT_API_KEY" \
      "https://$QDRANT_HOST/collections/$QDRANT_COLLECTION" | \
      grep -o '"points_count":[0-9]*' | cut -d: -f2)
    echo -ne "\r[$(date +%H:%M:%S)] Vectors in Qdrant: $count"
    sleep 5
done
```

**Performance Notes**:
- Local embeddings (LM Studio) process ~35-40 vectors/minute
- Full indexing of 60,000 documents takes ~24-30 hours
- The script has NO timeout - it will run until completion
- Safe to run multiple times - deduplication prevents any redundant work

#### Manual Ingestion (if needed)
```bash
# The application automatically processes docs on startup
make run  # Starts app and processes any new documents

# Or trigger manual ingestion via API
curl -X POST "http://localhost:8085/api/ingest/local?path=data/docs&maxFiles=10000"
```

### Deduplication & Quality

#### How Deduplication Works
1. **Content Hashing**: Each document chunk gets a SHA-256 hash based on `url + chunkIndex + content`
2. **Hash Database**: Processed files are tracked in `data/.processed_hashes.db`
3. **Vector Store Check**: Before uploading, checks if hash already exists in Qdrant
4. **Skip Redundant Work**: Prevents:
   - Re-downloading existing documentation
   - Re-processing already embedded documents
   - Duplicate vectors in Qdrant

#### Quality Features
- **Smart chunking**: ~900 tokens with 150 token overlap for context preservation
- **Metadata enrichment**: URL, title, package name, chunk index for precise citations
- **Idempotent operations**: Safe to run multiple times without side effects
- **Automatic retries**: Handles network failures gracefully

## Chat API (streaming)

- POST `/api/chat/stream` (SSE)
  - Body: `{ "sessionId": "s1", "latest": "How do I use records?" }`
  - Streams text tokens; on completion, stores the assistant response in session memory.

- GET `/api/chat/citations?q=your+query`
  - Returns top citations (URL, title, snippet) for the query.

- GET `/api/chat/export/last?sessionId=s1`
  - Returns the last assistant message (markdown).

- GET `/api/chat/export/session?sessionId=s1`
  - Returns the full session conversation as markdown.

## Retrieval & quality

- Chunking: ~900 tokens with 150 overlap (GPT-5-compatible segmentation).
- Vector search: Qdrant similarity. Next steps: enable hybrid (BM25 + vector) and MMR diversity.
- Re-ranker: planned BGE reranker (DJL) or LLM rerank for top-k. Citations pinned to top-3 by score.

## Citations & learning UX

Responses are grounded with citations and “background tooltips”:
- Citation metadata: `package/module`, `JDK version`, `resource/framework + version`, `URL`, `title`.
- Background: tooltips with bigger-picture context, hints, and reminders to aid understanding.

Data structures (server):
- Citation: `{ url, title, anchor, snippet }` (see `com.williamcallahan.javachat.model.Citation`).
- TODO: `Enrichment` payload with fields: `packageName`, `jdkVersion`, `resource`, `resourceVersion`, `hints[]`, `reminders[]`, `background[]`.

UI (server-rendered static placeholder):
- Return JSON with `citations` and `enrichment`. The client should render:
  - Compact “source pills” with domain icon, title, and external-link affordance (open in new tab).
  - Hover tooltips for background context (multi-paragraph allowed, markdown-safe).
  - Clear, modern layout (Shadcn-inspired). Future: SPA frontend if needed.

## Models & Architecture

### Chat Model
- **OpenAI GPT-4o-mini**: Fast, cost-effective, high-quality responses
- Direct OpenAI API integration (GitHub Models API deprecated due to authentication issues)
- Streaming via Server-Sent Events (SSE) for real-time interaction
- Session memory management for context preservation

### Embeddings
- **Local LM Studio**: `text-embedding-qwen3-embedding-8b` (4096 dimensions)
  - Running on Apple Silicon for fast, private embeddings
  - No external API calls for document processing
  - Server running at http://127.0.0.1:8088 (configurable)
- **Fallback**: OpenAI `text-embedding-3-small` if local server unavailable
- **Status**: ✅ Healthy and operational

### Vector Search & RAG
- **Qdrant Cloud**: High-performance HNSW vector search
  - Collection: `java-chat` with 22,756+ vectors
  - Dimensions: 4096 (matching local embedding model)
  - Connected via gRPC on port 8086 (mapped from container's 6334) with SSL
- **Smart Retrieval**: 
  - Top-K similarity search with configurable K (default: 12)
  - MMR (Maximum Marginal Relevance) for result diversity
  - TF-IDF reranking for relevance optimization
- **Citation System**: Top 3 sources with snippets and metadata

## Maintenance

- Re-ingesting docs: rerun `/api/ingest?maxPages=...` after a docs update.
- Qdrant housekeeping: snapshot/backup via Qdrant Cloud; set collection to HNSW + MMR/hybrid as needed.
- Env changes: restart app to pick up new model names or hosts.
- Logs/metrics: Spring Boot Actuator endpoints enabled for health/info/metrics.
 - Observability TODO: add tracing and custom metrics (query time, tokens, hit rates).

### Troubleshooting (Qdrant Cloud)

- Error `Invalid host or port` or `Expected closing bracket for IPv6 address`:
  - Ensure `QDRANT_HOST` has no `https://` prefix; it must be the hostname only.
  - Ensure `QDRANT_PORT=6334` and `QDRANT_SSL=true`.
  - Makefile forces IPv4 (`-Djava.net.preferIPv4Stack=true`) to avoid macOS IPv6 resolver quirks.
- Dimension mismatch errors:
  - Ensure `APP_LOCAL_EMBEDDING_DIMENSIONS=4096` matches your embedding model
  - Delete and recreate Qdrant collection if dimensions change
- LM Studio tokenizer warnings:
  - "[WARNING] At least one last token in strings embedded is not SEP" is harmless

## Roadmap

- [ ] Hybrid retrieval (BM25 + vector), MMR, and re-ranker integration.
- [ ] Enrichment payload + endpoint for tooltips/hints/reminders with package/JDK metadata.
- [ ] Content hashing + upsert-by-hash for dedup and change detection.
- [ ] Minimal SPA with modern source pills, tooltips, and copy actions.
- [ ] Persist user chats + embeddings (future, configurable).
 - [ ] Slash-commands (/search, /explain, /example) with semantic routing.
 - [ ] Per-session rate limiting.
 - [ ] DigitalOcean Spaces S3 offload for snapshots & parsed text.
 - [ ] Docker Compose app service + optional local embedding model.
## Stack details

- Spring Boot 3.5.5 (WebFlux, Actuator)
- Spring AI 1.0.1 (OpenAI client, VectorStore Qdrant)
- Qdrant (HNSW vector DB); `docker-compose.yml` includes a local dev service
- JSoup (HTML parsing), JTokkit (tokenization), Fastutil (utils)

Docker Compose (Qdrant only, optional fallback when you outgrow the free Qdrant Cloud plan or for offline dev):
```bash
docker compose up -d
# Then set QDRANT_HOST=localhost QDRANT_PORT=8086
```
