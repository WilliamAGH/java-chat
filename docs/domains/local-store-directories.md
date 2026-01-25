# Local Store Directories

LocalStoreService persists document snapshots, parsed chunks, and ingest markers on the filesystem.
It creates the configured directories on startup; if the paths are invalid or not writable, the app
fails to start and endpoints like `/api/guided/toc` return 500.

## Configuration
- `DOCS_SNAPSHOT_DIR` → raw HTML snapshots (default `data/snapshots`)
- `DOCS_PARSED_DIR` → parsed chunk text (default `data/parsed`)
- `DOCS_INDEX_DIR` → ingest hash markers (default `data/index`)

## Container requirement
When running as a non-root user, set `DOCS_*` to a writable path (for example `/app/data/...`)
and ensure the directories exist and are owned by the runtime user.
