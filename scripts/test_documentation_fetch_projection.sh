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
ALL_SOURCE_CAPTURE="$TEST_WORK_DIRECTORY/all-source"
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
    local captured_target_directory="$2"
    local generated_page_number
    for generated_page_number in 1 2 3 4 5 6 7; do
        printf '<html>Example Reference stable</html>\n' > "$captured_target_directory/page-$generated_page_number.html"
    done
    cd - > /dev/null
}

if ! (
    fetch_source \
        --url "https://docs.example.invalid/reference/" \
        --mirror-path "example/reference" \
        --name "Example Reference" \
        --source-version "stable" \
        --identity-regex "Example Reference stable" \
        --cut-directories 3 \
        --minimum-html-files 7 \
        --reject-regex "/archive" \
        --seed-document-type xml-sitemap \
        --seed-discovery-url "https://docs.example.invalid/sitemap.xml" \
        --seed-source-prefix "https://docs.example.invalid/reference/"
); then
    fail_documentation_fetch_test "named fetch options did not reach the discovered-source strategy"
fi

assert_captured_arguments "$DISCOVERED_FETCH_CAPTURE" \
    "https://docs.example.invalid/reference/" \
    "$(dirname "$TEST_DOCS_ROOT")/.documentation-fetch-staging/reference.599af15c691cb0976ef8042aaaf54bb39c76fed2c030db21d93b263113606c4c.partial" \
    "Example Reference" \
    3 \
    7 \
    "/archive" \
    false \
    xml-sitemap \
    "https://docs.example.invalid/sitemap.xml" \
    "https://docs.example.invalid/reference/"

: > "$DISCOVERED_FETCH_CAPTURE"
if fetch_source \
    --url "https://docs.example.invalid/reference/" \
    --mirror-path "example/reference" \
    --source-version "stable" \
    --cut-directories 3 \
    --minimum-html-files 7 > /dev/null 2>&1; then
    fail_documentation_fetch_test "fetch options without a name were accepted"
fi
assert_no_source_dispatch "$DISCOVERED_FETCH_CAPTURE" "missing required fetch option"

