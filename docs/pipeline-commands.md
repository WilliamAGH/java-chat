# Pipeline Commands

Command reference for documentation scraping, embedding, and ingestion into Qdrant.

Incremental runs are the default — unchanged content is skipped via SHA-256 hash markers.
"Full" runs publish into fresh environment- and generation-specific collections and state roots. Existing
collections and state remain read-only for rollback; never clear or rewrite them to change vector dimensions.

---

## Quick reference

| What | Incremental | Full |
|---|---|---|
| **Scrape** (mirror HTML) | `make fetch-all` | `make fetch-force` |
| **Ingest** (chunk → embed → upload) | `make process-all` | Configure fresh generation collections/state, then `make process-all` ([details](#full-re-ingest)) |
| **Both** | `make full-pipeline` | Configure fresh generation collections/state, then `make fetch-force` and `make process-all` |
| **Ingest subset** | Set `DOCS_SETS` to a mirror path, then run `make process-doc-sets` | — |
| **Ingest GitHub repo** | `REPO_URL=https://github.com/owner/repo make process-github-repo` | — |

---

## Scrape (fetch HTML mirrors)

The scrape phase mirrors upstream documentation into the configured `DOCS_DIR` (default `data/docs/`) using `wget`.

### Source ownership

`scripts/fetch_all_docs.sh` directly owns the URLs and policies it executes. It calls the reusable fetch and
publication primitives with named, source-specific values. JVM ingestion keeps only the smaller citation and
provenance values it consumes at runtime.

Structured sitemap and navigation discovery are implemented directly by `scripts/documentation_seed.py`.
Discovered URLs must match the exact source prefix and are deterministically mapped onto the fetch URL before
`wget` receives the seed list. Seeded fetches reject redirects and every nonzero `wget` status, reconcile stale
HTML against exact current seed paths before fetching, and verify exact path coverage afterward. Recursive mirrors
convert local HTML links, omit page requisites, reject known binary asset extensions, and remain bounded by
`--no-parent`.

### Make targets

```bash
make fetch-all          # Incremental: skip sources that already look complete
make fetch-quick        # Incremental + include small "quick" landing mirrors
make fetch-force        # Full: force refetch even if mirrors look complete
```

### Script flags

```bash
./scripts/fetch_all_docs.sh [--include-quick] [--no-clean] [--force] [--doc-sets=SOURCE_IDENTIFIER,...]
```

| Flag | Effect |
|---|---|
| `--include-quick` | Also fetch small landing-page mirrors (Spring Framework/AI quick sets) |
| `--no-clean` | Do not quarantine incomplete mirrors before refetching |
| `--force` | Refresh all sources even if they look complete |
| `--doc-sets=SOURCE_IDENTIFIER,...` | Fetch exactly the selected official source identifiers; omit legacy and quick sources |
| `--help` | Show usage |

Selectors are exact; blank, duplicate, and unknown tokens are rejected before any fetch begins. The fetch script's
source dispatch is the owner of accepted identifiers.

```bash
./scripts/fetch_all_docs.sh --doc-sets=kotlin,java/java25-complete
```

### What "incremental" means for scraping

- Each source fetches into a source/version-specific sibling staging root on the same filesystem as `DOCS_DIR`.
- Only a staging root with the same source identity and passing integrity checks is resumed; malformed or stale attempts are quarantined.
- Publication requires a successful fetch, the source-specific HTML minimum, the expected stable-version identity, and clean paths with no preview, temporary, malformed, query-duplicate, or non-content files.
- A validated stage atomically replaces its canonical root. The previous canonical root is preserved until replacement succeeds, and quarantine data remains available through downstream verification.
- No partial mirror is published as a successful canonical source.
- Java API sources use a deterministic Python seed generator (`scripts/oracle_javadoc_seed.py`) to avoid incomplete recursive crawls.

---

## Ingest (chunk, embed, deduplicate, upload)

The ingestion phase processes local HTML/PDF mirrors into chunked, embedded vectors and writes them to Qdrant.

### Make targets

```bash
make process-all        # Incremental ingestion, upload to Qdrant (default)
make process-doc-sets   # Ingest selected doc sets only (requires DOCS_SETS=...)
make full-pipeline      # Scrape + ingest in one step
make process-github-repo  # GitHub repo ingestion (REPO_PATH / REPO_URL / SYNC_EXISTING)
```

### Script flags

```bash
./scripts/process_all_to_qdrant.sh [--doc-sets=SET1,SET2]
```

| Flag | Effect |
|---|---|
| `--doc-sets=...` | Comma-separated doc set paths (or IDs for non-Java sets) to process (see [doc set filtering](#doc-set-filtering)) |
| `--help` | Show usage |

## GitHub repository ingestion

Use the dedicated GitHub ingestion script via Make:

```bash
# Local clone path
REPO_PATH=/absolute/path/to/repository make process-github-repo

# GitHub URL (auto clone/pull cache)
REPO_URL=https://github.com/owner/repository make process-github-repo

# Sync repositories represented by the exact active github-${SPRING_PROFILE}-qwen3-embedding-4b-2560- prefix
SPRING_PROFILE=local SYNC_EXISTING=1 make process-github-repo
```

Advanced option:

```bash
REPO_URL=https://github.com/owner/repository REPO_CACHE_DIR=/tmp/repo-cache make process-github-repo
REPO_URL=https://github.com/owner/repository REPO_CACHE_PATH=/tmp/repos/openai/java-chat make process-github-repo
```

GitHub ingestion details (canonical `repoKey`, collection naming, incremental changed-file behavior, and batch sync rules) are documented in [github-repository-ingestion.md](github-repository-ingestion.md).
GitHub ingestion runs in headless CLI mode (`spring.main.web-application-type=none`) and does not bind a web server port.

### Doc set filtering

Limit ingestion to specific doc sets by path. Non-Java sets also accept short IDs; Java API sets use their exact mirror paths:

```bash
DOCS_SETS=java/java25-complete make process-doc-sets

# Canonical Spring roots remain separate
./scripts/process_all_to_qdrant.sh --doc-sets=spring-framework-reference,spring-framework-api
```

Canonical framework doc sets include:

| ID | Content |
|---|---|
| `spring-framework-reference` | Spring Framework reference |
| `spring-framework-api` | Spring Framework Javadocs |
| `spring-ai-reference` | Spring AI stable reference |
| `spring-ai-api-stable` | Spring AI stable API |
| `books` | PDF books |

`DOCS_SETS=all` selects every canonical full source. Quick Spring mirrors are explicitly opt-in and cannot be combined with canonical full sources. A selected source that is missing, unreadable, or empty fails the CLI.

### What "incremental" means for ingestion

- Per-chunk SHA-256 hash markers in the configured generation-specific index root track what has been processed.
- A file is skipped only when its file-level marker has the same size, mtime, content SHA-256,
  extractor-semantics version, and provenance-aware ingestion fingerprint as the current ingestion contract.
- File-level markers (`DOCS_INDEX_DIR/file_*.marker`) include those values plus the ingested chunk hashes.
  A source-content, provenance, or extractor-semantics change triggers strict stale-vector and parsed-chunk
  same-collection replacement after the complete successor has been embedded and upserted. A marker owned by
  another or unknown collection generation fails closed and cannot delete or overwrite prior-generation state.

---

## Hybrid Qdrant setup

Ingestion writes to four Qdrant collections, each containing **dense vectors** (semantic embeddings) and **sparse vectors** (BM25 lexical tokens). Retrieval uses Qdrant's Query API with **Reciprocal Rank Fusion (RRF)** across all collections simultaneously.

### Collections

| Collection | Default name | Content |
|---|---|---|
| Books | `java-chat-${SPRING_PROFILE}-qwen3-embedding-4b-2560-books` | PDF books |
| Docs | `java-chat-${SPRING_PROFILE}-qwen3-embedding-4b-2560-docs` | Java API, Spring reference/API, Oracle Javase |
| Articles | `java-chat-${SPRING_PROFILE}-qwen3-embedding-4b-2560-articles` | IBM articles, JetBrains blog posts |
| PDFs | `java-chat-${SPRING_PROFILE}-qwen3-embedding-4b-2560-pdfs` | Non-book PDFs (reserved) |

Each collection has two named vector spaces:

- **`dense`** — 2,560-dimensional Qwen3-Embedding-4B vectors
- **`bm25`** — sparse lexical vectors computed via Lucene `StandardAnalyzer` tokenization and hashed term-frequency values, with Qdrant IDF modifier

### How retrieval works

1. Query text is embedded (dense) and tokenized (sparse BM25)
2. Both vectors are sent to all four collections in parallel as Qdrant prefetch queries
3. Qdrant fuses results per-collection via RRF
4. The app merges cross-collection results, deduplicates by point UUID, and returns the top-K by fused score
5. Results are reranked before being passed to the LLM as retrieval context

### Collection auto-creation

On startup (non-test profile), `QdrantIndexInitializer` ensures all four collections exist with the correct dense vector dimensions and sparse vector configuration, and creates payload indexes for filtering fields (`url`, `hash`, `docSet`, `sourceName`, etc.). Reachable schema or dimension mismatches cause startup failure; transient Qdrant unavailability (transport errors, `5xx`, `429`) defers initialization to a pending state that is retried without failing startup.

### Environment variables

See [configuration.md](configuration.md#qdrant) for the full list of Qdrant environment variables, including collection name overrides and hybrid search tuning.

---

## Full re-ingest

There is no in-place generation conversion. A full re-ingest creates new environment/generation collection names and uses the matching new state roots:

1. Stop the app and any running ingestion processes.

2. Configure fresh state roots, for example local:

```bash
export DOCS_SNAPSHOT_DIR="$PWD/data/qwen3-embedding-4b-2560/local/snapshots"
export DOCS_PARSED_DIR="$PWD/data/qwen3-embedding-4b-2560/local/parsed"
export DOCS_INDEX_DIR="$PWD/data/qwen3-embedding-4b-2560/local/index"
export QDRANT_COLLECTION_BOOKS=java-chat-local-qwen3-embedding-4b-2560-books
export QDRANT_COLLECTION_DOCS=java-chat-local-qwen3-embedding-4b-2560-docs
export QDRANT_COLLECTION_ARTICLES=java-chat-local-qwen3-embedding-4b-2560-articles
export QDRANT_COLLECTION_PDFS=java-chat-local-qwen3-embedding-4b-2560-pdfs
```

3. Start the generation-specific Qdrant 1.18.3 Compose project. Preserve old collections and state read-only for rollback.

4. Re-run ingestion:

```bash
export SPRING_PROFILE=local
export DOCS_DIR=/absolute/path/to/documentation-mirror
make process-all
```

Before launching the non-web CLI, `process_all_to_qdrant.sh` requires an exact `SPRING_PROFILE` of `local`,
`dev`, or `prod`, a readable `DOCS_DIR`, and writable `DOCS_SNAPSHOT_DIR`, `DOCS_PARSED_DIR`, and
`DOCS_INDEX_DIR`. It exports `DOCS_DIR` to the child process and preserves
`spring.main.web-application-type=none`.

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

To run the CLI directly, load the same guarded environment contract used by the scripts:

```bash
app_jar=$(ls -1 build/libs/*.jar | grep -v -- "-plain.jar" | head -1)
set -a
source .env
set +a

# With doc set filtering
DOCS_DIR=/absolute/path/to/docs DOCS_SETS=spring-framework-reference \
  java -Dspring.profiles.active=cli -jar "$app_jar" \
  --spring.main.web-application-type=none --server.port=0
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
make process-doc-sets      # Ingest selected doc sets (DOCS_SETS=...)
make process-github-repo   # GitHub repo ingest/sync (REPO_PATH or REPO_URL or SYNC_EXISTING=1)
make full-pipeline         # Scrape + ingest
make compose-up            # Start local Qdrant via Docker Compose
make compose-down          # Stop Qdrant
make compose-logs          # Tail Qdrant logs
make health                # Check app health endpoint
make ingest                # HTTP ingest (first 1000 docs)
```
