# Documentation ingestion (RAG indexing)

Java Chat includes scripts and a CLI profile to mirror upstream documentation into `data/docs/` and ingest it into the vector store with content-hash deduplication.

## Pipeline overview

1) **Fetch** documentation into `data/docs/` (HTML mirrors).
2) **Process** docs into chunks + embeddings.
3) **Deduplicate** chunks by SHA‑256 hash.
4) **Upload** embeddings to Qdrant (or cache locally).

## Fetch docs

Fetch all configured sources:

```bash
make fetch-all
```

This runs `scripts/fetch_all_docs.sh` (requires `wget`). Source URLs live in:

- `src/main/resources/docs-sources.properties`

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

## Deduplication markers

Deduplication is based on per-chunk SHA‑256 markers stored locally:

- `data/index/` contains one file per ingested chunk hash
- `data/parsed/` contains chunk text snapshots used for local fallback search and debugging

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
