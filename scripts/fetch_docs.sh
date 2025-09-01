#!/usr/bin/env bash
set -euo pipefail

# Fetch Spring and Java docs and verify basic completeness thresholds.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="${OUT:-$ROOT_DIR/data/docs}"
LOG_FILE="$OUT_DIR/_fetch.log"
MANIFEST_FILE="$OUT_DIR/_manifest.json"

INCLUDE_JAVADOC="${INCLUDE_JAVADOC:-true}"
# Toggle fetching single-page refs known to 404 on some sites
TRY_FRAME_SINGLE="${TRY_FRAME_SINGLE:-false}"
TRY_SAI_SINGLE="${TRY_SAI_SINGLE:-false}"
CLEAN="false"

for arg in "$@"; do
  case "$arg" in
    --no-javadoc)
      INCLUDE_JAVADOC="false"
      ;;
    --only-javadoc)
      INCLUDE_JAVADOC="true"
      ;;
    --clean)
      CLEAN="true"
      ;;
  esac
done

mkdir -p "$OUT_DIR" "$OUT_DIR/spring-boot" "$OUT_DIR/spring-framework" "$OUT_DIR/spring-ai" "$OUT_DIR/java"

timestamp() { date -u +%Y-%m-%dT%H:%M:%SZ; }

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
}

require_cmd curl
require_cmd wget

if [ "$CLEAN" = "true" ]; then
  rm -rf "$OUT_DIR" && mkdir -p "$OUT_DIR"
  mkdir -p "$OUT_DIR/spring-boot" "$OUT_DIR/spring-framework" "$OUT_DIR/spring-ai" "$OUT_DIR/java"
fi

touch "$LOG_FILE"
exec 3>>"$LOG_FILE"
echo "[$(timestamp)] Starting fetch (INCLUDE_JAVADOC=$INCLUDE_JAVADOC)" >&3

fetch_file() {
  local url="$1"; shift
  local dest="$1"; shift
  echo "[$(timestamp)] curl: $url -> $dest" >&3
  curl -L --fail --show-error --silent "$url" -o "$dest" 2>>"$LOG_FILE"
}

mirror_site() {
  local url="$1"; shift
  local dest="$1"; shift
  echo "[$(timestamp)] wget mirror: $url -> $dest" >&3
  mkdir -p "$dest"
  wget -e robots=off --no-verbose \
    --mirror --convert-links --page-requisites --adjust-extension --no-parent \
    --wait=1 --random-wait \
    --directory-prefix="$dest" "$url" >>"$LOG_FILE" 2>&1
}

ensure_min_size() {
  local path="$1"; shift
  local min_bytes="$1"; shift
  if [ ! -f "$path" ]; then
    echo "[$(timestamp)] FAIL: missing file $path" >&3
    return 1
  fi
  local size
  size=$(wc -c < "$path" | tr -d ' ')
  if [ "$size" -lt "$min_bytes" ]; then
    echo "[$(timestamp)] FAIL: $path too small ($size < $min_bytes)" >&3
    return 1
  fi
  echo "[$(timestamp)] OK: $path size=$size" >&3
  return 0
}

ensure_html_count() {
  local dir="$1"; shift
  local min_count="$1"; shift
  if [ ! -d "$dir" ]; then
    echo "[$(timestamp)] FAIL: missing dir $dir" >&3
    return 1
  fi
  local count
  # shellcheck disable=SC2012
  count=$(find "$dir" -type f -name "*.html" 2>/dev/null | wc -l | tr -d ' ')
  if [ "$count" -lt "$min_count" ]; then
    echo "[$(timestamp)] FAIL: html count in $dir too small ($count < $min_count)" >&3
    return 1
  fi
  echo "[$(timestamp)] OK: $dir html_count=$count" >&3
  return 0
}

total_kb() {
  local p="$1"
  du -sk "$p" 2>/dev/null | awk '{print $1}'
}