if fetch_source \
    --url "https://docs.example.invalid/reference/" \
    --mirror-path "example/reference" \
    --name "Example Reference" \
    --source-version "stable" \
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
    "Kotlin 2.4.10 Documentation" \
    --source-version \
    "2.4.10" \
    --identity-regex \
    "2\\.4\\.10" \
    --required-identity-page \
    "faq.html" \
    --required-identity-text \
    "The currently released version is 2.4.10, published on July 14, 2026." \
    --cut-directories \
    1 \
    --minimum-html-files \
    250 \
    --reject-regex \
    '(^|/)([Ee][Aa][Pp]|[Ss][Nn][Aa][Pp][Ss][Hh][Oo][Tt])(/|(-[^/]+)?\.html$)|(^|/)[^/]*-([Ee][Aa][Pp]|[Ss][Nn][Aa][Pp][Ss][Hh][Oo][Tt])(-[^/]+)?\.html$' \
    --seed-document-type \
    xml-sitemap \
    --seed-discovery-url \
    "https://kotlinlang.org/sitemap.xml" \
    --seed-source-prefix \
    "https://kotlinlang.org/docs/"

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() {
        :
    }
    record_documentation_fetch() {
        printf '%s\n' "$@" > "$ENVIRONMENT_OVERRIDE_CAPTURE"
    }
    fetch_named_official_source groovy
); then
    fail_documentation_fetch_test "canonical Groovy documentation dispatch did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://docs.groovy-lang.org/docs/groovy-5.0.7/html/documentation/" \
    --mirror-path \
    "groovy/5.0.7" \
    --name \
    "Groovy 5.0.7 Documentation" \
    --source-version \
    "5.0.7" \
    --identity-regex \
    'Groovy.*5\.0\.7|5\.0\.7.*Groovy' \
    --cut-directories \
    4 \
    --minimum-html-files \
    9 \
    --reject-regex \
    '/(gdk|templating|type-checking-extensions)\.html$' \
    --seed-document-type \
    html-links \
    --seed-discovery-url \
    "https://docs.groovy-lang.org/docs/groovy-5.0.7/html/documentation/" \
    --seed-source-prefix \
    "https://docs.groovy-lang.org/docs/groovy-5.0.7/html/documentation/"

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() {
        :
    }
    record_documentation_fetch() {
        printf '%s\n' "$@" > "$ENVIRONMENT_OVERRIDE_CAPTURE"
    }
    fetch_named_official_source scala
); then
    fail_documentation_fetch_test "canonical Scala documentation dispatch did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://docs.scala-lang.org/scala3/reference/" \
    --mirror-path \
    scala \
    --name \
    "Scala 3 Documentation" \
    --source-version \
    "3-stable" \
    --identity-regex \
    "Scala 3" \
    --cut-directories \
    2 \
    --minimum-html-files \
    300 \
    --seed-document-type \
    html-links \
    --seed-discovery-url \
    "https://docs.scala-lang.org/scala3/reference/" \
    --seed-source-prefix \
    "https://docs.scala-lang.org/scala3/reference/"

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() {
        :
    }
    record_documentation_fetch() {
        printf '%s\n' "$@" > "$ENVIRONMENT_OVERRIDE_CAPTURE"
    }
    fetch_named_official_source quarkus
); then
    fail_documentation_fetch_test "canonical Quarkus guides dispatch did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://quarkus.io/guides/" \
    --mirror-path \
    quarkus \
    --name \
    "Quarkus Guides" \
    --source-version \
    "stable-current" \
    --identity-regex \
    Quarkus \
    --cut-directories \
    1 \
    --minimum-html-files \
    200 \
    --reject-regex \
    '%7[BbDd]' \
    --seed-document-type \
    html-links \
    --seed-discovery-url \
    "https://quarkus.io/guides/" \
    --seed-source-prefix \
    "https://quarkus.io/guides/"

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    DOCS_ROOT="$TEST_DOCS_ROOT"
    LOG_FILE="$TEST_WORK_DIRECTORY/all-source.log"
    log() {
        :
    }
    fetch_all_official_sources() {
        printf '%s\n' "canonical-full" > "$ALL_SOURCE_CAPTURE"
    }
    fetch_quick_sources() {
        printf '%s\n' "quick" > "$ALL_SOURCE_CAPTURE"
        return 1
    }
    run_documentation_fetch --doc-sets=all > /dev/null
); then
    fail_documentation_fetch_test "all selector did not route to canonical full sources"
fi

if [ "$(< "$ALL_SOURCE_CAPTURE")" != "canonical-full" ]; then
    fail_documentation_fetch_test "all selector included quick documentation mirrors"
