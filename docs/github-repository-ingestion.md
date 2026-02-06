# GitHub Repository Ingestion

Java Chat supports GitHub source-code ingestion into dedicated hybrid Qdrant collections, with canonical repository identity and incremental updates.

## Goals

- Use one canonical repository identity for all ingestion entrypoints.
- Prevent local-path and URL ingestion from creating duplicate variants of the same repo.
- Reindex only changed files on reruns.
- Provide batch sync for all existing `github-*` collections.

## Canonical repository identity

Every repository is identified by canonical `owner/repository` (lowercase).

Derived values:

- `repoKey`: `owner/repository`
- `repoUrl`: `https://github.com/owner/repository`
- canonical collection name:
  - default: `github-owner-repository` (when owner/repo use only `[a-z0-9-]`)
  - collision-safe encoded form with hash suffix for punctuation variants (for example underscores/dots)

The ingestion pipeline stores this identity in payload metadata:

- `repoKey`
- `repoUrl`
- `repoOwner`
- `repoName`
- `repoBranch`
- `commitHash`

`docSet` for GitHub source files is `github/owner/repository`.

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

1. Discover all Qdrant collections prefixed with `github-`.
2. Read `repoUrl`/`repoKey` and `commitHash` payload metadata from each collection.
3. Resolve remote HEAD commit (`git ls-remote <repoUrl> HEAD`).
4. Reingest only collections where remote HEAD differs from stored `commitHash`.

## Incremental update behavior

Per file, ingestion stores marker metadata in `data/index/file_*.marker`:

- file size
- mtime
- content fingerprint (SHA-256)
- ingested chunk hashes

On rerun:

- unchanged file + sufficient Qdrant points: skipped
- changed file: strict prune and reindex

Strict prune removes:

- stale points for that file URL in the target collection
- old chunk hash markers
- old parsed chunk text files
- old file marker

Then the file is chunked and upserted again.

## Failure diagnostics and retry behavior

- GitHub ingestion fails fast when embedding or vector writes fail.
- Preflight now validates remote embedding payload quality before ingestion starts:
  - plain text probe
  - code-like multiline probe
  - probe failures include explicit endpoint/model context and payload anomaly details
  - plain-text failures stop immediately
  - code-like failures stop by default; set `EMBEDDING_CODE_PROBE_MODE=warn` to continue explicitly
- When null/invalid vectors are detected, diagnostics now state likely causes explicitly:
  - wrong endpoint (must resolve to `/v1/embeddings`)
  - non-embedding model
  - provider payload bug
- Remote embedding calls use exponential backoff retries for transient HTTP errors (for example `429`, `5xx`, and provider-side `400: null` gateway responses).
- Remote embedding response-shape failures that are typically transient (for example null/missing embedding entries) also retry with exponential backoff before failing terminally.
- On terminal failure, `scripts/process_github_repo.sh` prints a failure summary extracted from `process_github_repo.log`, including:
  - failure source classification (`AI Embedding API`, `Qdrant API`, `GitHub API`, or `Application/Unknown`),
  - explicit rate-limit diagnosis (`No rate limit detected` or detected API + evidence),
  - the last root-cause headline (for example, file URL and failing embedding batch),
  - the final exception cause chain (`Caused by`),
  - recent retry/error trace lines,
  - a compact tail of significant error lines.

## Collection and payload indexes

GitHub collections are created (if missing) with hybrid vector schema copied from reference collection (`java-docs` by default), then payload indexes are ensured for:

- core fields: `url`, `hash`, `chunkIndex`, `docSet`, `docPath`, `sourceName`, `sourceKind`, `docVersion`, `docType`
- GitHub fields: `filePath`, `language`, `repoUrl`, `repoOwner`, `repoName`, `repoKey`, `repoBranch`, `commitHash`, `license`, `repoDescription`

## Environment variables

Common optional variables for GitHub ingestion:

- `REPO_PATH`
- `REPO_URL`
- `REPO_CACHE_DIR`
- `REPO_CACHE_PATH` (single-repo URL mode only)
- `SYNC_EXISTING`
- `QDRANT_REFERENCE_COLLECTION` (default `java-docs`)

Qdrant connectivity and embedding provider variables follow the existing pipeline conventions in `docs/configuration.md`.
