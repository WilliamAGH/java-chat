#!/bin/bash

# Exercises explicit fetch option routing and scoped source selection without network access.

set -euo pipefail

TEST_SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_SCRIPT_DIRECTORY/.." && pwd)"
FETCH_SCRIPT="$PROJECT_ROOT/scripts/fetch_all_docs.sh"
FETCH_SOURCE_LIBRARY="$PROJECT_ROOT/scripts/lib/documentation_fetch_sources.sh"
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

assert_documentation_mirror_path_policy() {
    local policy_test_directory="$TEST_WORK_DIRECTORY/path-policy"
    mkdir -p "$policy_test_directory"
    printf '<html>Doppler</html>\n' > "$policy_test_directory/dns-made-easy.html"
    printf '<html>Doppler</html>\n' > "$policy_test_directory/secret-snapshots.html"
    validate_staged_documentation_mirror "$policy_test_directory" "Path Policy" 1 "Doppler"
    printf '<html>Doppler</html>\n' > "$policy_test_directory/reference-2.0-eap.html"
    if validate_staged_documentation_mirror "$policy_test_directory" "Path Policy" 1 "Doppler"; then
        fail_documentation_fetch_test "early-access documentation path was accepted"
    fi
    command rm "$policy_test_directory/reference-2.0-eap.html"
    printf '<html>Doppler</html>\n' > "$policy_test_directory/reference-SNAPSHOT.html"
    if validate_staged_documentation_mirror "$policy_test_directory" "Path Policy" 1 "Doppler"; then
        fail_documentation_fetch_test "snapshot documentation path was accepted"
    fi
}

write_java25_specification_byte_gate_stub() {
    local downloaded_specification_path="$1"
    local title_fragment="$2"
    printf '%s\n' \
        '%PDF-1.4' \
        "$title_fragment" \
        '%%EOF' > "$downloaded_specification_path"
}

write_java25_specification_parser_output() {
    local parsed_title="$1"
    local parsed_edition="$2"
    case "$parsed_title" in
        "The Java® Language Specification")
            printf '%s\n' "The Java® Language" "Specification" "$parsed_edition"
            ;;
        "The Java® Virtual Machine Specification")
            printf '%s\n' "The Java® Virtual" "Machine Specification" "$parsed_edition"
            ;;
        *)
            printf '%s\n%s\n' "$parsed_title" "$parsed_edition"
            ;;
    esac
}

set --
# shellcheck source=fetch_all_docs.sh
source "$FETCH_SCRIPT"

DOCS_ROOT="$TEST_DOCS_ROOT"
log() {
    :
}

assert_documentation_mirror_path_policy

TEXT_RESOURCE_TEST_PATH="$TEST_WORK_DIRECTORY/documentation-text-resource.txt"
printf '%s\n' "Cloudflare DNS" "R2 Object Storage" "Model Context Protocol (MCP)" \
    > "$TEXT_RESOURCE_TEST_PATH"
validate_documentation_text_resource \
    "$TEXT_RESOURCE_TEST_PATH" "Documentation Text Resource" 40 \
    "Cloudflare DNS" "R2 Object Storage" "Model Context Protocol (MCP)"
if validate_documentation_text_resource \
    "$TEXT_RESOURCE_TEST_PATH" "Documentation Text Resource" 1000 \
    "Cloudflare DNS" > /dev/null 2>&1; then
    fail_documentation_fetch_test "truncated completeness resource was accepted"
fi
if validate_documentation_text_resource \
    "$TEXT_RESOURCE_TEST_PATH" "Documentation Text Resource" 40 \
    "Workers AI" > /dev/null 2>&1; then
    fail_documentation_fetch_test "text resource missing required coverage was accepted"
fi

if ! (
    STABLE_SEED_TEST_ROOT="$TEST_WORK_DIRECTORY/stable-seed"
    mkdir -p "$STABLE_SEED_TEST_ROOT"
    printf '%s\n' 'https://docs.example.invalid/reference/' \
        > "$STABLE_SEED_TEST_ROOT/current-seed.txt"
    wget2() {
        local discovery_output_path=""
        while [ "$#" -gt 0 ]; do
            case "$1" in
                --output-document=*) discovery_output_path="${1#*=}" ;;
            esac
            shift
        done
        printf '%s\n' \
            '<?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>https://docs.example.invalid/reference/</loc></url></urlset>' \
            > "$discovery_output_path"
    }
    validate_stable_documentation_seed \
        --current-seed-file "$STABLE_SEED_TEST_ROOT/current-seed.txt" \
        --target-directory "$STABLE_SEED_TEST_ROOT" \
        --seed-document-type xml-sitemap \
        --seed-discovery-url 'https://docs.example.invalid/sitemap.xml' \
        --seed-source-prefix 'https://docs.example.invalid/' \
        --canonical-prefix 'https://docs.example.invalid/' \
        --seed-reject-regex '' \
        --cut-directories 0
    printf '%s\n' 'https://docs.example.invalid/stale/' \
        > "$STABLE_SEED_TEST_ROOT/current-seed.txt"
    if validate_stable_documentation_seed \
        --current-seed-file "$STABLE_SEED_TEST_ROOT/current-seed.txt" \
        --target-directory "$STABLE_SEED_TEST_ROOT" \
        --seed-document-type xml-sitemap \
        --seed-discovery-url 'https://docs.example.invalid/sitemap.xml' \
        --seed-source-prefix 'https://docs.example.invalid/' \
        --canonical-prefix 'https://docs.example.invalid/' \
        --seed-reject-regex '' \
        --cut-directories 0 > /dev/null 2>&1; then
        exit 1
    fi
); then
    fail_documentation_fetch_test "stable seed validation did not reject discovery drift"
