#!/bin/bash

# Verifies atomic documentation publication restores prior roots on each failed mutation.

set -euo pipefail

PUBLICATION_TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_DIR="$PUBLICATION_TEST_SCRIPT_DIRECTORY"
PUBLICATION_TEST_WORK_DIRECTORY="$(mktemp -d)"
trap 'rm -rf -- "$PUBLICATION_TEST_WORK_DIRECTORY"' EXIT

fail_publication_test() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

RED=""
YELLOW=""
NC=""
# shellcheck source=lib/documentation_seed_mirrors.sh
source "$PUBLICATION_TEST_SCRIPT_DIRECTORY/lib/documentation_seed_mirrors.sh"

log() {
    :
}

COPY_TEST_PUBLISHED="$PUBLICATION_TEST_WORK_DIRECTORY/copy-published"
COPY_TEST_STAGING="$PUBLICATION_TEST_WORK_DIRECTORY/copy-staging"
mkdir -p "$COPY_TEST_PUBLISHED" "$COPY_TEST_STAGING"
printf '%s\n' "published" > "$COPY_TEST_PUBLISHED/index.html"
copy_documentation_mirror_to_staging "$COPY_TEST_PUBLISHED" "$COPY_TEST_STAGING"
COPY_TEST_PUBLISHED_IDENTITY="$(stat -c '%d:%i' "$COPY_TEST_PUBLISHED/index.html" 2>/dev/null \
    || stat -f '%d:%i' "$COPY_TEST_PUBLISHED/index.html")"
COPY_TEST_STAGING_IDENTITY="$(stat -c '%d:%i' "$COPY_TEST_STAGING/index.html" 2>/dev/null \
    || stat -f '%d:%i' "$COPY_TEST_STAGING/index.html")"
[ "$COPY_TEST_PUBLISHED_IDENTITY" != "$COPY_TEST_STAGING_IDENTITY" ] \
    || fail_publication_test "validation staging shared a mutable inode with the published mirror"
printf '%s\n' "staged" > "$COPY_TEST_STAGING/index.html"
[ "$(< "$COPY_TEST_PUBLISHED/index.html")" = "published" ] \
    || fail_publication_test "staging mutation changed the published mirror"

create_documentation_quarantine_directory() {
    local quarantine_directory="$PUBLICATION_TEST_WORK_DIRECTORY/quarantine/$1-$2-$PUBLICATION_TEST_CASE"
    mkdir -p "$quarantine_directory"
    printf '%s\n' "$quarantine_directory"
}

prepare_publication_case() {
    DOCS_ROOT="$PUBLICATION_TEST_WORK_DIRECTORY/$PUBLICATION_TEST_CASE/data/docs"
    PUBLICATION_TEST_STAGE="$PUBLICATION_TEST_WORK_DIRECTORY/$PUBLICATION_TEST_CASE/stage"
    PUBLICATION_TEST_TARGET="$DOCS_ROOT/rolling-reference"
    PUBLICATION_TEST_SUPERSEDED="$DOCS_ROOT/rolling-reference-legacy"
    mkdir -p "$PUBLICATION_TEST_STAGE" "$PUBLICATION_TEST_TARGET" "$PUBLICATION_TEST_SUPERSEDED"
    printf '%s\n' "replacement" > "$PUBLICATION_TEST_STAGE/index.html"
    printf '%s\n' "active" > "$PUBLICATION_TEST_TARGET/index.html"
    printf '%s\n' "superseded" > "$PUBLICATION_TEST_SUPERSEDED/index.html"
}

PUBLICATION_TEST_CASE=atomic-exchange
prepare_publication_case
atomic_exchange_documentation_directories "$PUBLICATION_TEST_STAGE" "$PUBLICATION_TEST_TARGET"
[ "$(< "$PUBLICATION_TEST_TARGET/index.html")" = "replacement" ] \
    || fail_publication_test "atomic exchange did not publish the replacement directory"
[ "$(< "$PUBLICATION_TEST_STAGE/index.html")" = "active" ] \
    || fail_publication_test "atomic exchange did not retain the prior directory"

PUBLICATION_TEST_CASE=successful-publication
prepare_publication_case
publish_staged_documentation_mirror \
    "$PUBLICATION_TEST_STAGE" \
    "rolling-reference" \
    "rolling-reference-legacy" \
    "Rolling Reference"
[ "$(< "$PUBLICATION_TEST_TARGET/index.html")" = "replacement" ] \
    || fail_publication_test "successful publication did not install the replacement directory"
[ ! -e "$PUBLICATION_TEST_STAGE" ] \
    || fail_publication_test "successful publication left the staging directory active"
[ ! -e "$PUBLICATION_TEST_SUPERSEDED" ] \
    || fail_publication_test "successful publication left the superseded directory active"

PUBLICATION_TEST_CASE=child-root-publication
prepare_publication_case
PUBLICATION_TEST_SUPERSEDED="$PUBLICATION_TEST_TARGET/1.0"
mkdir -p "$PUBLICATION_TEST_SUPERSEDED"
printf '%s\n' "superseded child" > "$PUBLICATION_TEST_SUPERSEDED/index.html"
publish_staged_documentation_mirror \
    "$PUBLICATION_TEST_STAGE" \
    "rolling-reference" \
    "rolling-reference/1.0" \
    "Rolling Reference"
[ "$(< "$PUBLICATION_TEST_TARGET/index.html")" = "replacement" ] \
    || fail_publication_test "child-root publication did not install the replacement directory"
if ! find "$PUBLICATION_TEST_WORK_DIRECTORY/quarantine" -path '*/rolling-reference/1.0/index.html' -print -quit \
    | grep -q .; then
    fail_publication_test "child superseded root was not retained with the prior canonical root"
fi

assert_prior_roots_restored() {
    [ "$(< "$PUBLICATION_TEST_TARGET/index.html")" = "active" ] \
        || fail_publication_test "prior active root was not restored for $PUBLICATION_TEST_CASE"
    [ "$(< "$PUBLICATION_TEST_SUPERSEDED/index.html")" = "superseded" ] \
        || fail_publication_test "prior superseded root was not restored for $PUBLICATION_TEST_CASE"
    [ "$(< "$PUBLICATION_TEST_STAGE/index.html")" = "replacement" ] \
        || fail_publication_test "validated staging root was not retained for $PUBLICATION_TEST_CASE"
}

PUBLICATION_TEST_CASE=retire-superseded-failure
prepare_publication_case
mv() {
    if [ "$1" = "$PUBLICATION_TEST_SUPERSEDED" ]; then
        return 71
    fi
    command mv "$@"
}
if publish_staged_documentation_mirror \
    "$PUBLICATION_TEST_STAGE" \
    "rolling-reference" \
    "rolling-reference-legacy" \
    "Rolling Reference"; then
    fail_publication_test "superseded-root retirement failure was accepted"
fi
assert_prior_roots_restored
unset -f mv

PUBLICATION_TEST_CASE=staging-publication-failure
prepare_publication_case
mv() {
    if [ "$1" = "$PUBLICATION_TEST_STAGE" ]; then
        return 72
    fi
    command mv "$@"
}
if publish_staged_documentation_mirror \
    "$PUBLICATION_TEST_STAGE" \
    "rolling-reference" \
    "rolling-reference-legacy" \
    "Rolling Reference"; then
    fail_publication_test "staging publication failure was accepted"
fi
assert_prior_roots_restored

printf 'PASS: failed documentation publication retains staging and restores active and superseded roots.\n'
