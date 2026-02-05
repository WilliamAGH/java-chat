# Pipeline Commands

This page is the single command reference for documentation scrapes and ingestion.

Incremental runs are the default. "Full" runs are explicit and require clearing local state and, if applicable, resetting Qdrant.

## Scrape (Fetch HTML Mirrors)

Incremental scrape (default):

```bash
make fetch-all
```

Incremental scrape including quick landing mirrors:

```bash
make fetch-quick
```

Full scrape (force refetch all sources):

```bash
make fetch-force
```

Optional scrape flags:

```bash
./scripts/fetch_all_docs.sh --include-quick
./scripts/fetch_all_docs.sh --no-clean
./scripts/fetch_all_docs.sh --force
```

What these do:

- Incremental scrape only fetches when a mirror is missing or looks incomplete.
- Full scrape forces a refresh even if mirrors look complete.
- `--include-quick` adds small "quick" landing mirrors (useful for spot checks).
- `--no-clean` disables quarantining incomplete mirrors before refetch.

Help:

```bash
./scripts/fetch_all_docs.sh --help
```

## Ingest (Chunk, Embed, Dedupe, Index)

Incremental ingestion (default upload mode):

```bash
make process-all
```

Incremental ingestion, upload to Qdrant:

```bash
make process-upload
```

Incremental ingestion, local-only embeddings cache (no Qdrant):

```bash
make process-local
```

Incremental ingestion, local-only embeddings cache (no Qdrant):

```bash
./scripts/process_all_to_qdrant.sh --local-only
```

Incremental ingestion, upload to Qdrant:

```bash
./scripts/process_all_to_qdrant.sh --upload
```

Ingest only selected doc sets:

```bash
DOCS_SETS=java25-complete make process-doc-sets
./scripts/process_all_to_qdrant.sh --upload --doc-sets=java25-complete
./scripts/process_all_to_qdrant.sh --upload --doc-sets=java/java25-complete,spring-boot-complete
```

What "incremental" means:

- The ingestion pipeline uses content-hash markers in `data/index/` so unchanged content is skipped on re-runs.
- Embeddings are cached to `data/embeddings-cache/` in `--local-only` mode.
- In upload mode, Qdrant and the embedding provider must be reachable. Provider failures are surfaced (no runtime fallbacks).

Help:

```bash
./scripts/process_all_to_qdrant.sh --help
```

## Full Re-ingest (Force Re-embed/Re-index)

There is no single "force" flag for ingestion. A full re-ingest is explicit:

1. Stop the app and ingestion processes.
2. Clear local ingestion state so all content is re-chunked and re-embedded:

```bash
rm -rf data/index data/parsed data/embeddings-cache
```

3. If you are uploading to Qdrant, reset the target collection on the Qdrant side.
4. Re-run ingestion:

```bash
make process-all
```

## Gradle (No Scripts)

The ingestion scripts use Gradle to build a runnable JAR for the CLI profile:

```bash
./gradlew buildForScripts
```

If you want to run the CLI directly, build the JAR and then start it with the `cli` profile:

```bash
app_jar=$(ls -1 build/libs/*.jar | rg -v -- \"-plain\\.jar\" | head -n 1)
EMBEDDINGS_UPLOAD_MODE=upload java -Dspring.profiles.active=cli -jar \"$app_jar\"
```

Doc set filtering is controlled by `DOCS_SETS` (environment variable) and the docs root defaults to `data/docs` unless `DOCS_DIR` is set.

## Make Targets

Discover available targets:

```bash
make help
```