# Optional sitemap-based fetch for Spring reference docs
# Args: name, url_prefix, destination_dir
sitemap_fetch_reference() {
  local name="$1"; shift
  local prefix="$1"; shift
  local dest="$1"; shift
  local tmpdir
  tmpdir=$(mktemp -d)
  echo "[$(timestamp)] sitemap fetch: $name -> $dest (prefix=$prefix)" >&3
  mkdir -p "$dest"
  curl -s https://docs.spring.io/sitemap_index.xml \
    | grep -oE 'https?://[^<]+' > "$tmpdir/sitemaps.txt" || true
  : > "$tmpdir/urls.txt"
  if [ -s "$tmpdir/sitemaps.txt" ]; then
    while read -r sm; do
      curl -s "$sm" | grep -oE 'https?://[^<]+' >> "$tmpdir/urls.txt" || true
    done < "$tmpdir/sitemaps.txt"
  fi
  if [ -s "$tmpdir/urls.txt" ]; then
    grep -E "^${prefix//\//\/}" "$tmpdir/urls.txt" | sort -u > "$tmpdir/urls_filtered.txt" || true
    if [ -s "$tmpdir/urls_filtered.txt" ]; then
      cat "$tmpdir/urls_filtered.txt" \
        | xargs -n1 -P8 -I{} wget -x -c --convert-links --adjust-extension --page-requisites --wait=1 --random-wait -q {}
      if [ -d "docs.spring.io" ]; then
        rsync -a "docs.spring.io/" "$dest/" 2>/dev/null || true
        rm -rf "docs.spring.io"
      fi
    fi
  fi
  rm -rf "$tmpdir"
}

# Sources
BOOT_REF_HTML_URL="https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/"
BOOT_REF_PDF_URL="https://docs.spring.io/spring-boot/docs/current/reference/pdf/spring-boot-reference.pdf"
BOOT_REF_HTML_MULTI_URL="https://docs.spring.io/spring-boot/reference/"
BOOT_API_URL="https://docs.spring.io/spring-boot/api/java/"

FRAME_REF_HTML_URL="https://docs.spring.io/spring-framework/reference/htmlsingle/"
FRAME_REF_HTML_MULTI_URL="https://docs.spring.io/spring-framework/reference/"
FRAME_REF_PDF_URL="https://docs.spring.io/spring-framework/reference/pdf/spring-framework-reference.pdf"
FRAME_API_URL="https://docs.spring.io/spring-framework/docs/current/javadoc-api/"

SAI_REF_HTML_URL="https://docs.spring.io/spring-ai/reference/htmlsingle/"
SAI_REF_HTML_MULTI_URL="https://docs.spring.io/spring-ai/reference/"
SAI_REF_PDF_URL="https://docs.spring.io/spring-ai/reference/pdf/spring-ai-reference.pdf"
SAI_API_URL="https://javadoc.io/doc/org.springframework.ai/spring-ai-core/latest/"

JAVA25_API_URL="https://download.java.net/java/early_access/jdk25/docs/api/"

mkdir -p "$OUT_DIR/spring-boot" "$OUT_DIR/spring-framework" "$OUT_DIR/spring-ai" "$OUT_DIR/java" >/dev/null 2>&1 || true
# Fetch references (HTML + PDF)
fetch_file "$BOOT_REF_HTML_URL" "$OUT_DIR/spring-boot/reference.html" || true
fetch_file "$BOOT_REF_PDF_URL" "$OUT_DIR/spring-boot/reference.pdf" || true

if [ "$TRY_FRAME_SINGLE" = "true" ]; then
  fetch_file "$FRAME_REF_HTML_URL" "$OUT_DIR/spring-framework/reference.html" || true
  fetch_file "$FRAME_REF_PDF_URL" "$OUT_DIR/spring-framework/reference.pdf" || true
fi

if [ "$TRY_SAI_SINGLE" = "true" ]; then
  fetch_file "$SAI_REF_HTML_URL" "$OUT_DIR/spring-ai/reference.html" || true
  fetch_file "$SAI_REF_PDF_URL" "$OUT_DIR/spring-ai/reference.pdf" || true
fi

# Mirror multi-page reference HTMLs
[ -d "$OUT_DIR/spring-boot/reference-html" ] || mirror_site "$BOOT_REF_HTML_MULTI_URL" "$OUT_DIR/spring-boot/reference-html" || true
mirror_site "$FRAME_REF_HTML_MULTI_URL" "$OUT_DIR/spring-framework/reference-html" || true
mirror_site "$SAI_REF_HTML_MULTI_URL" "$OUT_DIR/spring-ai/reference-html" || true

# Fetch Javadocs (mirrors)
if [ "$INCLUDE_JAVADOC" = "true" ]; then
  mirror_site "$BOOT_API_URL" "$OUT_DIR/spring-boot/api"
  mirror_site "$FRAME_API_URL" "$OUT_DIR/spring-framework/api"
  mirror_site "$SAI_API_URL" "$OUT_DIR/spring-ai/api"
  mirror_site "$JAVA25_API_URL" "$OUT_DIR/java/api"
fi

# Quality gates
QUALITY_FAILS=0

# Quality for references: pass if either single-page size or multi-page html count is sufficient
BOOT_SINGLE_OK=1
FRAME_SINGLE_OK=1
SAI_SINGLE_OK=1

