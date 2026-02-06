# Documentation ingestion (RAG indexing)

Java Chat includes scripts and a CLI profile to mirror upstream documentation into `data/docs/` and ingest it into Qdrant hybrid collections with content-hash deduplication.

Command reference:

- See [pipeline-commands.md](pipeline-commands.md) for all scrape and ingestion commands, flags, and full vs incremental behavior.

## Pipeline overview

1. **Fetch** documentation into `data/docs/` (HTML mirrors via `wget`).
2. **Chunk** content using JTokkit's CL100K_BASE tokenizer (~900 tokens per chunk with 150-token overlap).
3. **Deduplicate** chunks by SHA-256 hash — unchanged content is skipped on re-runs.
4. **Embed** chunks with both dense vectors (semantic, from configured embedding provider) and sparse vectors (Lucene `StandardAnalyzer` tokens encoded as hashed term-frequency vectors).
5. **Upsert** to Qdrant hybrid collections.

## Fetch docs

Fetch all configured sources:

```bash
make fetch-all
```

This runs `scripts/fetch_all_docs.sh` (requires `wget`). Source URLs live in:

- `src/main/resources/docs-sources.properties`

See [pipeline-commands.md](pipeline-commands.md#scrape-fetch-html-mirrors) for flags (`--force`, `--include-quick`, `--no-clean`).

## Process + upload to Qdrant

```bash
make process-all
```

This runs `scripts/process_all_to_qdrant.sh`, which:

- Loads `.env`
- Validates Qdrant connectivity and embedding server availability
- Builds the app JAR (`./gradlew buildForScripts`)
- Runs the `cli` Spring profile (`DocumentProcessor`)
- Routes each doc set to the appropriate Qdrant collection (`QdrantCollectionRouter`)
- Writes both dense and sparse (BM25) vectors per chunk via gRPC (`HybridVectorService`)

### Doc set filtering (CLI)

Limit ingestion to specific doc sets:

```bash
DOCS_SETS=java25-complete make process-doc-sets
./scripts/process_all_to_qdrant.sh --doc-sets=java25-complete,spring-boot-complete
```

See [pipeline-commands.md](pipeline-commands.md#doc-set-filtering) for the full doc set ID table.

## Hybrid vector storage

Each ingested chunk is stored as a Qdrant point with two named vectors:

- **`dense`** — semantic embedding from the configured provider (default 4096 dimensions via Qwen3 8B)
- **`bm25`** — sparse lexical vector from Lucene `StandardAnalyzer` tokenization encoded as hashed term-frequency values with Qdrant IDF modifier

This enables hybrid retrieval: dense search captures semantic similarity while sparse search captures exact keyword matches. Sparse vectors use local hashed TF values and Qdrant applies IDF at query time. Results are fused via Reciprocal Rank Fusion (RRF).

If sparse encoding logic changes (tokenization or hashing), run a full re-ingest so stored sparse vectors stay compatible with query-time encoding.

See [pipeline-commands.md](pipeline-commands.md#hybrid-qdrant-setup) for collection layout and retrieval details.

## Deduplication markers

Deduplication is based on per-chunk SHA-256 markers stored locally:

- `data/index/` contains one file per ingested chunk hash
- `data/parsed/` contains chunk text snapshots for debugging
- `data/index/file_*.marker` records file-level fingerprints (size, mtime, content SHA-256) and chunk hashes so re-runs can skip unchanged files and prune stale vectors when a source file changes

See [local store directories](domains/local-store-directories.md) for details.

## Ingest via HTTP API

With the app running:

```bash
# Ingest a local docs directory
curl -sS -X POST "http://localhost:8085/api/ingest/local?dir=data/docs&maxFiles=50000"

# Small remote crawl (dev/debug)
curl -sS -X POST "http://localhost:8085/api/ingest?maxPages=100"
```

## Monitoring

```bash
scripts/monitor_progress.sh        # Simple log-based view
scripts/monitor_indexing.sh        # Dashboard view (requires jq and bc)
```
