#!/bin/bash

# Exercises versioned documentation archive extraction without network access.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_WORK_DIRECTORY="$(mktemp -d)"
ZIP_FIXTURE_PATH="$TEST_WORK_DIRECTORY/javadoc.zip"
TAR_FIXTURE_ROOT="$TEST_WORK_DIRECTORY/python-docs"
TAR_FIXTURE_PATH="$TEST_WORK_DIRECTORY/python-docs.tar.bz2"
UNSAFE_ZIP_FIXTURE_PATH="$TEST_WORK_DIRECTORY/unsafe.zip"
LOG_FILE="$TEST_WORK_DIRECTORY/archive.log"
RED=""
GREEN=""
NC=""
trap 'rm -rf -- "$TEST_WORK_DIRECTORY"' EXIT

# shellcheck source=lib/documentation_sources.sh
source "$TEST_SCRIPT_DIRECTORY/lib/documentation_sources.sh"
# shellcheck source=lib/documentation_fetch_sources.sh
source "$TEST_SCRIPT_DIRECTORY/lib/documentation_fetch_sources.sh"
# shellcheck source=lib/documentation_archives.sh
source "$TEST_SCRIPT_DIRECTORY/lib/documentation_archives.sh"

log() {
    printf '%s\n' "$1" >> "$LOG_FILE"
}

wget2() {
    local output_document=""
    local source_url=""
    local wget_argument
    for wget_argument in "$@"; do
        case "$wget_argument" in
            --output-document=*) output_document="${wget_argument#*=}" ;;
            file://*) source_url="$wget_argument" ;;
        esac
    done
    cp "${source_url#file://}" "$output_document"
}

python3 - "$ZIP_FIXTURE_PATH" "$UNSAFE_ZIP_FIXTURE_PATH" <<'PYTHON'
import pathlib
import sys
import zipfile

zip_fixture_path = pathlib.Path(sys.argv[1])
unsafe_zip_fixture_path = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(zip_fixture_path, "w") as archive:
    archive.writestr("index.html", "<title>Exact Javadoc 1.0 API</title>")
    archive.writestr("package-summary.html", "<title>Package</title>")
    archive.writestr("versioned/html/documentation/guide.html", "<title>Guide</title>")
    archive.writestr("versioned/html/api/index.html", "<title>API</title>")
with zipfile.ZipFile(unsafe_zip_fixture_path, "w") as archive:
    archive.writestr("../escaped.html", "unsafe")
PYTHON

mkdir -p "$TAR_FIXTURE_ROOT/python-exact-docs/library"
printf '<title>Python Exact Documentation</title>\n' > "$TAR_FIXTURE_ROOT/python-exact-docs/index.html"
printf '<title>Library</title>\n' > "$TAR_FIXTURE_ROOT/python-exact-docs/library/index.html"
tar -cjf "$TAR_FIXTURE_PATH" --directory "$TAR_FIXTURE_ROOT" python-exact-docs

ZIP_TARGET_DIRECTORY="$TEST_WORK_DIRECTORY/zip-target"
mkdir -p "$ZIP_TARGET_DIRECTORY"
fetch_documentation_archive \
    "file://$ZIP_FIXTURE_PATH" \
    "$ZIP_TARGET_DIRECTORY" \
    "Exact Javadoc" \
    2 \
    zip \
    0
[ -f "$ZIP_TARGET_DIRECTORY/index.html" ]
[ -f "$ZIP_TARGET_DIRECTORY/package-summary.html" ]

ZIP_PUBLICATION_TARGET_DIRECTORY="$TEST_WORK_DIRECTORY/zip-publication-target"
mkdir -p "$ZIP_PUBLICATION_TARGET_DIRECTORY"
fetch_documentation_archive \
    "file://$ZIP_FIXTURE_PATH" \
    "$ZIP_PUBLICATION_TARGET_DIRECTORY" \
    "Versioned ZIP Documentation" \
    1 \
    zip \
    0 \
    "versioned/html/documentation"
[ -f "$ZIP_PUBLICATION_TARGET_DIRECTORY/guide.html" ]
[ ! -e "$ZIP_PUBLICATION_TARGET_DIRECTORY/versioned" ]
[ ! -e "$ZIP_PUBLICATION_TARGET_DIRECTORY/index.html" ]

TAR_TARGET_DIRECTORY="$TEST_WORK_DIRECTORY/tar-target"
mkdir -p "$TAR_TARGET_DIRECTORY"
fetch_documentation_archive \
    "file://$TAR_FIXTURE_PATH" \
    "$TAR_TARGET_DIRECTORY" \
    "Exact Python Documentation" \
    2 \
    tar-bz2 \
    1
[ -f "$TAR_TARGET_DIRECTORY/index.html" ]
[ -f "$TAR_TARGET_DIRECTORY/library/index.html" ]

UNSAFE_TARGET_DIRECTORY="$TEST_WORK_DIRECTORY/unsafe-target"
mkdir -p "$UNSAFE_TARGET_DIRECTORY"
if fetch_documentation_archive \
    "file://$UNSAFE_ZIP_FIXTURE_PATH" \
    "$UNSAFE_TARGET_DIRECTORY" \
    "Unsafe Javadoc" \
    1 \
    zip \
    0; then
    echo "Unsafe documentation archive was accepted" >&2
    exit 1
fi
[ ! -e "$TEST_WORK_DIRECTORY/escaped.html" ]

echo "Documentation archive tests passed"
