#!/bin/bash

# Writes fetch metadata from the completed documentation-fetch run.

extract_meta_version() {
    local file_path="$1"
    if [ -z "$file_path" ] || [ ! -f "$file_path" ]; then
        echo ""
        return 0
    fi
    local version_meta_tag
    version_meta_tag="$(grep -E '<meta name="version" content="[^"]+"' "$file_path" 2>/dev/null | head -n 1 || true)"
    if [ -z "$version_meta_tag" ]; then
        echo ""
        return 0
    fi
    echo "$version_meta_tag" | sed -E 's/.*content="([^"]+)".*/\1/'
}

write_documentation_fetch_metadata() {
    local total_html_files
    total_html_files=$(find "$DOCS_ROOT" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')
    local total_files
    total_files=$(find "$DOCS_ROOT" -type f 2>/dev/null | wc -l | tr -d ' ')
    local spring_boot_reference_version
    spring_boot_reference_version="$(extract_meta_version "$DOCS_ROOT/spring-boot-complete/reference/index.html")"
    local spring_framework_reference_version
    spring_framework_reference_version="$(extract_meta_version "$DOCS_ROOT/spring-framework-complete/reference/index.html")"
    local spring_ai_reference_stable_version
    spring_ai_reference_stable_version="$(extract_meta_version "$DOCS_ROOT/spring-ai-reference/reference/index.html")"
    local spring_ai_reference_2_version
    spring_ai_reference_2_version="$(extract_meta_version "$DOCS_ROOT/spring-ai-reference-2/reference/2.0/index.html")"
    local java_javadoc_versions_metadata
    java_javadoc_versions_metadata="$(render_java_javadoc_versions_metadata "$DOCS_ROOT")"
    local java_complete_directories_metadata
    java_complete_directories_metadata="$(render_java_complete_directories_metadata "$DOCS_ROOT")"

    log "  - Total HTML files: $total_html_files"
    log "  - Total files: $total_files"
    log "  - Documentation root: $DOCS_ROOT"

    local metadata_file="$DOCS_ROOT/.fetch_metadata.json"
    cat > "$metadata_file" << EOF
{
  "last_fetch": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "statistics": {
    "newly_fetched": $TOTAL_FETCHED,
    "partial": $TOTAL_PARTIAL,
    "failed": $TOTAL_FAILED,
    "total_html_files": $total_html_files,
    "total_files": $total_files
  },
  "versions": {
$java_javadoc_versions_metadata,
    "spring_boot_reference": "$spring_boot_reference_version",
    "spring_framework_reference": "$spring_framework_reference_version",
    "spring_ai_reference_stable": "$spring_ai_reference_stable_version",
    "spring_ai_reference_2": "$spring_ai_reference_2_version"
  },
  "directories": {
$java_complete_directories_metadata,
    "spring_boot_complete": "$(find "$DOCS_ROOT/spring-boot-complete" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_framework_complete": "$(find "$DOCS_ROOT/spring-framework-complete" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_ai_reference_stable": "$(find "$DOCS_ROOT/spring-ai-reference" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_ai_reference_2": "$(find "$DOCS_ROOT/spring-ai-reference-2" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_ai_api_stable": "$(find "$DOCS_ROOT/spring-ai-api-stable" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')",
    "spring_ai_api_2": "$(find "$DOCS_ROOT/spring-ai-api-2" -name "*.html" 2>/dev/null | wc -l | tr -d ' ')"
  }
}
EOF

    log "Metadata saved to: $metadata_file"
}
