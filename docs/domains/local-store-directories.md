# Local Store Directories

LocalStoreService persists document snapshots, parsed chunks, and ingest markers on the filesystem.
It creates the configured directories on startup; if the paths are invalid or not writable, the app
fails to start (so endpoints are unavailable). If permissions change after startup, affected endpoints
may return 500.

## Configuration
- `DOCS_SNAPSHOT_DIR` → raw HTML snapshots
- `DOCS_PARSED_DIR` → parsed chunk text
- `DOCS_INDEX_DIR` → ingest hash markers

Container deployments use `/app/data/qwen3-embedding-4b-2560/{local|dev|prod}/{snapshots,parsed,index}`.
The path segment must match the exact `SPRING_PROFILE`. Prior-generation state stays read-only for rollback.

## Container requirement
When running as UID 1001, configure all `DOCS_*` paths under the matching generation root and ensure the
directories exist and are owned by that runtime user. Documentation mirrors remain read-only.
