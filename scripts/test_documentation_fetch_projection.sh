#!/bin/bash

# Exercises explicit fetch option routing and scoped source selection without network access.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_SCRIPT_DIRECTORY/.." && pwd)"
FETCH_SCRIPT="$PROJECT_ROOT/scripts/fetch_all_docs.sh"
TEST_WORK_DIRECTORY="$(mktemp -d)"
TEST_DOCS_ROOT="$TEST_WORK_DIRECTORY/docs"
DISCOVERED_FETCH_CAPTURE="$TEST_WORK_DIRECTORY/discovered-fetch"
SELECTED_SOURCE_CAPTURE="$TEST_WORK_DIRECTORY/selected-source"
ENVIRONMENT_OVERRIDE_CAPTURE="$TEST_WORK_DIRECTORY/environment-override"
mkdir -p "$TEST_DOCS_ROOT"
trap 'rm -rf -- "$TEST_WORK_DIRECTORY"' EXIT

fail_documentation_fetch_test() {
    local failure_message="$1"
    printf 'FAIL: %s\n' "$failure_message" >&2
    exit 1
}

assert_captured_arguments() {
    local capture_file="$1"
    shift
    local expected_argument
    local captured_argument

    exec 3< "$capture_file"
    for expected_argument in "$@"; do
        if ! IFS= read -r captured_argument <&3; then
            fail_documentation_fetch_test "missing captured argument: $expected_argument"
        fi
        if [ "$captured_argument" != "$expected_argument" ]; then
            fail_documentation_fetch_test "expected '$expected_argument' but captured '$captured_argument'"
        fi
    done
    if IFS= read -r captured_argument <&3; then
        fail_documentation_fetch_test "unexpected captured argument: $captured_argument"
    fi
    exec 3<&-
}

assert_no_source_dispatch() {
    local capture_file="$1"
    local selector_description="$2"
    if [ -s "$capture_file" ]; then
        fail_documentation_fetch_test "$selector_description dispatched a source before validation completed"
    fi
}

set --
# shellcheck source=fetch_all_docs.sh
source "$FETCH_SCRIPT"

DOCS_ROOT="$TEST_DOCS_ROOT"
log() {
    :
}

fetch_discovered_documentation_seed() {
    printf '%s\n' "$@" > "$DISCOVERED_FETCH_CAPTURE"
}

if ! (
    fetch_source \
        --url "https://docs.example.invalid/reference/" \
        --mirror-path "example/reference" \
        --name "Example Reference" \
        --cut-directories 3 \
        --minimum-html-files 7 \
        --reject-regex "/archive" \
        --allow-partial \
        --seed-document-type xml-sitemap \
        --seed-discovery-url "https://docs.example.invalid/sitemap.xml" \
        --seed-source-prefix "https://docs.example.invalid/reference/"
); then
    fail_documentation_fetch_test "named fetch options did not reach the discovered-source strategy"
fi

assert_captured_arguments "$DISCOVERED_FETCH_CAPTURE" \
    "https://docs.example.invalid/reference/" \
    "$TEST_DOCS_ROOT/example/reference" \
    "Example Reference" \
    3 \
    7 \
    "/archive" \
    true \
    xml-sitemap \
    "https://docs.example.invalid/sitemap.xml" \
    "https://docs.example.invalid/reference/"

: > "$DISCOVERED_FETCH_CAPTURE"
if fetch_source \
    --url "https://docs.example.invalid/reference/" \
    --mirror-path "example/reference" \
    --cut-directories 3 \
    --minimum-html-files 7 > /dev/null 2>&1; then
    fail_documentation_fetch_test "fetch options without a name were accepted"
fi
assert_no_source_dispatch "$DISCOVERED_FETCH_CAPTURE" "missing required fetch option"

if fetch_source \
    --url "https://docs.example.invalid/reference/" \
    --mirror-path "example/reference" \
    --name "Example Reference" \
    --cut-directories 3 \
    --minimum-html-files 7 \
    --unknown-option > /dev/null 2>&1; then
    fail_documentation_fetch_test "unknown fetch option was accepted"
fi
assert_no_source_dispatch "$DISCOVERED_FETCH_CAPTURE" "unknown fetch option"

fetch_source() {
    printf '%s\n' "$@" > "$SELECTED_SOURCE_CAPTURE"
}

if ! (
    run_documentation_fetch --doc-sets=kotlin > /dev/null
); then
    fail_documentation_fetch_test "named Kotlin selection did not complete"