fi

if grep -Fq -- '--retry-on-http-error' "$FETCH_SOURCE_LIBRARY"; then
    fail_documentation_fetch_test "documented Ubuntu Wget2 does not support --retry-on-http-error"
fi
if grep -Fq -- 'grep -P' "$FETCH_SOURCE_LIBRARY"; then
    fail_documentation_fetch_test "documentation fetch still requires GNU grep PCRE support"
fi
if ! grep -Fq -- '--matches-regex' "$FETCH_SOURCE_LIBRARY"; then
    fail_documentation_fetch_test "Cloudflare alias validation bypasses the portable Python regex boundary"
fi
if ! grep -Fq -- '--discovery-url "$seed_additional_discovery_url"' "$FETCH_SOURCE_LIBRARY"; then
    fail_documentation_fetch_test "additional discovery links do not resolve against their own document URL"
fi

fetch_discovered_documentation_seed() {
    printf '%s\n' "$@" > "$DISCOVERED_FETCH_CAPTURE"
    local captured_target_directory="$4"
    local generated_page_number
    for generated_page_number in 1 2 3 4 5 6 7; do
        printf '<html>Example Reference stable</html>\n' > "$captured_target_directory/page-$generated_page_number.html"
    done
    printf 'User-agent: *\n' > "$captured_target_directory/robots.txt"
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
    --canonical-prefix \
    "https://docs.example.invalid/reference/" \
    --target-dir \
    "$(dirname "$TEST_DOCS_ROOT")/.documentation-fetch-staging/reference.599af15c691cb0976ef8042aaaf54bb39c76fed2c030db21d93b263113606c4c.partial" \
    --name \
    "Example Reference" \
    --cut-directories \
    3 \
    --minimum-html-files \
    7 \
    --reject-regex \
    "/archive" \
    --partial-mirror-allowed \
    false \
    --seed-document-type \
    xml-sitemap \
    --seed-discovery-url \
    "https://docs.example.invalid/sitemap.xml" \
    --seed-source-prefix \
    "https://docs.example.invalid/reference/" \
    --seed-reject-regex \
    "" \
    --request-delay-seconds \
    0 \
    --seed-additional-discovery-url \
    ""

if [ -f "$TEST_DOCS_ROOT/example/reference/robots.txt" ]; then
    fail_documentation_fetch_test "published documentation retained a non-content fetch artifact"
fi
if ! find "$(dirname "$TEST_DOCS_ROOT")/.quarantine" -type f -name robots.txt -print -quit | grep -q .; then
    fail_documentation_fetch_test "non-content fetch artifact was not preserved in quarantine"
fi

QUARANTINE_FAILURE_STAGE="$TEST_WORK_DIRECTORY/quarantine-failure-stage"
mkdir -p "$QUARANTINE_FAILURE_STAGE"
printf 'first\n' > "$QUARANTINE_FAILURE_STAGE/first.txt"
printf 'second\n' > "$QUARANTINE_FAILURE_STAGE/second.txt"
MOVE_CALL_COUNT=0
mv() {
    MOVE_CALL_COUNT=$((MOVE_CALL_COUNT + 1))
    if [ "$MOVE_CALL_COUNT" -eq 2 ]; then
        return 1
    fi
    command mv "$@"
}
if quarantine_staged_non_html_files "$QUARANTINE_FAILURE_STAGE" "Quarantine Failure Test"; then
    fail_documentation_fetch_test "partial non-content quarantine was accepted"
fi
unset -f mv
if [ ! -f "$QUARANTINE_FAILURE_STAGE/first.txt" ] \
    || [ ! -f "$QUARANTINE_FAILURE_STAGE/second.txt" ]; then
    fail_documentation_fetch_test "failed non-content quarantine did not restore the complete staging tree"
fi

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
    '(^|/)(custom-frontend-app(/|$)|home\.html$|multiplatform/(compose-multiplatform|get-started)\.html$)|(^|/)([Ee][Aa][Pp]|[Ss][Nn][Aa][Pp][Ss][Hh][Oo][Tt])(/|(-[^/]+)?\.html$)|(^|/)[^/]*-([Ee][Aa][Pp]|[Ss][Nn][Aa][Pp][Ss][Hh][Oo][Tt])(-[^/]+)?\.html$' \
    --seed-document-type \
    xml-sitemap \
    --seed-discovery-url \
    "https://kotlinlang.org/sitemap.xml" \
    --seed-source-prefix \
    "https://kotlinlang.org/docs/"

if ! (
    run_documentation_fetch --doc-sets=doppler-guides > /dev/null
); then
    fail_documentation_fetch_test "named Doppler guides selection did not complete"
fi

assert_captured_arguments "$SELECTED_SOURCE_CAPTURE" \
    --url \
    "https://docs.doppler.com/docs/" \
    --mirror-path \
    "doppler/docs" \
    --name \
    "Doppler Guides" \
    --source-version \
    current \
    --identity-regex \
    Doppler \
    --cut-directories \
    1 \
    --minimum-html-files \
    200 \
    --seed-document-type \
    html-links \
    --seed-discovery-url \
    "https://docs.doppler.com/docs/start" \
    --seed-source-prefix \
    "https://docs.doppler.com/docs/" \
    --seed-reject-regex \
    '^https://docs\.doppler\.com/docs/(enclave-installation(-docker|-serverless)?|enclave-service-tokens)$' \
    --request-delay-seconds \
    1

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
    "https://archive.apache.org/dist/groovy/5.0.7/distribution/apache-groovy-docs-5.0.7.zip" \
    --mirror-path \
    "groovy/5.0.7" \
    --name \
    "Groovy 5.0.7 Documentation" \
    --source-version \
    "5.0.7" \
    --identity-regex \
    'Groovy.*5\.0\.7|5\.0\.7.*Groovy' \
    --required-identity-page \
    "core-introduction.html" \
    --required-identity-text \
    "version 5.0.7" \
    --cut-directories \
    0 \
    --minimum-html-files \
    40 \
    --archive-format \
    zip \
    --archive-publication-root \
    "groovy-5.0.7/html/documentation"

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
    "https://docs.scala-lang.org/scala3/reference/" \
    --seed-reject-regex \
    '/index\.html$'

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
    "https://quarkus.io/guides/" \
    --canonicalize-extensionless-directory-urls

QUARKUS_DISCOVERY_FIXTURE="$TEST_WORK_DIRECTORY/quarkus-guides.html"
QUARKUS_SEED_OUTPUT="$TEST_WORK_DIRECTORY/quarkus-seed.txt"
QUARKUS_MIRROR_OUTPUT="$TEST_WORK_DIRECTORY/quarkus-mirror-paths.txt"
printf '%s\n' '<a href="/guides/aesh">Aesh</a><a href="/guides/stylesheet/config.css">CSS</a>' \
    > "$QUARKUS_DISCOVERY_FIXTURE"
python3 "$SCRIPT_DIR/documentation_seed.py" \
    --document-type html-links \
    --input "$QUARKUS_DISCOVERY_FIXTURE" \
    --discovery-url "https://quarkus.io/guides/" \
    --source-prefix "https://quarkus.io/guides/" \
    --canonical-prefix "https://quarkus.io/guides/" \
    --output "$QUARKUS_SEED_OUTPUT" \
    --mirror-path-output "$QUARKUS_MIRROR_OUTPUT" \
    --cut-directories 1 \
    --canonicalize-extensionless-directory-urls
if ! grep -Fxq 'https://quarkus.io/guides/aesh/' "$QUARKUS_SEED_OUTPUT" \
    || ! grep -Fxq 'https://quarkus.io/guides/stylesheet/config.css' "$QUARKUS_SEED_OUTPUT" \
    || ! grep -Fxq 'aesh/index.html' "$QUARKUS_MIRROR_OUTPUT" \
    || ! grep -Fxq 'stylesheet/config.css.html' "$QUARKUS_MIRROR_OUTPUT"; then
    fail_documentation_fetch_test "Quarkus extensionless guides did not project onto canonical directory URLs"
fi

assert_current_documentation_source_dispatch() {
    local documentation_source_identifier="$1"
    local expected_citation_base="$2"
    local expected_mirror_path="$3"
    local expected_discovery_url="$4"
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
        fetch_named_official_source "$documentation_source_identifier"
    ); then
        fail_documentation_fetch_test "current documentation source dispatch failed: $documentation_source_identifier"
    fi
    if ! grep -Fxq -- "$expected_citation_base" "$ENVIRONMENT_OVERRIDE_CAPTURE" \
        || ! grep -Fxq -- "$expected_mirror_path" "$ENVIRONMENT_OVERRIDE_CAPTURE" \
        || ! grep -Fxq -- "$expected_discovery_url" "$ENVIRONMENT_OVERRIDE_CAPTURE"; then
        fail_documentation_fetch_test "current documentation source dispatch lost its canonical boundary: $documentation_source_identifier"
    fi
}

