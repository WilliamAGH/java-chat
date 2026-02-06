#!/usr/bin/env bash
# Migrate Qdrant collection from Cloud to self-hosted by streaming points
# - Uses /points/scroll on source (Cloud) and /points upsert on destination (self-hosted)
# - Preserves ids, vectors (single or named), and payloads
# - Idempotent: safe to resume; upserts overwrite existing ids
#
# Requirements:
# - jq installed (brew install jq)
# - .env with defaults OR export the SOURCE_* and DEST_* variables below
#
# Env vars (source = Cloud, dest = self-hosted):
#   SOURCE_QDRANT_HOST           # e.g., 0d50..fefce.us-west-1-0.aws.cloud.qdrant.io
#   SOURCE_QDRANT_API_KEY        # Cloud API key (keep secret)
#   SOURCE_QDRANT_COLLECTION     # e.g., java-chat
#   DEST_QDRANT_HOST=localhost   # Self-host host (default: localhost)
#   DEST_QDRANT_PORT=8087        # Self-host REST port (default: 8087 from docker-compose)
#   DEST_QDRANT_SSL=false        # Use https for dest if true
#   DEST_QDRANT_API_KEY          # Optional API key for dest (if configured)
#   DEST_QDRANT_COLLECTION       # Target collection name (default: same as source)
#   BATCH=128                    # Batch size for scroll/upsert (default: 128)
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."

# shellcheck source=lib/shell_bootstrap.sh
source "$SCRIPT_DIR/lib/shell_bootstrap.sh"
# shellcheck source=lib/env_loader.sh
source "$SCRIPT_DIR/lib/env_loader.sh"

# Load .env if present (do not echo secrets)
if [[ -f "$PROJECT_ROOT/.env" ]]; then
    preserve_process_env_then_source_file "$PROJECT_ROOT/.env"
fi

require_command jq "brew install jq"

# Inputs
SOURCE_QDRANT_HOST=${SOURCE_QDRANT_HOST:-}
SOURCE_QDRANT_API_KEY=${SOURCE_QDRANT_API_KEY:-}
SOURCE_QDRANT_COLLECTION=${SOURCE_QDRANT_COLLECTION:-${QDRANT_COLLECTION:-java-chat}}
DEST_QDRANT_HOST=${DEST_QDRANT_HOST:-localhost}
DEST_QDRANT_PORT=${DEST_QDRANT_PORT:-8087}
DEST_QDRANT_SSL=${DEST_QDRANT_SSL:-false}
DEST_QDRANT_API_KEY=${DEST_QDRANT_API_KEY:-}
DEST_QDRANT_COLLECTION=${DEST_QDRANT_COLLECTION:-$SOURCE_QDRANT_COLLECTION}
BATCH=${BATCH:-128}

if [[ -z "$SOURCE_QDRANT_HOST" || -z "$SOURCE_QDRANT_API_KEY" ]]; then
  echo "ERROR: SOURCE_QDRANT_HOST and SOURCE_QDRANT_API_KEY must be set." >&2
  exit 1
fi

SRC_BASE="https://${SOURCE_QDRANT_HOST}"
if [[ "$DEST_QDRANT_SSL" == "true" || "$DEST_QDRANT_SSL" == "1" ]]; then
  DEST_BASE="https://${DEST_QDRANT_HOST}:${DEST_QDRANT_PORT}"
else
  DEST_BASE="http://${DEST_QDRANT_HOST}:${DEST_QDRANT_PORT}"
fi

# Helpers for auth headers
src_curl=(curl -sS -H "api-key: ${SOURCE_QDRANT_API_KEY}")
if [[ -n "$DEST_QDRANT_API_KEY" ]]; then
  dest_auth=( -H "api-key: ${DEST_QDRANT_API_KEY}" )
else
  dest_auth=()
fi

# Check source and get stats
echo "Checking source collection..."
SRC_INFO=$("${src_curl[@]}" "${SRC_BASE}/collections/${SOURCE_QDRANT_COLLECTION}" || true)
if [[ -z "$SRC_INFO" || "$(echo "$SRC_INFO" | jq -r '.status')" != "ok" ]]; then
  echo "ERROR: Unable to reach source collection at ${SRC_BASE}/collections/${SOURCE_QDRANT_COLLECTION}" >&2
  exit 1
fi
SRC_POINTS=$(echo "$SRC_INFO" | jq -r '.result.points_count // 0')
VEC_SIZE=$(echo "$SRC_INFO" | jq -r '.result.config.params.vectors.size // .result.vectors.size // empty')
VEC_DIST=$(echo "$SRC_INFO" | jq -r '.result.config.params.vectors.distance // .result.vectors.distance // "Cosine"')

echo "Source points: ${SRC_POINTS} | vector size: ${VEC_SIZE:-unknown} | distance: ${VEC_DIST}"

