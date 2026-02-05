# Documentation ingestion (RAG indexing)

Java Chat includes scripts and a CLI profile to mirror upstream documentation into `data/docs/` and ingest it into the vector store with content-hash deduplication.

Command reference:

- See `docs/pipeline-commands.md` for the supported scrape and ingestion commands, flags, and full vs incremental behavior.

## Pipeline overview

1) **Fetch** documentation into `data/docs/` (HTML mirrors).
2) **Process** docs into chunks + embeddings.
3) **Deduplicate** chunks by SHA‑256 hash.
4) **Upload** embeddings to Qdrant (upload mode) or **cache** embeddings locally (local-only mode).

## Fetch docs

Fetch all configured sources:

```bash
make fetch-all
```

This runs `scripts/fetch_all_docs.sh` (requires `wget`). Source URLs live in:

- `src/main/resources/docs-sources.properties`

Optional flags:

```bash
./scripts/fetch_all_docs.sh --include-quick
```

## Process + upload to Qdrant

Run the processor:

```bash
make process-all
```

This runs `scripts/process_all_to_qdrant.sh`, which:

- Loads `.env`
- Builds the app JAR (`./gradlew buildForScripts`)
- Runs the `cli` Spring profile (`com.williamcallahan.javachat.cli.DocumentProcessor`)

### Modes

- Default: `--upload` (uploads to Qdrant)
- Optional: `--local-only` (caches embeddings under `data/embeddings-cache/`)

```bash
./scripts/process_all_to_qdrant.sh --local-only
./scripts/process_all_to_qdrant.sh --upload
```

### Doc set filtering (CLI)

Limit ingestion to specific doc sets by id or path:

```bash
./scripts/process_all_to_qdrant.sh --upload --doc-sets=java25-complete
./scripts/process_all_to_qdrant.sh --upload --doc-sets=java/java25-complete,spring-boot-complete
```

## Deduplication markers

Deduplication is based on per-chunk SHA‑256 markers stored locally:

- `data/index/` contains one file per ingested chunk hash
- `data/parsed/` contains chunk text snapshots used for local fallback search and debugging
- `data/index/file_*.marker` records file-level fingerprints and chunk hashes so re-runs can skip unchanged files and prune stale vectors when a source file changes

See [local store directories](domains/local-store-directories.md) for details.

## Ingest via HTTP API

Ingest a local docs directory (must be under `data/docs/`):

```bash
curl -sS -X POST "http://localhost:8085/api/ingest/local?dir=data/docs&maxFiles=50000"
```

Run a small remote crawl (dev/debug):

```bash
curl -sS -X POST "http://localhost:8085/api/ingest?maxPages=100"
```

## Monitoring

There are helper scripts in `scripts/`:

- `scripts/monitor_progress.sh` (simple log-based view)
- `scripts/monitor_indexing.sh` (dashboard view; requires `jq` and `bc`)