assert_current_documentation_source_dispatch \
    anthropic-api \
    "https://platform.claude.com/docs/en/" \
    "anthropic/api" \
    "https://platform.claude.com/sitemap.xml"
if ! grep -Fxq -- '^https://platform\.claude\.com/docs/en/home$' "$ENVIRONMENT_OVERRIDE_CAPTURE"; then
    fail_documentation_fetch_test "Anthropic API dispatch retained its non-content landing shell"
fi
assert_current_documentation_source_dispatch \
    claude-code \
    "https://code.claude.com/docs/en/" \
    "anthropic/claude-code" \
    "https://code.claude.com/sitemap.xml"
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
    fetch_named_official_source traefik
); then
    fail_documentation_fetch_test "canonical Traefik documentation dispatch did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://doc.traefik.io/traefik/" \
    --mirror-path \
    traefik \
    --name \
    "Traefik Proxy Documentation" \
    --source-version \
    current \
    --identity-regex \
    "Traefik Proxy" \
    --cut-directories \
    1 \
    --minimum-html-files \
    280 \
    --seed-document-type \
    xml-sitemap \
    --seed-discovery-url \
    "https://doc.traefik.io/sitemap.xml" \
    --seed-source-prefix \
    "https://doc.traefik.io/traefik/"
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
    fetch_named_official_source amp-code
); then
    fail_documentation_fetch_test "canonical Amp Code documentation dispatch did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://ampcode.com/" \
    --mirror-path \
    amp-code \
    --name \
    "Amp Code CLI Manual" \
    --source-version \
    current \
    --identity-regex \
    Amp \
    --cut-directories \
    0 \
    --minimum-html-files \
    8 \
    --seed-document-type \
    html-links \
    --seed-discovery-url \
    "https://ampcode.com/manual" \
    --seed-source-prefix \
    "https://ampcode.com/" \
    --seed-reject-regex \
    '^https://ampcode\.com/(?:manual/appendix/legacy-permissions-rules\.txt$|(?!manual(?:/|$)).*)' \
    --seed-url \
    "https://ampcode.com/manual/orbs/oidc" \
    --seed-url \
    "https://ampcode.com/manual/sdk/python" \
    --seed-url \
    "https://ampcode.com/manual/sdk/typescript"