fi

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
assert_rejected_selector "all,kotlin"

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() {
        :
    }
    SOURCE_CALL_COUNT=0
    record_documentation_fetch() {
        SOURCE_CALL_COUNT=$((SOURCE_CALL_COUNT + 1))
        if [ "$SOURCE_CALL_COUNT" -eq 1 ]; then
            printf '%s\n' "$@" > "$ENVIRONMENT_OVERRIDE_CAPTURE"
        fi
    }
    fetch_named_official_source spring-ai-reference
); then
    fail_documentation_fetch_test "canonical source dispatch did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://docs.spring.io/spring-ai/reference/1.1/" \
    --mirror-path \
    "spring-ai-reference" \
    --name \
    "Spring AI Reference (stable 1.1)" \
    --source-version \
    "1.1.8" \
    --identity-regex \
    '<meta name="version" content="1\.1\.8"' \
    --forbidden-identity-regex \
    '<meta name="version" content="(2\.|[^"]*SNAPSHOT)|data-version="(2\.|[^"]*SNAPSHOT)' \
    --expected-meta-version \
    "1.1.8" \
    --cut-directories \
    3 \
    --minimum-html-files \
    80 \
    --reject-regex \
    'SNAPSHOT|/spring-ai/reference/(2\.|next/)' \
    --superseded-mirror-path \
    "spring-ai-complete"

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() {
        :
    }
    SOURCE_CALL_COUNT=0
    record_documentation_fetch() {
        SOURCE_CALL_COUNT=$((SOURCE_CALL_COUNT + 1))
        if [ "$SOURCE_CALL_COUNT" -eq 1 ]; then
            printf '%s\n' "$@" > "$ENVIRONMENT_OVERRIDE_CAPTURE"
        fi
    }
    fetch_named_official_source spring-ai-api-stable
); then
    fail_documentation_fetch_test "canonical Spring AI API dispatch did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://docs.spring.io/spring-ai/docs/1.1.2/api/" \
    --mirror-path \
    "spring-ai-api-stable" \
    --name \
    "Spring AI API 1.1.2" \
    --source-version \
    "1.1.2" \
    --identity-regex \
    'Spring AI Parent 1\.1\.2 API' \
    --forbidden-identity-regex \
    'Spring AI Parent (2\.[^ ]*|[^ ]*SNAPSHOT) API' \
    --cut-directories \
    4 \
    --minimum-html-files \
    4000 \
    --reject-regex \
    'SNAPSHOT|/spring-ai/docs/2\.'

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() {
        :
    }
    record_documentation_fetch() {
        printf '%s\n' "$@" > "$ENVIRONMENT_OVERRIDE_CAPTURE"
    }
    fetch_named_official_source java/java25-complete
); then
    fail_documentation_fetch_test "canonical Java 25 API dispatch did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --java-release \
    25 \
    --url \
    "https://docs.oracle.com/en/java/javase/25/docs/api/" \
    --mirror-path \
    "java/java25-complete" \
    --name \
    "Java 25 Complete API" \
    --source-version \
    "25-ga" \
    --identity-regex \
    'Overview \(Java SE 25 &amp; JDK 25\)' \
    --required-identity-page \
    "api/index.html" \
    --required-identity-text \
    "Overview (Java SE 25 & JDK 25)" \
    --cut-directories \
    5 \
    --minimum-html-files \
    5000

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() {
        :
    }
    record_documentation_fetch() {
        printf '%s\n' "$@" > "$ENVIRONMENT_OVERRIDE_CAPTURE"
    }
    fetch_named_official_source jetbrains-java25-article
); then
    fail_documentation_fetch_test "governed JetBrains article dispatch did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://blog.jetbrains.com/idea/2025/09/java-25-lts-and-intellij-idea/" \
    --mirror-path \
    "jetbrains/idea/2025/09" \
    --name \
    "JetBrains Java 25 Blog" \
    --source-version \
    "25-ga" \
    --identity-regex \
    'Java.*25|25.*Java' \
    --cut-directories \
    3 \
    --minimum-html-files \
    1 \
    --single-page

SINGLE_PAGE_STAGE="$TEST_WORK_DIRECTORY/single-page-stage"
mkdir -p "$SINGLE_PAGE_STAGE"
printf '<html><body>stale recursive page</body></html>\n' > "$SINGLE_PAGE_STAGE/unrelated.html"
LOG_FILE="$TEST_WORK_DIRECTORY/single-page.log"
wget() {
    local wget_argument
    local output_document=""
    for wget_argument in "$@"; do
        case "$wget_argument" in
            --output-document=*) output_document="${wget_argument#--output-document=}" ;;
        esac
    done
    if [ -z "$output_document" ]; then
        return 1
    fi
    printf '<html><body>Java 25</body></html>\n' > "$output_document"
}
if ! (
    cd "$TEST_WORK_DIRECTORY"
    fetch_single_documentation_page \
        "https://blog.jetbrains.com/idea/2025/09/java-25-lts-and-intellij-idea/" \
        "$SINGLE_PAGE_STAGE" \
        "JetBrains Java 25 Blog" \
        3 \
        1 \
        false
); then
    fail_documentation_fetch_test "governed single-page fetch did not complete"
fi
unset -f wget
if [ ! -f "$SINGLE_PAGE_STAGE/java-25-lts-and-intellij-idea/index.html" ]; then
    fail_documentation_fetch_test "governed single-page fetch used the wrong projected path"
fi
if [ -f "$SINGLE_PAGE_STAGE/unrelated.html" ]; then
    fail_documentation_fetch_test "governed single-page fetch retained an unrelated resumed page"
fi

