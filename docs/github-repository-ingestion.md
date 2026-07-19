# GitHub Repository Ingestion

Java Chat supports GitHub source-code ingestion into dedicated hybrid Qdrant collections, with canonical repository identity and incremental updates.

## Goals

- Use one canonical repository identity for all ingestion entrypoints.
- Prevent local-path and URL ingestion from creating duplicate variants of the same repo.
- Reindex only changed files on reruns.
- Sync only collections under the exact active environment/generation prefix.

## Canonical repository identity

The canonical owner for repository identity and collection-name validation is
[`scripts/lib/github_identity.sh`](../scripts/lib/github_identity.sh), specifically
`require_canonical_collection_name`.

Every ingestion entrypoint asks that owner to validate the repository before processing. The result
is one environment- and generation-specific collection for each GitHub repository: local-clone, URL, and sync ingestion reject a
collection name that does not match the source repository. Ingested content retains the repository
metadata required for incremental synchronization.

## Commands

All GitHub ingestion commands run in headless CLI mode (`spring.main.web-application-type=none`), so they do not bind an HTTP port and can run concurrently with the main app or other ingestion jobs. Each CLI invocation runs in its own short-lived JVM process and exits automatically after completion.

Runtime configuration precedence for this pipeline:

1. CLI flags / inline Make variables
2. Exported shell environment variables
3. `.env` values (when `.env` exists)
4. Script defaults

### Ingest from a local clone

```bash
REPO_PATH=/absolute/path/to/repository make process-github-repo
```

When `REPO_PATH` mode is used, repository identity is resolved from the local clone's `origin`
remote (`.git/config`). `REPO_URL` is optional and only needed to override metadata explicitly.

### Ingest from a GitHub URL

```bash
REPO_URL=https://github.com/owner/repository make process-github-repo
```

URL mode clones or refreshes the repository in cache (`data/repos/github` by default).

Optional cache override:

```bash
REPO_URL=https://github.com/owner/repository REPO_CACHE_DIR=/tmp/repo-cache make process-github-repo
```

Per-repo exact cache path override:

```bash
REPO_URL=https://github.com/owner/repository REPO_CACHE_PATH=/tmp/repos/openai/java-chat make process-github-repo
```

Default cache root is the project-local path `data/repos/github`.

### Batch sync existing GitHub collections

```bash
SYNC_EXISTING=1 make process-github-repo
```

Batch sync flow:

1. Require `SPRING_PROFILE` to be exactly `local`, `dev`, or `prod`.
2. Discover only Qdrant collections prefixed with `github-${SPRING_PROFILE}-qwen3-embedding-4b-2560-`.
3. Read the source repository and indexed revision from each collection's payload metadata.
4. Verify collection identity through `require_canonical_collection_name`.
5. Resolve the source repository's remote HEAD commit.
6. Reingest only collections whose source has changed.

## Incremental update behavior

Per file, ingestion stores marker metadata in the active generation's configured index root:

- file size
- mtime
- content fingerprint (SHA-256)
- ingested chunk hashes

On rerun:

- unchanged file + sufficient Qdrant points: skipped
- changed file: embed and upsert the complete replacement, then remove stale same-collection point IDs and local chunks
- marker from another or unknown collection generation: fail without vector, marker, or parsed-state mutation

An embedding or upsert failure leaves the prior complete page and its marker intact. The active marker is replaced only after the replacement and local cleanup succeed.

## Failure diagnostics and retry behavior

- GitHub ingestion fails fast when embedding or vector writes fail.
- Preflight validates the gateway model alias and `X-Tier: batch` embedding batches of 1 and 32 before ingestion starts.
- When null/invalid vectors are detected, diagnostics now state likely causes explicitly:
  - wrong endpoint (must resolve to `/v1/embeddings`)
  - non-embedding model
  - provider payload bug
- Batch ingestion requests perform one provider attempt so a failed batch cannot silently become partial success.
- On terminal failure, `scripts/process_github_repo.sh` prints a failure summary extracted from `process_github_repo.log`, including:
  - failure source classification (`AI Embedding API`, `Qdrant API`, `GitHub API`, or `Application/Unknown`),
  - explicit rate-limit diagnosis (`No rate limit detected` or detected API + evidence),
  - the last root-cause headline (for example, file URL and failing embedding batch),
  - the final exception cause chain (`Caused by`),
  - recent retry/error trace lines,
  - a compact tail of significant error lines.

## Collection and payload indexes

GitHub payload-index behavior is owned by
[`scripts/lib/github_identity.sh`](../scripts/lib/github_identity.sh), specifically
`ensure_github_payload_indexes`. A missing collection clones only the active environment's
`QDRANT_COLLECTION_DOCS` schema. Both existing and newly cloned collections must validate as named
`dense` 2,560/Cosine plus `bm25`/IDF with on-disk payloads before ingestion.

## Environment variables

Common optional variables for GitHub ingestion:

- `REPO_PATH`
- `REPO_URL`
- `REPO_CACHE_DIR`
- `REPO_CACHE_PATH` (single-repo URL mode only)
- `SYNC_EXISTING`
- `SPRING_PROFILE` (exactly `local`, `dev`, or `prod`)
- `QDRANT_COLLECTION_DOCS` (active environment/generation schema source)
- `DOCS_SNAPSHOT_DIR` (matching environment/generation snapshot state root)
- `DOCS_PARSED_DIR` (matching environment/generation parsed state root)
- `DOCS_INDEX_DIR` (matching environment/generation marker state root)

Qdrant connectivity and embedding provider variables follow the existing pipeline conventions in `docs/configuration.md`.