fi

assert_captured_arguments "$SELECTED_SOURCE_CAPTURE" \
    --url \
    "https://kotlinlang.org/docs/" \
    --mirror-path \
    kotlin \
    --name \
    "Kotlin Documentation" \
    --cut-directories \
    1 \
    --minimum-html-files \
    250 \
    --seed-document-type \
    xml-sitemap \
    --seed-discovery-url \
    "https://kotlinlang.org/sitemap.xml" \
    --seed-source-prefix \
    "https://kotlinlang.org/docs/" \
    --superseded-mirror-path \
    "kotlin/2.4.10"

assert_rejected_selector() {
    local documentation_source_selector="$1"
    : > "$SELECTED_SOURCE_CAPTURE"
    if (run_documentation_fetch "--doc-sets=$documentation_source_selector" > /dev/null 2>&1); then
        fail_documentation_fetch_test "invalid selector was accepted: $documentation_source_selector"
    fi
    assert_no_source_dispatch "$SELECTED_SOURCE_CAPTURE" "invalid selector '$documentation_source_selector'"
}

assert_rejected_selector "kotlin,unknown-source"
assert_rejected_selector "kotlin,kotlin"
assert_rejected_selector "kotlin,,java/java25-complete"

if ! (
    SPRING_AI_REFERENCE_BASE="https://override.example.invalid/spring-ai/reference/"
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() {
        :
    }
    LEGACY_SOURCE_CALL_COUNT=0
    record_documentation_fetch() {
        LEGACY_SOURCE_CALL_COUNT=$((LEGACY_SOURCE_CALL_COUNT + 1))
        if [ "$LEGACY_SOURCE_CALL_COUNT" -eq 1 ]; then
            printf '%s\n' "$@" > "$ENVIRONMENT_OVERRIDE_CAPTURE"
        fi
    }
    fetch_legacy_sources
); then
    fail_documentation_fetch_test "legacy source URL override did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://override.example.invalid/spring-ai/reference/" \
    --mirror-path \
    "spring-ai-reference" \
    --name \
    "Spring AI Reference (stable)" \
    --cut-directories \
    1 \
    --minimum-html-files \
    80 \
    --reject-regex \
    "/spring-ai/reference/[0-9]|/spring-ai/reference/[^/]*SNAPSHOT"

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    DOCS_ROOT="$TEST_WORK_DIRECTORY/java-post-fetch/data/docs"
    LOG_FILE="$TEST_WORK_DIRECTORY/java-post-fetch.log"
    JAVA_POST_FETCH_TARGET_DIRECTORY="$DOCS_ROOT/java/java25-complete"
    JAVA_POST_FETCH_WGET_ARGUMENTS="$TEST_WORK_DIRECTORY/java-post-fetch-wget-arguments"
    mkdir -p "$JAVA_POST_FETCH_TARGET_DIRECTORY"
    printf '%s\n%s\n' \
        "https://docs.example.invalid/Record.html" \
        "https://docs.example.invalid/String.html" \
        > "$JAVA_POST_FETCH_TARGET_DIRECTORY/.oracle-javadoc-seed.txt"
    log() {
        :
    }
    wget() {
        printf '%s\n' "$@" > "$JAVA_POST_FETCH_WGET_ARGUMENTS"
        printf '<html>Record</html>\n' > "$JAVA_POST_FETCH_TARGET_DIRECTORY/Record.html"
    }
    write_java_api_seed_mirror_paths() {
        printf '%s\n%s\n' Record.html String.html > "$4"
    }
    if (
        cd "$JAVA_POST_FETCH_TARGET_DIRECTORY"
        fetch_java_api_javadoc_seed \
            "$JAVA_POST_FETCH_TARGET_DIRECTORY" \
            "Java post-fetch verification" \
            0 \
            1 \
            "" \
            false \
            "https://docs.example.invalid/"
    ) > /dev/null 2>&1; then
        exit 1
    fi
    [ -f "$JAVA_POST_FETCH_TARGET_DIRECTORY/Record.html" ] || exit 1
    [ ! -f "$JAVA_POST_FETCH_TARGET_DIRECTORY/String.html" ] || exit 1
    grep -Fxq -- "--max-redirect=0" "$JAVA_POST_FETCH_WGET_ARGUMENTS"
); then
    fail_documentation_fetch_test "Java seed fetch did not reject redirects or verify fetched seed paths"