assert_current_documentation_source_dispatch \
    tinker \
    "https://tinker-docs.thinkingmachines.ai/" \
    tinker \
    "https://tinker-docs.thinkingmachines.ai/sitemap.xml"
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
    fetch_named_official_source porkbun
); then
    fail_documentation_fetch_test "canonical Porkbun documentation dispatch did not complete"
fi
for required_porkbun_argument in \
    "https://porkbun.com/api/json/v3/documentation" \
    "https://porkbun.com/llms-full.txt" \
    "https://porkbun.com/llms.txt" \
    "npx -y @porkbunllc/mcp-server" \
    "https://porkbun.com/llms/agent-setup"; do
    if ! grep -Fxq -- "$required_porkbun_argument" "$ENVIRONMENT_OVERRIDE_CAPTURE"; then
        fail_documentation_fetch_test "Porkbun dispatch lost required API/MCP coverage: $required_porkbun_argument"
    fi
done
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
    fetch_named_official_source porkbun-mcp
); then
    fail_documentation_fetch_test "canonical Porkbun MCP documentation dispatch did not complete"
fi
for required_porkbun_mcp_argument in \
    "https://github.com/oborseth/Porkbun-MCP/blob/64e8b4f4caad75e99333733bca5f2987afee3c75/README.md" \
    "https://raw.githubusercontent.com/oborseth/Porkbun-MCP/64e8b4f4caad75e99333733bca5f2987afee3c75/README.md" \
    "0.17.1-64e8b4f4caad" \
    "npx -y @porkbunllc/mcp-server"; do
    if ! grep -Fxq -- "$required_porkbun_mcp_argument" "$ENVIRONMENT_OVERRIDE_CAPTURE"; then
        fail_documentation_fetch_test "Porkbun MCP dispatch lost pinned official coverage: $required_porkbun_mcp_argument"
    fi
done
assert_current_documentation_source_dispatch \
    cloudflare \
    "https://developers.cloudflare.com/" \
    cloudflare \
    "https://developers.cloudflare.com/sitemap-0.xml"
for required_cloudflare_argument in \
    "https://developers.cloudflare.com/sitemap-index.xml" \
    "https://developers.cloudflare.com/llms-full.txt" \
    "8199" \
    "--validate-cloudflare-seed-aliases" \
    "# Cloudflare DNS" \
    "# R2 Object Storage" \
    "## Wrangler usage" \
    "# AI Gateway" \
    "## Workers AI" \
    "Model Context Protocol (MCP)"; do
    if ! grep -Fxq -- "$required_cloudflare_argument" "$ENVIRONMENT_OVERRIDE_CAPTURE"; then
        fail_documentation_fetch_test "Cloudflare dispatch lost required platform coverage: $required_cloudflare_argument"
    fi
done

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    DEFAULT_SOURCE_CAPTURE="$TEST_WORK_DIRECTORY/default-source-capture"
    fetch_named_official_source() {
        printf '%s\n' "$1" >> "$DEFAULT_SOURCE_CAPTURE"
    }
    fetch_all_official_sources
    grep -Fxq porkbun "$DEFAULT_SOURCE_CAPTURE" \
        && grep -Fxq porkbun-mcp "$DEFAULT_SOURCE_CAPTURE" \
        && grep -Fxq cloudflare "$DEFAULT_SOURCE_CAPTURE"
); then
    fail_documentation_fetch_test "default documentation inventory omitted a platform source"
fi

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

assert_current_documentation_source_dispatch \
    lombok-1.18.46-reference \
    "https://projectlombok.org/features/" \
    "lombok/1.18.46/reference" \
    "https://projectlombok.org/features/"

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://projectlombok.org/features/" \
    --mirror-path \
    "lombok/1.18.46/reference" \
    --name \
    "Lombok 1.18.46 Feature Reference" \
    --source-version \
    "1.18.46" \
    --identity-regex \
    "Project Lombok" \
    --cut-directories \
    1 \
    --minimum-html-files \
    30 \
    --seed-document-type \
    html-links \
    --seed-discovery-url \
    "https://projectlombok.org/features/" \
    --seed-additional-discovery-url \
    "https://projectlombok.org/features/experimental/" \
    --seed-source-prefix \
    "https://projectlombok.org/features/" \
    --seed-reject-regex \
    '^https://projectlombok\.org/features/(?:experimental/)?all$'

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
    5000 \
    --java25-specification-pdfs

JAVA25_INVALID_PDF="$TEST_WORK_DIRECTORY/java25-invalid.pdf"
printf '<html>not a PDF</html>\n' > "$JAVA25_INVALID_PDF"
if validate_java25_specification_pdf \
    "$JAVA25_INVALID_PDF" \
    "invalid Java 25 specification" \
    "Language Specification" \
    "The Java® Language Specification"; then
    fail_documentation_fetch_test "non-PDF Java 25 specification content was accepted"
fi

JAVA25_PDF_PARSER_INPUT="$TEST_WORK_DIRECTORY/java25-parser-input.pdf"
write_java25_specification_byte_gate_stub \
    "$JAVA25_PDF_PARSER_INPUT" "Language Specification"