SPRING_AI_MIXED_STAGE="$TEST_WORK_DIRECTORY/spring-ai-mixed-stage"
mkdir -p "$SPRING_AI_MIXED_STAGE"
printf '<html><meta name="version" content="1.1.8"></html>\n' > "$SPRING_AI_MIXED_STAGE/stable.html"
printf '<html><meta name="version" content="2.0.0"></html>\n' > "$SPRING_AI_MIXED_STAGE/prohibited.html"
if validate_staged_documentation_mirror \
    "$SPRING_AI_MIXED_STAGE" \
    "Spring AI mixed-version fixture" \
    2 \
    '<meta name="version" content="1\.1\.8"' \
    '<meta name="version" content="(2\.|[^\"]*SNAPSHOT)'; then
    fail_documentation_fetch_test "mixed Spring AI stable and prohibited identities were accepted"
fi

KOTLIN_PINNED_STAGE="$TEST_WORK_DIRECTORY/kotlin-pinned-stage"
mkdir -p "$KOTLIN_PINNED_STAGE"
printf '<html><body><p>The currently released version is 2.4.10, published on July 14, 2026.</p></body></html>\n' \
    > "$KOTLIN_PINNED_STAGE/faq.html"
if ! python3 "$SCRIPT_DIR/documentation_seed.py" \
    --validate-published-identity \
    --root "$KOTLIN_PINNED_STAGE" \
    --required-page "faq.html" \
    --required-text "The currently released version is 2.4.10, published on July 14, 2026."; then
    fail_documentation_fetch_test "exact Kotlin 2.4.10 publication identity was rejected"
fi
printf '<html><body><p>The currently released version is 2.4.20, published later.</p></body></html>\n' \
    > "$KOTLIN_PINNED_STAGE/faq.html"
if python3 "$SCRIPT_DIR/documentation_seed.py" \
    --validate-published-identity \
    --root "$KOTLIN_PINNED_STAGE" \
    --required-page "faq.html" \
    --required-text "The currently released version is 2.4.10, published on July 14, 2026." \
    > /dev/null 2>&1; then
    fail_documentation_fetch_test "a newer rolling Kotlin publication was accepted as 2.4.10"
fi

SPRING_AI_PINNED_STAGE="$TEST_WORK_DIRECTORY/spring-ai-pinned-stage"
mkdir -p "$SPRING_AI_PINNED_STAGE"
printf '<html><head><meta name="version" content="1.1.8"></head></html>\n' \
    > "$SPRING_AI_PINNED_STAGE/index.html"
printf '<html><head><meta name="version" content="1.1.8"></head></html>\n' \
    > "$SPRING_AI_PINNED_STAGE/section.html"
if ! python3 "$SCRIPT_DIR/documentation_seed.py" \
    --validate-published-identity \
    --root "$SPRING_AI_PINNED_STAGE" \
    --expected-meta-version "1.1.8"; then
    fail_documentation_fetch_test "homogeneous Spring AI 1.1.8 metadata was rejected"
fi
printf '<html><head><meta name="version" content="1.1.9"></head></html>\n' \
    > "$SPRING_AI_PINNED_STAGE/section.html"
if python3 "$SCRIPT_DIR/documentation_seed.py" \
    --validate-published-identity \
    --root "$SPRING_AI_PINNED_STAGE" \
    --expected-meta-version "1.1.8" \
    > /dev/null 2>&1; then
    fail_documentation_fetch_test "mixed Spring AI 1.1.x metadata was accepted"
fi

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
    log() {
        :
    }
    create_documentation_fetch_staging_directory() {
        mkdir -p "$STAGED_PUBLICATION_DIRECTORY"
        printf '<html>Rolling Reference stable</html>\n' > "$STAGED_PUBLICATION_DIRECTORY/index.html"
        printf '%s\n' "$STAGED_PUBLICATION_DIRECTORY"
    }
    fetch_docs_mirror() {
        cd - > /dev/null
    }
    publish_staged_documentation_mirror() {
        return 71
    }
    if fetch_source \
        --url "https://docs.example.invalid/reference/" \
        --mirror-path "rolling-reference" \
        --name "Rolling Reference" \
        --source-version "stable" \
        --identity-regex "Rolling Reference stable" \
        --cut-directories 0 \
        --minimum-html-files 1 \
        --superseded-mirror-path "rolling-reference/1.0" > /dev/null; then
        exit 1
    fi
    [ -d "$STAGED_PUBLICATION_DIRECTORY" ]
); then
    fail_documentation_fetch_test "staged publication failure was not propagated"
fi

printf 'PASS: explicit fetch options, pinned source selection, version rejection, and Java seed safety are wired.\n'