fi

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    DOCS_ROOT="$TEST_WORK_DIRECTORY/java-quarantine/data/docs"
    LOG_FILE="$TEST_WORK_DIRECTORY/java-quarantine.log"
    JAVA_QUARANTINE_TARGET_DIRECTORY="$DOCS_ROOT/java/java25-complete"
    mkdir -p "$JAVA_QUARANTINE_TARGET_DIRECTORY/java.base/java/lang"
    printf '%s\n' "https://docs.example.invalid/java.base/java/lang/Record.html" \
        > "$JAVA_QUARANTINE_TARGET_DIRECTORY/.oracle-javadoc-seed.txt"
    printf '<html>Canonical</html>\n' \
        > "$JAVA_QUARANTINE_TARGET_DIRECTORY/java.base/java/lang/Record.html"
    printf '<html>Stale</html>\n' > "$JAVA_QUARANTINE_TARGET_DIRECTORY/Record.html"
    log() {
        :
    }
    write_java_api_seed_mirror_paths() {
        printf '%s\n' "java.base/java/lang/Record.html" > "$4"
    }
    reconcile_java_api_seed_mirror \
        "https://docs.example.invalid/" \
        "$JAVA_QUARANTINE_TARGET_DIRECTORY" \
        "Java stale-page quarantine" \
        0
    [ -f "$JAVA_QUARANTINE_TARGET_DIRECTORY/java.base/java/lang/Record.html" ] || exit 1
    [ ! -e "$JAVA_QUARANTINE_TARGET_DIRECTORY/Record.html" ] || exit 1
    find "$(dirname "$DOCS_ROOT")/.quarantine" -type f -name Record.html -print -quit | grep -q .
); then
    fail_documentation_fetch_test "unseeded Java pages were not quarantined outside the active mirror"
fi

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    LOG_FILE="$TEST_WORK_DIRECTORY/java-seed-generation.log"
    JAVA_SEED_TARGET_DIRECTORY="$TEST_WORK_DIRECTORY/java-seed-generation"
    mkdir -p "$JAVA_SEED_TARGET_DIRECTORY"
    printf 'retained-seed\n' > "$JAVA_SEED_TARGET_DIRECTORY/.oracle-javadoc-seed.txt"
    log() {
        :
    }
    python3() {
        while [ "$#" -gt 0 ]; do
            if [ "$1" = "--output" ]; then
                printf 'incomplete-seed\n' > "$2"
                return 42
            fi
            shift
        done
        return 1
    }
    if generate_java_api_javadoc_seed \
        "https://docs.example.invalid/" \
        "$JAVA_SEED_TARGET_DIRECTORY" > /dev/null 2>&1; then
        exit 1
    fi
    [ "$(< "$JAVA_SEED_TARGET_DIRECTORY/.oracle-javadoc-seed.txt")" = "retained-seed" ]
); then
    fail_documentation_fetch_test "failed Java seed generation replaced the active seed"
fi

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    DOCS_ROOT="$TEST_WORK_DIRECTORY/staged-publication/data/docs"
    LOG_FILE="$TEST_WORK_DIRECTORY/staged-publication.log"
    STAGED_PUBLICATION_DIRECTORY="$TEST_WORK_DIRECTORY/staged-publication/replacement"
    STAGED_PUBLICATION_DISCARD="$TEST_WORK_DIRECTORY/staged-publication/discarded"
    log() {
        :
    }
    create_documentation_fetch_staging_directory() {
        mkdir -p "$STAGED_PUBLICATION_DIRECTORY"
        printf '%s\n' "$STAGED_PUBLICATION_DIRECTORY"
    }
    fetch_docs_mirror() {
        :
    }
    publish_staged_documentation_mirror() {
        return 71
    }
    discard_documentation_fetch_staging_directory() {
        printf 'discarded\n' > "$STAGED_PUBLICATION_DISCARD"
    }
    if fetch_source \
        --url "https://docs.example.invalid/reference/" \
        --mirror-path "rolling-reference" \
        --name "Rolling Reference" \
        --cut-directories 0 \
        --minimum-html-files 1 \
        --superseded-mirror-path "rolling-reference/1.0" > /dev/null; then
        exit 1
    fi
    [ -s "$STAGED_PUBLICATION_DISCARD" ]
); then
    fail_documentation_fetch_test "staged publication failure was not propagated"
fi

printf 'PASS: explicit fetch options, source selection, Java seed safety, and URL overrides are behaviorally wired.\n'