if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() {
        :
    }
    mutool() {
        case "$#" in
            8)
                [ "$1" = "draw" ] \
                    && [ "$2" = "-q" ] \
                    && [ "$3" = "-F" ] \
                    && [ "$4" = "txt" ] \
                    && [ "$5" = "-o" ] \
                    && [ "$6" = "-" ] \
                    && [ "$8" = "1" ] \
                    || return 1
                case "$JAVA25_PDF_PARSER_TEST_CASE" in
                    java-se24)
                        write_java25_specification_parser_output \
                            "The Java® Language Specification" "Java SE 24 Edition"
                        ;;
                    wrong-specification)
                        write_java25_specification_parser_output \
                            "The Java® Virtual Machine Specification" "Java SE 25 Edition"
                        ;;
                    parse-failure) return 69 ;;
                    full-parse-failure)
                        write_java25_specification_parser_output \
                            "The Java® Language Specification" "Java SE 25 Edition"
                        ;;
                    *) return 1 ;;
                esac
                ;;
            7)
                [ "$1" = "draw" ] \
                    && [ "$2" = "-q" ] \
                    && [ "$3" = "-F" ] \
                    && [ "$4" = "txt" ] \
                    && [ "$5" = "-o" ] \
                    && [ "$6" = "/dev/null" ] \
                    || return 1
                [ "$JAVA25_PDF_PARSER_TEST_CASE" != "full-parse-failure" ] || return 70
                ;;
            *) return 1 ;;
        esac
    }
    JAVA25_PDF_PARSER_TEST_CASE="java-se24"
    if validate_java25_specification_pdf \
        "$JAVA25_PDF_PARSER_INPUT" \
        "Java SE 24 language specification" \
        "Language Specification" \
        "The Java® Language Specification"; then
        exit 1
    fi
    JAVA25_PDF_PARSER_TEST_CASE="wrong-specification"
    if validate_java25_specification_pdf \
        "$JAVA25_PDF_PARSER_INPUT" \
        "wrong Java 25 specification" \
        "Language Specification" \
        "The Java® Language Specification"; then
        exit 1
    fi
    JAVA25_PDF_PARSER_TEST_CASE="parse-failure"
    if validate_java25_specification_pdf \
        "$JAVA25_PDF_PARSER_INPUT" \
        "unparseable Java 25 specification" \
        "Language Specification" \
        "The Java® Language Specification"; then
        exit 1
    fi
    JAVA25_PDF_PARSER_TEST_CASE="full-parse-failure"
    if validate_java25_specification_pdf \
        "$JAVA25_PDF_PARSER_INPUT" \
        "partially parseable Java 25 specification" \
        "Language Specification" \
        "The Java® Language Specification"; then
        exit 1
    fi
); then
    fail_documentation_fetch_test "Java 25 parser wrapper accepted a rejected specification"
fi

JAVA25_MISSING_PARSER_LOG="$TEST_WORK_DIRECTORY/java25-missing-parser.log"
if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    log() {
        printf '%s\n' "$1" >> "$JAVA25_MISSING_PARSER_LOG"
    }
    command() {
        if [ "$#" -eq 2 ] && [ "$1" = "-v" ] && [ "$2" = "mutool" ]; then
            return 1
        fi
        builtin command "$@"
    }
    JAVA25_MISSING_PARSER_WGET2_CALLS=0
    wget2() {
        JAVA25_MISSING_PARSER_WGET2_CALLS=$((JAVA25_MISSING_PARSER_WGET2_CALLS + 1))
        return 1
    }
    if fetch_java25_specification_pdfs \
        "$TEST_WORK_DIRECTORY/java25-missing-parser-stage" "Java 25 Complete API"; then
        exit 1
    fi
    [ "$JAVA25_MISSING_PARSER_WGET2_CALLS" -eq 0 ]
); then
    fail_documentation_fetch_test "missing mutool did not fail before Java 25 specification downloads"
fi
if ! grep -Fq -- "brew install mupdf" "$JAVA25_MISSING_PARSER_LOG" \
    || ! grep -Fq -- "apt install mupdf-tools" "$JAVA25_MISSING_PARSER_LOG" \
    || ! grep -Fq -- "dnf install mupdf" "$JAVA25_MISSING_PARSER_LOG"; then
    fail_documentation_fetch_test "missing mutool did not report its installation command"
fi