if [ -f "$OUT_DIR/spring-boot/reference.html" ]; then
  ensure_min_size "$OUT_DIR/spring-boot/reference.html" 200000 || BOOT_SINGLE_OK=0
else
  BOOT_SINGLE_OK=0
fi
ensure_html_count "$OUT_DIR/spring-boot/reference-html" 80 || BOOT_MULTI_OK=0
BOOT_MULTI_OK=${BOOT_MULTI_OK:-1}
[ "${BOOT_SINGLE_OK}" -eq 1 ] || [ "${BOOT_MULTI_OK}" -eq 1 ] || {
  echo "[$(timestamp)] Boot reference insufficient via mirror; trying sitemap fallback" >&3
  sitemap_fetch_reference "spring-boot" "$BOOT_REF_HTML_MULTI_URL" "$OUT_DIR/spring-boot/reference-html"
  ensure_html_count "$OUT_DIR/spring-boot/reference-html" 80 || QUALITY_FAILS=$((QUALITY_FAILS+1))
}

if [ -f "$OUT_DIR/spring-framework/reference.html" ]; then
  ensure_min_size "$OUT_DIR/spring-framework/reference.html" 200000 || FRAME_SINGLE_OK=0
else
  FRAME_SINGLE_OK=0
fi
ensure_html_count "$OUT_DIR/spring-framework/reference-html" 400 || FRAME_MULTI_OK=0
FRAME_MULTI_OK=${FRAME_MULTI_OK:-1}
[ "${FRAME_SINGLE_OK}" -eq 1 ] || [ "${FRAME_MULTI_OK}" -eq 1 ] || QUALITY_FAILS=$((QUALITY_FAILS+1))

if [ -f "$OUT_DIR/spring-ai/reference.html" ]; then
  ensure_min_size "$OUT_DIR/spring-ai/reference.html" 80000 || SAI_SINGLE_OK=0
else
  SAI_SINGLE_OK=0
fi
ensure_html_count "$OUT_DIR/spring-ai/reference-html" 40 || SAI_MULTI_OK=0
SAI_MULTI_OK=${SAI_MULTI_OK:-1}
[ "${SAI_SINGLE_OK}" -eq 1 ] || [ "${SAI_MULTI_OK}" -eq 1 ] || QUALITY_FAILS=$((QUALITY_FAILS+1))

# PDFs are optional but checked when present
if [ -f "$OUT_DIR/spring-boot/reference.pdf" ]; then
  ensure_min_size "$OUT_DIR/spring-boot/reference.pdf" 1000000 || echo "[$(timestamp)] WARN: spring-boot PDF smaller than typical" >&3
fi
if [ -f "$OUT_DIR/spring-framework/reference.pdf" ]; then
  ensure_min_size "$OUT_DIR/spring-framework/reference.pdf" 1000000 || echo "[$(timestamp)] WARN: spring-framework PDF smaller than typical" >&3
fi
if [ -f "$OUT_DIR/spring-ai/reference.pdf" ]; then
  ensure_min_size "$OUT_DIR/spring-ai/reference.pdf" 100000 || echo "[$(timestamp)] WARN: spring-ai PDF smaller than typical" >&3
fi

if [ "$INCLUDE_JAVADOC" = "true" ]; then
  ensure_html_count "$OUT_DIR/spring-boot/api" 100 || QUALITY_FAILS=$((QUALITY_FAILS+1))
  ensure_html_count "$OUT_DIR/spring-framework/api" 2000 || QUALITY_FAILS=$((QUALITY_FAILS+1))
  ensure_html_count "$OUT_DIR/spring-ai/api" 50 || QUALITY_FAILS=$((QUALITY_FAILS+1))
  ensure_html_count "$OUT_DIR/java/api" 2000 || QUALITY_FAILS=$((QUALITY_FAILS+1))
fi

# Build manifest
BOOT_API_HTML=0
FRAME_API_HTML=0
SAI_API_HTML=0
JAVA_API_HTML=0
BOOT_REF_HTML_COUNT=0
FRAME_REF_HTML_COUNT=0
SAI_REF_HTML_COUNT=0

if [ -d "$OUT_DIR/spring-boot/api" ]; then BOOT_API_HTML=$(find "$OUT_DIR/spring-boot/api" -type f -name "*.html" | wc -l | tr -d ' '); fi
if [ -d "$OUT_DIR/spring-framework/api" ]; then FRAME_API_HTML=$(find "$OUT_DIR/spring-framework/api" -type f -name "*.html" | wc -l | tr -d ' '); fi
if [ -d "$OUT_DIR/spring-ai/api" ]; then SAI_API_HTML=$(find "$OUT_DIR/spring-ai/api" -type f -name "*.html" | wc -l | tr -d ' '); fi
if [ -d "$OUT_DIR/java/api" ]; then JAVA_API_HTML=$(find "$OUT_DIR/java/api" -type f -name "*.html" | wc -l | tr -d ' '); fi

