#!/bin/bash

DOCUMENTATION_FETCH_DEFAULT_MINIMUM_HTML_FILES='1'
DOCUMENTATION_FETCH_DEFAULT_REJECT_REGEX=''
DOCUMENTATION_FETCH_DEFAULT_ALLOW_PARTIAL='false'

documentation_fetch_cut_directories() {
    local fetch_url="$1"
    local authority_and_path="${fetch_url#https://}"
    local fetch_path="${authority_and_path#*/}"
    fetch_path="${fetch_path%/}"
    if [ -z "$fetch_path" ]; then
        printf '0\n'
        return 0
    fi

    local IFS='/'
    local -a fetch_path_segments
    read -r -a fetch_path_segments <<< "$fetch_path"
    printf '%s\n' "${#fetch_path_segments[@]}"
}

append_manifest_documentation_fetch_sources() {
    local documentation_source_projection
    for documentation_source_projection in "${DOCUMENTATION_SOURCE_PROJECTIONS[@]}"; do
        local fetchUrl
        local citationBaseUrl
        local relativeMirrorPath
        local displayName
        local docSet
        local sourceKind
        local docType
        local docVersion
        IFS='|' read -r fetchUrl citationBaseUrl relativeMirrorPath displayName docSet sourceKind docType docVersion <<< "$documentation_source_projection"

        local cut_directories
        cut_directories="$(documentation_fetch_cut_directories "$fetchUrl")"
        DOC_SOURCES+=("|$fetchUrl|$relativeMirrorPath|$displayName|$cut_directories|$DOCUMENTATION_FETCH_DEFAULT_MINIMUM_HTML_FILES|$DOCUMENTATION_FETCH_DEFAULT_REJECT_REGEX|$DOCUMENTATION_FETCH_DEFAULT_ALLOW_PARTIAL")
    done
}

append_legacy_documentation_fetch_sources() {
    DOC_SOURCES+=(
        "|${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|spring-ai-reference|Spring AI Reference (stable)|1|80|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_REFERENCE_2_BASE:-https://docs.spring.io/spring-ai/reference/2.0/}|spring-ai-reference-2|Spring AI Reference (2.0)|1|80|/spring-ai/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_API_STABLE_BASE:-https://docs.spring.io/spring-ai/docs/current/api/}|spring-ai-api-stable|Spring AI API (stable)|1|200||false"
        "|${SPRING_AI_API_2_BASE:-https://docs.spring.io/spring-ai/docs/2.0.x/api/}|spring-ai-api-2|Spring AI API (2.x)|1|200||false"
        "|${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|spring-framework-complete|Spring Framework Reference (current)|1|3000|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_FRAMEWORK_API_BASE:-https://docs.spring.io/spring-framework/docs/current/javadoc-api/}|spring-framework-complete|Spring Framework Javadoc (current)|1|7000||false"
        "|${JAVA25_RELEASE_NOTES_ISSUES_URL:-https://www.oracle.com/java/technologies/javase/25-relnote-issues.html}|oracle/javase|Java 25 Release Notes Issues|3|1||false"
        "|${IBM_JAVA25_ARTICLE_URL:-https://developer.ibm.com/articles/java-whats-new-java25/}|ibm/articles|IBM Java 25 Overview|1|1||false"
        "|${JETBRAINS_JAVA25_BLOG_URL:-https://blog.jetbrains.com/idea/2025/09/java-25-lts-and-intellij-idea/}|jetbrains/idea/2025/09|JetBrains Java 25 Blog|3|1||false"
    )
}

append_quick_documentation_fetch_sources() {
    DOC_SOURCES+=(
        "|${SPRING_FRAMEWORK_REFERENCE_BASE:-https://docs.spring.io/spring-framework/reference/}|spring-framework|Spring Framework Quick (reference landing)|1|1|/spring-framework/reference/[0-9]__OR__/spring-framework/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_REFERENCE_BASE:-https://docs.spring.io/spring-ai/reference/}|spring-ai|Spring AI Quick (reference landing)|1|1|/spring-ai/reference/[0-9]__OR__/spring-ai/reference/[^/]*SNAPSHOT|false"
        "|${SPRING_AI_REFERENCE_2_BASE:-https://docs.spring.io/spring-ai/reference/2.0/}|spring-ai-2|Spring AI Quick (2.0 landing)|1|1|/spring-ai/reference/[^/]*SNAPSHOT|false"
    )
}

fetch_documentation_source() {
    local documentation_source_projection="$1"
    local fetch_projection_delimiters="${documentation_source_projection//[^|]/}"
    if [ "${#fetch_projection_delimiters}" -ne 7 ]; then
        log "${RED}✗ Documentation source projection must contain exactly eight fields${NC}"
        return 1
    fi

    local java_release
    local documentation_source_url
    local relative_mirror_path
    local documentation_source_name
    local cut_directories
    local minimum_html_files
    local reject_regex
    local partial_mirror_allowed
    IFS='|' read -r java_release documentation_source_url relative_mirror_path documentation_source_name cut_directories minimum_html_files reject_regex partial_mirror_allowed <<< "$documentation_source_projection"

    if [ -z "$documentation_source_url" ] || [ -z "$relative_mirror_path" ] || [ -z "$documentation_source_name" ]; then
        log "${RED}✗ Documentation source projection has a blank required field${NC}"
        return 1
    fi
    if has_boundary_whitespace "$documentation_source_url" \
        || has_boundary_whitespace "$relative_mirror_path" \
        || has_boundary_whitespace "$documentation_source_name" \
        || has_boundary_whitespace "$reject_regex" \
        || has_manifest_control_character "$documentation_source_url" \
        || has_manifest_control_character "$relative_mirror_path" \
        || has_manifest_control_character "$documentation_source_name" \
        || has_manifest_control_character "$reject_regex"; then
        log "${RED}✗ Documentation source projection has invalid text fields${NC}"
        return 1
    fi
    if [ -n "$java_release" ] \
        && { ! is_canonical_manifest_integer "$java_release" || [ "$java_release" = "0" ]; }; then
        log "${RED}✗ Documentation source Java release must be blank or a positive canonical integer${NC}"
        return 1
    fi
    if ! is_normalized_relative_mirror_path "$relative_mirror_path"; then
        log "${RED}✗ Documentation source mirror path must be normalized and relative${NC}"
        return 1
    fi
    if [ -n "$java_release" ] && ! is_absolute_https_remote_base_url "$documentation_source_url"; then
        log "${RED}✗ Java API documentation source URL must be an absolute HTTPS base URL${NC}"
        return 1
    fi
    if ! is_canonical_manifest_integer "$cut_directories"; then
        log "${RED}✗ Documentation source cut-directories value must be a canonical integer${NC}"
        return 1
    fi
    if ! is_canonical_manifest_integer "$minimum_html_files" || [ "$minimum_html_files" = "0" ]; then
        log "${RED}✗ Documentation source minimum HTML files must be a positive canonical integer${NC}"
        return 1
    fi
    if [ "$partial_mirror_allowed" != "true" ] && [ "$partial_mirror_allowed" != "false" ]; then
        log "${RED}✗ Documentation source partial-mirror policy must be true or false${NC}"
        return 1
    fi

    echo ""
    log "Processing: $documentation_source_name"
    log "URL: $documentation_source_url"
    log "Target: $DOCS_ROOT/$relative_mirror_path"

    fetch_docs "$java_release" "$documentation_source_url" "$relative_mirror_path" "$documentation_source_name" "$cut_directories" "$minimum_html_files" "$reject_regex" "$partial_mirror_allowed"
}