JAVA25_PDF_SUCCESS_ROOT="$TEST_WORK_DIRECTORY/java25-pdf-success"
JAVA25_PDF_SUCCESS_CAPTURE="$JAVA25_PDF_SUCCESS_ROOT/wget2-arguments"
JAVA25_PDF_SUCCESS_PARSER_CAPTURE="$JAVA25_PDF_SUCCESS_ROOT/mutool-arguments"
if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    DOCS_ROOT="$JAVA25_PDF_SUCCESS_ROOT/data/docs"
    LOG_FILE="$JAVA25_PDF_SUCCESS_ROOT/fetch.log"
    JAVA25_PDF_SUCCESS_STAGE="$JAVA25_PDF_SUCCESS_ROOT/stage"
    log() {
        :
    }
    create_documentation_fetch_staging_directory() {
        mkdir -p "$JAVA25_PDF_SUCCESS_ROOT" "$JAVA25_PDF_SUCCESS_STAGE"
        printf '%s\n' "$JAVA25_PDF_SUCCESS_STAGE"
    }
    generate_javadoc_seed() {
        :
    }
    reconcile_javadoc_seed_mirror() {
        :
    }
    fetch_javadoc_seed() {
        printf '<html>Java 25 API</html>\n' > "$1/index.html"
        cd - > /dev/null
    }
    validate_staged_documentation_identity() {
        :
    }
    wget2() {
        local wget_argument
        local output_document=""
        local requested_url=""
        printf '%s\n' "$@" >> "$JAVA25_PDF_SUCCESS_CAPTURE"
        for wget_argument in "$@"; do
            case "$wget_argument" in
                --output-document=*) output_document="${wget_argument#--output-document=}" ;;
                --*) ;;
                *) requested_url="$wget_argument" ;;
            esac
        done
        case "$requested_url" in
            https://docs.oracle.com/javase/specs/jls/se25/jls25.pdf)
                write_java25_specification_byte_gate_stub \
                    "$output_document" "Language Specification"
                ;;
            https://docs.oracle.com/javase/specs/jvms/se25/jvms25.pdf)
                write_java25_specification_byte_gate_stub \
                    "$output_document" "Virtual Machine Specification"
                ;;
            *) return 1 ;;
        esac
    }
    mutool() {
        case "$#" in
            8)
                [ "$1" = "draw" ] \
                    && [ "$2" = "-q" ] \
                    && [ "$3" = "-F" ] \
                    && [ "$4" = "txt" ] \
                    && [ "$5" = "-o" ] \
                    && [ "$6" = "-" ] \
                    && [ "$8" = "1" ] \
                    || return 1
                printf '%s\n' "$@" >> "$JAVA25_PDF_SUCCESS_PARSER_CAPTURE"
                case "$7" in
                    *jls25.pdf*)
                        write_java25_specification_parser_output \
                            "The Java® Language Specification" "Java SE 25 Edition"
                        ;;
                    *jvms25.pdf*)
                        write_java25_specification_parser_output \
                            "The Java® Virtual Machine Specification" "Java SE 25 Edition"
                        ;;
                    *) return 1 ;;
                esac
                ;;
            7)
                [ "$1" = "draw" ] \
                    && [ "$2" = "-q" ] \
                    && [ "$3" = "-F" ] \
                    && [ "$4" = "txt" ] \
                    && [ "$5" = "-o" ] \
                    && [ "$6" = "/dev/null" ] \
                    || return 1
                printf '%s\n' "$@" >> "$JAVA25_PDF_SUCCESS_PARSER_CAPTURE"
                case "$7" in
                    *jls25.pdf*|*jvms25.pdf*) return 0 ;;
                    *) return 1 ;;
                esac
                ;;
            *) return 1 ;;
        esac
    }
    fetch_source \
        --java-release 25 \
        --java25-specification-pdfs \
        --url "https://docs.oracle.com/en/java/javase/25/docs/api/" \
        --mirror-path "java/java25-complete" \
        --name "Java 25 Complete API" \
        --source-version "25-ga" \
        --cut-directories 5 \
        --minimum-html-files 1
); then
    fail_documentation_fetch_test "Java 25 source refresh did not publish both specification PDFs"
fi
for JAVA25_SPECIFICATION_PATH in \
    "$JAVA25_PDF_SUCCESS_ROOT/data/docs/java/java25-complete/docs.oracle.com/javase/specs/jls/se25/jls25.pdf" \
    "$JAVA25_PDF_SUCCESS_ROOT/data/docs/java/java25-complete/docs.oracle.com/javase/specs/jvms/se25/jvms25.pdf"; do
    [ -f "$JAVA25_SPECIFICATION_PATH" ] \
        || fail_documentation_fetch_test "Java 25 source refresh omitted $JAVA25_SPECIFICATION_PATH"
done
grep -Fxq -- "--max-redirect=0" "$JAVA25_PDF_SUCCESS_CAPTURE" \
    || fail_documentation_fetch_test "Java 25 specification fetch accepted redirects"
grep -Fxq -- "https://docs.oracle.com/javase/specs/jls/se25/jls25.pdf" "$JAVA25_PDF_SUCCESS_CAPTURE" \
    || fail_documentation_fetch_test "Java 25 source refresh did not request the canonical JLS 25 PDF"
grep -Fxq -- "https://docs.oracle.com/javase/specs/jvms/se25/jvms25.pdf" "$JAVA25_PDF_SUCCESS_CAPTURE" \
    || fail_documentation_fetch_test "Java 25 source refresh did not request the canonical JVMS 25 PDF"
if [ "$(grep -Fxc -- "draw" "$JAVA25_PDF_SUCCESS_PARSER_CAPTURE")" -ne 8 ] \
    || [ "$(grep -Fxc -- "/dev/null" "$JAVA25_PDF_SUCCESS_PARSER_CAPTURE")" -ne 4 ]; then
    fail_documentation_fetch_test "Java 25 source refresh did not fully parse each staged and installed specification"
fi

