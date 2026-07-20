# Documentation ingestion (RAG indexing)

Java Chat includes scripts and a CLI profile to mirror upstream documentation into the configurable `DOCS_DIR`
(default `data/docs/`) and ingest it into Qdrant hybrid collections with content-hash deduplication.

Command reference:

- See [pipeline-commands.md](pipeline-commands.md) for all scrape and ingestion commands, flags, and full vs incremental behavior.
- See [github-repository-ingestion.md](github-repository-ingestion.md) for GitHub source repository ingestion.

## Pipeline overview

1. **Fetch** documentation into `DOCS_DIR` (defaults to `data/docs/`; HTML mirrors via `wget`).
2. **Chunk** content using JTokkit's CL100K_BASE tokenizer (~900 tokens per chunk with 150-token overlap).
3. **Deduplicate** chunks by SHA-256 hash — unchanged content is skipped on re-runs.
4. **Embed** chunks with both dense vectors (semantic, from configured embedding provider) and sparse vectors (Lucene `StandardAnalyzer` tokens encoded as hashed term-frequency vectors).
5. **Upsert** to Qdrant hybrid collections.

## Fetch docs

Fetch all configured sources:

```bash
make fetch-all
```

This runs `scripts/fetch_all_docs.sh` (requires `wget`). See the canonical source ownership and edit
workflow in [pipeline-commands.md](pipeline-commands.md#scrape-fetch-html-mirrors), along with flags
(`--force`, `--include-quick`, `--no-clean`).

## Process + upload to Qdrant

```bash
make process-all
```

This runs `scripts/process_all_to_qdrant.sh`, which:

- Loads `.env`
- Requires `SPRING_PROFILE` to be exactly `local`, `dev`, or `prod`
- Requires readable `DOCS_DIR` and writable generation-specific `DOCS_SNAPSHOT_DIR`, `DOCS_PARSED_DIR`, and `DOCS_INDEX_DIR`
- Validates Qdrant connectivity and probes gateway embedding batches of 1 and 32 with `X-Tier: batch`
- Builds the app JAR (`./gradlew buildForScripts`)
- Runs the `cli` Spring profile (`DocumentProcessor`)
- Routes each doc set to the appropriate Qdrant collection (`QdrantCollectionRouter`)
- Writes both dense and sparse (BM25) vectors per chunk via gRPC (`HybridVectorService`)

### Doc set filtering (CLI)

Limit ingestion to a selected mirror path:

```bash
DOCS_SETS=java/java25-complete make process-doc-sets
```

See [pipeline-commands.md](pipeline-commands.md#doc-set-filtering) for filtering.

## Hybrid vector storage

Each ingested chunk is stored as a Qdrant point with two named vectors:

- **`dense`** — semantic embedding from the configured provider (default 2560 dimensions via Qwen3 4B)
- **`bm25`** — sparse lexical vector from Lucene `StandardAnalyzer` tokenization encoded as hashed term-frequency values with Qdrant IDF modifier

This enables hybrid retrieval: dense search captures semantic similarity while sparse search captures exact keyword matches. Sparse vectors use local hashed TF values and Qdrant applies IDF at query time. Results are fused via Reciprocal Rank Fusion (RRF).

If sparse encoding logic changes (tokenization or hashing), run a full re-ingest so stored sparse vectors stay compatible with query-time encoding.

See [pipeline-commands.md](pipeline-commands.md#hybrid-qdrant-setup) for collection layout and retrieval details.

## Deduplication markers

Deduplication is based on per-chunk SHA-256 markers in the configured environment/generation state root:

- `DOCS_INDEX_DIR` contains one file per ingested chunk hash
- `DOCS_PARSED_DIR` contains chunk text snapshots for debugging
- `DOCS_INDEX_DIR/file_*.marker` records the file size, modification time, content SHA-256,
  extractor-semantics version, and chunk hashes.

A file is skipped only when every file-level marker value, including the extractor-semantics version and
the provenance-aware ingestion fingerprint, matches the current ingestion contract.
Changing extraction behavior or source provenance therefore invalidates otherwise
identical HTML: a same-generation replacement is fully embedded and upserted before stale point IDs and local chunks are removed. A marker owned by another collection generation fails closed.
Older markers with a missing or prior extractor-semantics version are intentionally reindexed once.

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

## GitHub repository ingestion

GitHub source ingestion uses `scripts/process_github_repo.sh` and the `cli-github` Spring profile.

```bash
# Local clone path
REPO_PATH=/absolute/path/to/repository make process-github-repo

# GitHub URL (auto clone/pull)
REPO_URL=https://github.com/owner/repository make process-github-repo

# Optional cache overrides for URL mode
REPO_URL=https://github.com/owner/repository REPO_CACHE_DIR=/tmp/repo-cache make process-github-repo
REPO_URL=https://github.com/owner/repository REPO_CACHE_PATH=/tmp/repos/openai/java-chat make process-github-repo

# Sync repositories represented by the exact active environment/generation prefix
SPRING_PROFILE=local SYNC_EXISTING=1 make process-github-repo
```

GitHub ingestion stores canonical repository identity (`repoKey=owner/repository`) in payload metadata and applies strict changed-file pruning before reindexing.
In local-path mode, canonical identity is resolved from the clone's `origin` remote in `.git/config`.
See `docs/github-repository-ingestion.md` for full workflow, failure diagnostics, and retry behavior.