if [ -d "$OUT_DIR/spring-boot/reference-html" ]; then BOOT_REF_HTML_COUNT=$(find "$OUT_DIR/spring-boot/reference-html" -type f -name "*.html" | wc -l | tr -d ' '); fi
if [ -d "$OUT_DIR/spring-framework/reference-html" ]; then FRAME_REF_HTML_COUNT=$(find "$OUT_DIR/spring-framework/reference-html" -type f -name "*.html" | wc -l | tr -d ' '); fi
if [ -d "$OUT_DIR/spring-ai/reference-html" ]; then SAI_REF_HTML_COUNT=$(find "$OUT_DIR/spring-ai/reference-html" -type f -name "*.html" | wc -l | tr -d ' '); fi

BOOT_REF_HTML_BYTES=$(wc -c < "$OUT_DIR/spring-boot/reference.html" | tr -d ' ')
FRAME_REF_HTML_BYTES=$( [ -f "$OUT_DIR/spring-framework/reference.html" ] && wc -c < "$OUT_DIR/spring-framework/reference.html" | tr -d ' ' || echo 0 )
SAI_REF_HTML_BYTES=$( [ -f "$OUT_DIR/spring-ai/reference.html" ] && wc -c < "$OUT_DIR/spring-ai/reference.html" | tr -d ' ' || echo 0 )

BOOT_API_KB=$(total_kb "$OUT_DIR/spring-boot/api" || echo 0)
FRAME_API_KB=$(total_kb "$OUT_DIR/spring-framework/api" || echo 0)
SAI_API_KB=$(total_kb "$OUT_DIR/spring-ai/api" || echo 0)
JAVA_API_KB=$(total_kb "$OUT_DIR/java/api" || echo 0)

cat >"$MANIFEST_FILE" <<JSON
{
  "generatedAt": "$(timestamp)",
  "includeJavadoc": $INCLUDE_JAVADOC,
  "sources": {
    "springBoot": {
      "referenceHtmlBytes": $BOOT_REF_HTML_BYTES,
      "referencePdfBytes": $( [ -f "$OUT_DIR/spring-boot/reference.pdf" ] && wc -c < "$OUT_DIR/spring-boot/reference.pdf" | tr -d ' ' || echo 0 ),
      "referenceHtmlPages": $BOOT_REF_HTML_COUNT,
      "javadocHtmlCount": $BOOT_API_HTML,
      "javadocSizeKB": $BOOT_API_KB
    },
    "springFramework": {
      "referenceHtmlBytes": $FRAME_REF_HTML_BYTES,
      "referencePdfBytes": $( [ -f "$OUT_DIR/spring-framework/reference.pdf" ] && wc -c < "$OUT_DIR/spring-framework/reference.pdf" | tr -d ' ' || echo 0 ),
      "referenceHtmlPages": $FRAME_REF_HTML_COUNT,
      "javadocHtmlCount": $FRAME_API_HTML,
      "javadocSizeKB": $FRAME_API_KB
    },
    "springAI": {
      "referenceHtmlBytes": $SAI_REF_HTML_BYTES,
      "referencePdfBytes": $( [ -f "$OUT_DIR/spring-ai/reference.pdf" ] && wc -c < "$OUT_DIR/spring-ai/reference.pdf" | tr -d ' ' || echo 0 ),
      "referenceHtmlPages": $SAI_REF_HTML_COUNT,
      "javadocHtmlCount": $SAI_API_HTML,
      "javadocSizeKB": $SAI_API_KB
    },
    "java25": {
      "javadocHtmlCount": $JAVA_API_HTML,
      "javadocSizeKB": $JAVA_API_KB
    }
  },
  "quality": {
    "failures": $QUALITY_FAILS,
    "status": "$( [ "$QUALITY_FAILS" -eq 0 ] && echo PASSED || echo FAILED )"
  }
}
JSON

echo "[$(timestamp)] Wrote manifest to $MANIFEST_FILE" >&3

if [ "$QUALITY_FAILS" -ne 0 ]; then
  echo "[$(timestamp)] One or more quality gates failed." >&3
  exit 2
fi

echo "[$(timestamp)] Quality gates PASSED." >&3
echo "[$(timestamp)] Done." >&3