JAVA25_PDF_FAILURE_ROOT="$TEST_WORK_DIRECTORY/java25-pdf-parse-failure"
JAVA25_PDF_FAILURE_PARSER_CAPTURE="$JAVA25_PDF_FAILURE_ROOT/mutool-arguments"
if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    DOCS_ROOT="$JAVA25_PDF_FAILURE_ROOT/data/docs"
    LOG_FILE="$JAVA25_PDF_FAILURE_ROOT/fetch.log"
    JAVA25_PDF_FAILURE_STAGE="$JAVA25_PDF_FAILURE_ROOT/stage"
    JAVA25_PDF_FAILURE_TARGET="$DOCS_ROOT/java/java25-complete"
    mkdir -p "$JAVA25_PDF_FAILURE_TARGET"
    printf '<html>active Java 25 API</html>\n' > "$JAVA25_PDF_FAILURE_TARGET/index.html"
    log() {
        :
    }
    create_documentation_fetch_staging_directory() {
        mkdir -p "$JAVA25_PDF_FAILURE_STAGE"
        printf '%s\n' "$JAVA25_PDF_FAILURE_STAGE"
    }
    generate_javadoc_seed() {
        :
    }
    reconcile_javadoc_seed_mirror() {
        :
    }
    fetch_javadoc_seed() {
        printf '<html>Java 25 API</html>\n' > "$1/index.html"
        cd - > /dev/null
    }
    validate_staged_documentation_identity() {
        :
    }
    wget2() {
        local wget_argument
        local output_document=""
        local requested_url=""
        for wget_argument in "$@"; do
            case "$wget_argument" in
                --output-document=*) output_document="${wget_argument#--output-document=}" ;;
                --*) ;;
                *) requested_url="$wget_argument" ;;
            esac
        done
        case "$requested_url" in
            https://docs.oracle.com/javase/specs/jls/se25/jls25.pdf)
                write_java25_specification_byte_gate_stub \
                    "$output_document" "Language Specification"
                ;;
            https://docs.oracle.com/javase/specs/jvms/se25/jvms25.pdf)
                write_java25_specification_byte_gate_stub \
                    "$output_document" "Virtual Machine Specification"
                ;;
            *) return 1 ;;
        esac
    }
    mutool() {
        case "$#" in
            8)
                [ "$1" = "draw" ] \
                    && [ "$2" = "-q" ] \
                    && [ "$3" = "-F" ] \
                    && [ "$4" = "txt" ] \
                    && [ "$5" = "-o" ] \
                    && [ "$6" = "-" ] \
                    && [ "$8" = "1" ] \
                    || return 1
                printf '%s\n' "$@" >> "$JAVA25_PDF_FAILURE_PARSER_CAPTURE"
                case "$7" in
                    *jls25.pdf*)
                        write_java25_specification_parser_output \
                            "The Java® Language Specification" "Java SE 25 Edition"
                        ;;
                    *jvms25.pdf*)
                        write_java25_specification_parser_output \
                            "The Java® Virtual Machine Specification" "Java SE 25 Edition"
                        ;;
                    *) return 1 ;;
                esac
                ;;
            7)
                [ "$1" = "draw" ] \
                    && [ "$2" = "-q" ] \
                    && [ "$3" = "-F" ] \
                    && [ "$4" = "txt" ] \
                    && [ "$5" = "-o" ] \
                    && [ "$6" = "/dev/null" ] \
                    || return 1
                printf '%s\n' "$@" >> "$JAVA25_PDF_FAILURE_PARSER_CAPTURE"
                case "$7" in
                    *jls25.pdf*) return 70 ;;
                    *jvms25.pdf*) return 0 ;;
                    *) return 1 ;;
                esac
                ;;
            *) return 1 ;;
        esac
    }
    if fetch_source \
        --java-release 25 \
        --java25-specification-pdfs \
        --url "https://docs.oracle.com/en/java/javase/25/docs/api/" \
        --mirror-path "java/java25-complete" \
        --name "Java 25 Complete API" \
        --source-version "25-ga" \
        --cut-directories 5 \
        --minimum-html-files 1; then
        exit 1
    fi
    [ "$(< "$JAVA25_PDF_FAILURE_TARGET/index.html")" = "<html>active Java 25 API</html>" ]
    [ ! -e "$JAVA25_PDF_FAILURE_TARGET/docs.oracle.com/javase/specs/jls/se25/jls25.pdf" ]
    [ ! -e "$JAVA25_PDF_FAILURE_TARGET/docs.oracle.com/javase/specs/jvms/se25/jvms25.pdf" ]
); then
    fail_documentation_fetch_test "Java 25 specification parse failure replaced the active mirror"
fi
if [ "$(grep -Fxc -- "/dev/null" "$JAVA25_PDF_FAILURE_PARSER_CAPTURE")" -ne 1 ] \
    || ! grep -Fq -- "jls25.pdf" "$JAVA25_PDF_FAILURE_PARSER_CAPTURE" \
    || grep -Fq -- "jvms25.pdf" "$JAVA25_PDF_FAILURE_PARSER_CAPTURE"; then
    fail_documentation_fetch_test "Java 25 specification pair continued after the JLS full-parse failure"
fi

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    DOCS_ROOT="$TEST_WORK_DIRECTORY/resumed-publication/data/docs"
    LOG_FILE="$TEST_WORK_DIRECTORY/resumed-publication.log"
    RESUMED_STAGING_DIRECTORY="$TEST_WORK_DIRECTORY/resumed-publication/staging"
    RESUMED_PUBLICATION_CAPTURE="$TEST_WORK_DIRECTORY/resumed-publication/published"
    PROJECT_ROOT="$TEST_WORK_DIRECTORY/resumed-publication/project"
    mkdir -p "$PROJECT_ROOT"
    log() {
        :
    }
    create_documentation_fetch_staging_directory() {
        mkdir -p "$RESUMED_STAGING_DIRECTORY"
        printf '<html>Resumed Reference stable</html>\n' > "$RESUMED_STAGING_DIRECTORY/index.html"
        printf '%s\n' "$RESUMED_STAGING_DIRECTORY"
    }
    fetch_discovered_documentation_seed() {
        exit 1
    }
    fetch_documentation_archive() {
        exit 1
    }
    publish_staged_documentation_mirror() {
        printf '%s\n' "$@" > "$RESUMED_PUBLICATION_CAPTURE"
    }
    fetch_source \
        --url "https://docs.example.invalid/reference/" \
        --mirror-path "resumed-reference" \
        --name "Resumed Reference" \
        --source-version "stable" \
        --identity-regex "Resumed Reference stable" \
        --cut-directories 0 \
        --minimum-html-files 1 \
        --archive-format zip > /dev/null
    [ -s "$RESUMED_PUBLICATION_CAPTURE" ]
); then
    fail_documentation_fetch_test "complete resumed archive staging was discarded instead of published"
fi

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
    fetch_named_official_source oracle-java25-release-notes
); then
    fail_documentation_fetch_test "governed Oracle Java 25 release-notes dispatch did not complete"
fi