# Ensure destination collection exists (attempt create if missing and size known)
DEST_GET=$(curl -sS "${DEST_BASE}/collections/${DEST_QDRANT_COLLECTION}" "${dest_auth[@]}" || true)
if [[ -z "$DEST_GET" || "$(echo "$DEST_GET" | jq -r '.status // empty')" != "ok" ]]; then
  if [[ -n "$VEC_SIZE" ]]; then
    echo "Creating destination collection ${DEST_QDRANT_COLLECTION}..."
    CREATE_PAYLOAD=$(jq -n --argjson size "$VEC_SIZE" --arg dist "$VEC_DIST" '{ vectors: { size: $size, distance: $dist } }')
    HTTP=$(curl -sS -o /dev/null -w "%{http_code}" -X POST "${DEST_BASE}/collections/${DEST_QDRANT_COLLECTION}" -H 'Content-Type: application/json' "${dest_auth[@]}" -d "$CREATE_PAYLOAD" || true)
    if [[ "$HTTP" != "200" && "$HTTP" != "201" && "$HTTP" != "202" ]]; then
      echo "WARN: Failed to create destination collection (HTTP $HTTP). Ensure it exists and vector size matches." >&2
    fi
  else
    echo "WARN: Destination collection not found and vector size unknown; please create it manually first." >&2
  fi
fi

# Migration loop
OFFSET=null
COPIED=0
START_TS=$(date +%s)

echo "Starting migration from ${SOURCE_QDRANT_COLLECTION} -> ${DEST_QDRANT_COLLECTION}"
while :; do
  REQ=$(jq -n --argjson off "$OFFSET" --argjson limit "$BATCH" '{ limit: $limit, with_payload: true, with_vector: true, offset: $off }')
  RESP=$("${src_curl[@]}" -H 'Content-Type: application/json' -X POST "${SRC_BASE}/collections/${SOURCE_QDRANT_COLLECTION}/points/scroll" -d "$REQ" || true)
  # Basic validation
  OK=$(echo "$RESP" | jq -r '.status // empty')
  if [[ "$OK" != "ok" ]]; then
    echo "ERROR: Scroll failed. Response:" >&2
    echo "$RESP" | head -200 >&2
    exit 1
  fi

  POINTS_JSON=$(echo "$RESP" | jq -c '.result.points')
  COUNT=$(echo "$POINTS_JSON" | jq 'length')
  if [[ "$COUNT" -eq 0 ]]; then
    echo "Done. No more points to migrate."
    break
  fi

  # Build upsert payload; preserve either .vector or .vectors
  UPSERT=$(echo "$POINTS_JSON" | jq -c '{ points: (map( if has("vector") then { id, vector, payload: (.payload // {}) } else { id, vectors, payload: (.payload // {}) } end )) }')

  # Upsert with retries
  ATTEMPTS=0; MAX_ATTEMPTS=3
  while :; do
    ATTEMPTS=$((ATTEMPTS+1))
    HTTP=$(curl -sS -o /dev/null -w "%{http_code}" -X PUT "${DEST_BASE}/collections/${DEST_QDRANT_COLLECTION}/points?wait=true" -H 'Content-Type: application/json' "${dest_auth[@]}" -d "$UPSERT" || true)
    if [[ "$HTTP" == "200" || "$HTTP" == "202" ]]; then
      break
    fi
    if [[ "$ATTEMPTS" -ge "$MAX_ATTEMPTS" ]]; then
      echo "ERROR: Upsert failed after ${MAX_ATTEMPTS} attempts (HTTP $HTTP). Aborting." >&2
      exit 1
    fi
    sleep 1
  done

  COPIED=$((COPIED + COUNT))
  ELAPSED=$(( $(date +%s) - START_TS ))
  RATE=0
  if [[ "$ELAPSED" -gt 0 ]]; then RATE=$(python3 - <<PY 2>/dev/null || echo 0
import sys
c=int(sys.argv[1]); e=int(sys.argv[2])
print(int(c/e)) if e>0 else print(0)
PY
 "$COPIED" "$ELAPSED"); fi
  PCT=0
  if [[ "$SRC_POINTS" -gt 0 ]]; then PCT=$(python3 - <<PY 2>/dev/null || echo 0
import sys
c=int(sys.argv[1]); t=int(sys.argv[2])
print(f"{(c/t)*100:.2f}") if t>0 else print("0.00")
PY
 "$COPIED" "$SRC_POINTS"); fi
  echo "→ Copied batch ${COUNT}; total ${COPIED}/${SRC_POINTS} (${PCT}%), ~${RATE} pts/sec"

  # Next offset
  OFFSET=$(echo "$RESP" | jq -c '.result.next_page_offset')
  if [[ "$OFFSET" == "null" || -z "$OFFSET" ]]; then
    echo "Reached end of source points. Migration complete."
    break
  fi
  # Gentle throttle
  sleep 0.15
done

# Verify destination
DEST_INFO=$(curl -sS "${DEST_BASE}/collections/${DEST_QDRANT_COLLECTION}" "${dest_auth[@]}" || true)
if [[ -n "$DEST_INFO" ]]; then
  DEST_POINTS=$(echo "$DEST_INFO" | jq -r '.result.points_count // 0')
  echo "Destination points_count: ${DEST_POINTS}"
fi

echo "✅ Migration finished."

