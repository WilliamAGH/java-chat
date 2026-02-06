# Pipeline Commands

Command reference for documentation scraping, embedding, and ingestion into Qdrant.

Incremental runs are the default — unchanged content is skipped via SHA-256 hash markers.
"Full" runs require clearing local state and, optionally, resetting Qdrant collections.

---

## Quick reference

| What | Incremental | Full |
|---|---|---|
| **Scrape** (mirror HTML) | `make fetch-all` | `make fetch-force` |
| **Ingest** (chunk → embed → upload) | `make process-all` | Clear state, then `make process-all` ([details](#full-re-ingest)) |
| **Both** | `make full-pipeline` | `make fetch-force`, then clear state, then `make process-all` |
| **Ingest subset** | `DOCS_SETS=java25-complete make process-doc-sets` | — |
| **Embed locally** (no Qdrant) | `make process-local` | — |

---

## Scrape (fetch HTML mirrors)

The scrape phase mirrors upstream documentation into `data/docs/` using `wget`.
Source URLs are defined in `src/main/resources/docs-sources.properties`.

### Make targets

```bash
make fetch-all          # Incremental: skip sources that already look complete
make fetch-quick        # Incremental + include small "quick" landing mirrors
make fetch-force        # Full: force refetch even if mirrors look complete
```

### Script flags

```bash
./scripts/fetch_all_docs.sh [--include-quick] [--no-clean] [--force]
```

| Flag | Effect |
|---|---|
| `--include-quick` | Also fetch small landing-page mirrors (Spring Boot/Framework/AI quick sets) |
| `--no-clean` | Do not quarantine incomplete mirrors before refetching |
| `--force` | Refresh all sources even if they look complete |
| `--help` | Show usage |

### What "incremental" means for scraping

- `wget --mirror --timestamping` skips files that haven't changed on the server.
- Sources with fewer HTML files than their configured minimum are quarantined and re-fetched.
- Oracle Javadoc uses a deterministic Python seed generator (`scripts/oracle_javadoc_seed.py`) to avoid incomplete recursive crawls.

---

## Ingest (chunk, embed, deduplicate, upload)

The ingestion phase processes local HTML/PDF mirrors into chunked, embedded vectors and writes them to Qdrant (or caches them locally).

### Make targets

```bash
make process-all        # Incremental ingestion, upload to Qdrant (default)
make process-upload     # Same as process-all (explicit upload mode)
make process-local      # Incremental ingestion, cache embeddings locally (no Qdrant required)
make process-doc-sets   # Ingest selected doc sets only (requires DOCS_SETS=...)
make full-pipeline      # Scrape + ingest in one step
```

### Script flags

```bash
./scripts/process_all_to_qdrant.sh [--upload | --local-only] [--doc-sets=SET1,SET2]
```

| Flag | Effect |
|---|---|
| `--upload` | Upload embeddings to Qdrant (default) |
| `--local-only` | Cache embeddings under `data/embeddings-cache/` without Qdrant |
| `--doc-sets=...` | Comma-separated doc set IDs or paths to process (see [doc set filtering](#doc-set-filtering)) |
| `--help` | Show usage |

### Doc set filtering

Limit ingestion to specific doc sets by ID or path:

```bash
# Single doc set
DOCS_SETS=java25-complete make process-doc-sets
./scripts/process_all_to_qdrant.sh --upload --doc-sets=java25-complete

# Multiple doc sets
./scripts/process_all_to_qdrant.sh --upload --doc-sets=java25-complete,spring-boot-complete

# Path-style IDs
./scripts/process_all_to_qdrant.sh --upload --doc-sets=java/java25-complete
```

Available doc set IDs are defined in `DocumentationSetCatalog.java`. Common ones:

| ID | Content |
|---|---|
| `java24-complete` | Java 24 complete API (Oracle Javadoc) |
| `java25-complete` | Java 25 complete API (Oracle Javadoc) |
| `spring-boot-complete` | Spring Boot reference + API docs |
| `spring-framework-complete` | Spring Framework reference + Javadoc |
| `spring-ai-complete` | Spring AI reference + API (stable + 2.0) |
| `books` | PDF books |

### What "incremental" means for ingestion

- Per-chunk SHA-256 hash markers in `data/index/` track what has been processed.
- Unchanged files are skipped on re-runs — only new or modified content is embedded.
- File-level fingerprints (`data/index/file_*.marker`) detect when a source file changes, triggering re-chunking and pruning stale vectors from Qdrant.
- In `--local-only` mode, embeddings are cached to `data/embeddings-cache/` for deferred upload.

---

## Hybrid Qdrant setup

Ingestion writes to four Qdrant collections, each containing **dense vectors** (semantic embeddings) and **sparse vectors** (BM25 lexical tokens). Retrieval uses Qdrant's Query API with **Reciprocal Rank Fusion (RRF)** across all collections simultaneously.

### Collections

| Collection | Default name | Content |
|---|---|---|
| Books | `java-chat-books` | PDF books |
| Docs | `java-docs` | Java API, Spring reference/API, Oracle Javase |
| Articles | `java-articles` | IBM articles, JetBrains blog posts |
| PDFs | `java-pdfs` | Non-book PDFs (reserved) |

Each collection has two named vector spaces:

- **`dense`** — 4096-dimensional embeddings from the configured embedding provider (default: Qwen3 8B)
- **`bm25`** — sparse lexical vectors computed via Lucene `StandardAnalyzer` tokenization with IDF modifier

### How retrieval works

1. Query text is embedded (dense) and tokenized (sparse BM25)
2. Both vectors are sent to all four collections in parallel as Qdrant prefetch queries
3. Qdrant fuses results per-collection via RRF
4. The app merges cross-collection results, deduplicates by point UUID, and returns the top-K by fused score
5. Results are reranked before being passed to the LLM as retrieval context

### Collection auto-creation

On startup (non-test profile), `QdrantIndexInitializer` ensures all four collections exist with the correct dense vector dimensions and sparse vector configuration, and creates payload indexes for filtering fields (`url`, `hash`, `docSet`, `sourceName`, etc.). Dimension mismatches cause startup failure.

### Environment variables

See [configuration.md](configuration.md#qdrant) for the full list of Qdrant environment variables, including collection name overrides and hybrid search tuning.

---

## Full re-ingest

There is no single "force" flag for ingestion. A full re-ingest requires clearing local state:

1. Stop the app and any running ingestion processes.

2. Clear local deduplication state so all content is re-chunked and re-embedded:

```bash
rm -rf data/index data/parsed data/embeddings-cache
```

3. Optionally, delete Qdrant collections to start clean (they are auto-created on next startup):

```bash
# Local Qdrant example (REST port 8087)
for coll in java-chat-books java-docs java-articles java-pdfs; do
  curl -X DELETE "http://localhost:8087/collections/$coll"
done
```

4. Re-run ingestion:

```bash
make process-all
```

---

## Embeddings cache management (HTTP API)

When the app is running, the embeddings cache can be managed via HTTP endpoints:

```bash
# View cache statistics
curl -sS http://localhost:8085/api/embeddings-cache/stats

# Upload cached embeddings to Qdrant (batch size configurable)
curl -sS -X POST "http://localhost:8085/api/embeddings-cache/upload?batchSize=100"

# Take a cache snapshot
curl -sS -X POST http://localhost:8085/api/embeddings-cache/snapshot

# Export cache to file
curl -sS -X POST "http://localhost:8085/api/embeddings-cache/export?filename=cache-backup.gz"

# Import cache from file
curl -sS -X POST "http://localhost:8085/api/embeddings-cache/import?filename=cache-backup.gz"
```

---

## Ingest via HTTP API (runtime)

With the app running, you can trigger ingestion directly via HTTP:

```bash
# Ingest local docs directory
curl -sS -X POST "http://localhost:8085/api/ingest/local?dir=data/docs&maxFiles=50000"

# Small remote crawl (dev/debug)
curl -sS -X POST "http://localhost:8085/api/ingest?maxPages=100"
```

---

## Gradle (no scripts)

The ingestion scripts use Gradle to build a runnable JAR:

```bash
./gradlew buildForScripts
```

To run the CLI directly:

```bash
app_jar=$(ls -1 build/libs/*.jar | grep -v -- "-plain.jar" | head -1)

# Upload mode
EMBEDDINGS_UPLOAD_MODE=upload java -Dspring.profiles.active=cli -jar "$app_jar"

# Local-only mode
EMBEDDINGS_UPLOAD_MODE=local-only java -Dspring.profiles.active=cli -jar "$app_jar"

# With doc set filtering
DOCS_SETS=java25-complete EMBEDDINGS_UPLOAD_MODE=upload java -Dspring.profiles.active=cli -jar "$app_jar"
```

The docs root defaults to `data/docs` unless `DOCS_DIR` is set.

---

## Monitoring

```bash
scripts/monitor_progress.sh        # Simple log-based progress view
scripts/monitor_indexing.sh        # Dashboard view (requires jq and bc)
```

During ingestion, the process script shows live progress with file counts, current doc set, and completion percentage.

---

## Make target reference

```bash
make help                  # List all available targets
make fetch-all             # Incremental scrape
make fetch-force           # Full scrape (force refetch)
make fetch-quick           # Incremental scrape + quick landing mirrors
make process-all           # Incremental ingest (upload to Qdrant)
make process-upload        # Explicit upload mode
make process-local         # Local-only embeddings (no Qdrant)
make process-doc-sets      # Ingest selected doc sets (DOCS_SETS=...)
make full-pipeline         # Scrape + ingest
make compose-up            # Start local Qdrant via Docker Compose
make compose-down          # Stop Qdrant
make compose-logs          # Tail Qdrant logs
make health                # Check app health endpoint
make ingest                # HTTP ingest (first 1000 docs)
```