assert_captured_arguments "$ENVIRONMENT_OVERRIDE_CAPTURE" \
    fetch_source \
    --url \
    "https://www.oracle.com/java/technologies/javase/25-relnote-issues.html" \
    --mirror-path \
    "oracle/javase" \
    --name \
    "Java 25 Release Notes Issues" \
    --source-version \
    "25-ga" \
    --identity-regex \
    'Java.*25|25.*Java' \
    --cut-directories \
    3 \
    --minimum-html-files \
    1 \
    --single-page

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
SINGLE_PAGE_WGET2_CAPTURE="$TEST_WORK_DIRECTORY/single-page-wget2"
mkdir -p "$SINGLE_PAGE_STAGE"
printf '<html><body>stale recursive page</body></html>\n' > "$SINGLE_PAGE_STAGE/unrelated.html"
LOG_FILE="$TEST_WORK_DIRECTORY/single-page.log"
wget2() {
    local wget_argument
    local output_document=""
    printf '%s\n' "$@" > "$SINGLE_PAGE_WGET2_CAPTURE"
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
        "https://www.oracle.com/java/technologies/javase/25-relnote-issues.html" \
        "$SINGLE_PAGE_STAGE" \
        "Java 25 Release Notes Issues" \
        3 \
        1 \
        false
); then
    fail_documentation_fetch_test "governed single-page fetch did not complete"
fi
unset -f wget2
if [ ! -f "$SINGLE_PAGE_STAGE/25-relnote-issues.html" ]; then
    fail_documentation_fetch_test "governed single-page fetch used the wrong projected path"
fi
if [ -f "$SINGLE_PAGE_STAGE/unrelated.html" ]; then
    fail_documentation_fetch_test "governed single-page fetch retained an unrelated resumed page"
fi
if ! grep -Fqx -- "--user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" "$SINGLE_PAGE_WGET2_CAPTURE"; then
    fail_documentation_fetch_test "governed single-page fetch did not use the verified browser request identity"
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
    JAVA_POST_FETCH_WGET2_ARGUMENTS="$TEST_WORK_DIRECTORY/java-post-fetch-wget2-arguments"
    mkdir -p "$JAVA_POST_FETCH_TARGET_DIRECTORY"
    printf '%s\n%s\n' \
        "https://docs.example.invalid/Record.html" \
        "https://docs.example.invalid/String.html" \
        > "$JAVA_POST_FETCH_TARGET_DIRECTORY/.javadoc-seed.txt"
    log() {
        :
    }
    wget2() {
        printf '%s\n' "$@" > "$JAVA_POST_FETCH_WGET2_ARGUMENTS"
        printf '<html>Record</html>\n' > "$JAVA_POST_FETCH_TARGET_DIRECTORY/Record.html"
    }
    write_documentation_seed_mirror_paths() {
        printf '%s\n%s\n' Record.html String.html > "$4"
    }
    if (
        cd "$JAVA_POST_FETCH_TARGET_DIRECTORY"
        fetch_javadoc_seed \
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
    grep -Fxq -- "--max-redirect=0" "$JAVA_POST_FETCH_WGET2_ARGUMENTS"
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
        > "$JAVA_QUARANTINE_TARGET_DIRECTORY/.javadoc-seed.txt"
    printf '<html>Canonical</html>\n' \
        > "$JAVA_QUARANTINE_TARGET_DIRECTORY/java.base/java/lang/Record.html"
    printf '<html>Stale</html>\n' > "$JAVA_QUARANTINE_TARGET_DIRECTORY/Record.html"
    log() {
        :
    }
    write_documentation_seed_mirror_paths() {
        printf '%s\n' "java.base/java/lang/Record.html" > "$4"
    }
    reconcile_javadoc_seed_mirror \
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
    printf 'retained-seed\n' > "$JAVA_SEED_TARGET_DIRECTORY/.javadoc-seed.txt"
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
    if generate_javadoc_seed \
        "https://docs.example.invalid/" \
        "$JAVA_SEED_TARGET_DIRECTORY" > /dev/null 2>&1; then
        exit 1
    fi
    [ "$(< "$JAVA_SEED_TARGET_DIRECTORY/.javadoc-seed.txt")" = "retained-seed" ]
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

if ! (
    set --
    # shellcheck source=fetch_all_docs.sh
    source "$FETCH_SCRIPT"
    DOCS_ROOT="$TEST_WORK_DIRECTORY/sentinel-resume/data/docs"
    LOG_FILE="$TEST_WORK_DIRECTORY/sentinel-resume.log"
    SENTINEL_RESUME_DIRECTORY="$TEST_WORK_DIRECTORY/sentinel-resume/staging"
    mkdir -p "$SENTINEL_RESUME_DIRECTORY"
    printf '<html>resume progress</html>\n' > "$SENTINEL_RESUME_DIRECTORY/progress.html"
    log() {
        :
    }
    create_documentation_fetch_staging_directory() {
        printf '%s\n' "$SENTINEL_RESUME_DIRECTORY"
    }
    validate_documentation_llm_sentinel() {
        return 1
    }
    if fetch_source \
        --url "https://docs.example.invalid/reference/" \
        --mirror-path "sentinel-resume" \
        --name "Sentinel Resume" \
        --source-version "stable" \
        --identity-regex "Sentinel Resume" \
        --cut-directories 0 \
        --minimum-html-files 1 \
        --llm-sentinel-url "https://docs.example.invalid/llms.txt" \
        --minimum-llm-sentinel-bytes 1 > /dev/null 2>&1; then
        exit 1
    fi
    [ -s "$SENTINEL_RESUME_DIRECTORY/progress.html" ]
); then
    fail_documentation_fetch_test "sentinel failure deleted resumable staging progress"
fi

printf 'PASS: explicit fetch options, pinned source selection, version rejection, and Java seed safety are wired.\n'
