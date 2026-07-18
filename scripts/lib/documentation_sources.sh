#!/bin/bash

DOCUMENTATION_SOURCE_LIBRARY_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG="$DOCUMENTATION_SOURCE_LIBRARY_DIRECTORY/../../src/main/resources/documentation-seed-document-types.manifest"
DOCUMENTATION_SOURCE_FIELD_CATALOG="$DOCUMENTATION_SOURCE_LIBRARY_DIRECTORY/../../src/main/resources/documentation-source-fields.manifest"
JAVA_API_SOURCE_MANIFEST_HEADER='javaRelease|remoteBaseUrl|relativeMirrorPath|displayName|cutDirectories|minimumHtmlFiles|rejectRegex|allowPartial'
JAVA_API_SOURCE_MAX_INTEGER='2147483647'
DOCUMENTATION_REMOTE_URL_MINIMUM_PORT='1'
DOCUMENTATION_REMOTE_URL_MAXIMUM_PORT='65535'
JAVA_API_SOURCE_PROJECTIONS=()
DOCUMENTATION_SOURCE_FIELDS=()
DOCUMENTATION_SOURCE_FIELDS_LOADED="false"
DOCUMENTATION_SOURCE_MANIFEST_HEADER=""
DOCUMENTATION_SOURCE_PROJECTIONS=()
DOCUMENTATION_SOURCE_DOC_SET_PROJECTIONS=()
DOCUMENTATION_SOURCE_FETCH_PROJECTIONS=()
DOCUMENTATION_SEED_DOCUMENT_TYPES=()
DOCUMENTATION_SEED_DOCUMENT_TYPES_LOADED="false"

is_supported_documentation_seed_document_type() {
    local requested_seed_document_type="$1"
    if [ "$DOCUMENTATION_SEED_DOCUMENT_TYPES_LOADED" != "true" ]; then
        return 1
    fi
    local supported_seed_document_type
    for supported_seed_document_type in "${DOCUMENTATION_SEED_DOCUMENT_TYPES[@]}"; do
        if [ "$supported_seed_document_type" = "$requested_seed_document_type" ]; then
            return 0
        fi
    done
    return 1
}

load_documentation_seed_document_types() {
    local document_type_catalog_file="$1"
    if [ ! -f "$document_type_catalog_file" ]; then
        echo "Documentation seed document type catalog not found: $document_type_catalog_file" >&2
        return 1
    fi

    DOCUMENTATION_SEED_DOCUMENT_TYPES=()
    DOCUMENTATION_SEED_DOCUMENT_TYPES_LOADED="false"
    local catalog_line_number=0
    local seed_document_type
    while IFS= read -r seed_document_type || [ -n "$seed_document_type" ]; do
        catalog_line_number=$((catalog_line_number + 1))
        seed_document_type="${seed_document_type%$'\r'}"
        if is_blank_manifest_line "$seed_document_type" \
            || has_boundary_whitespace "$seed_document_type" \
            || has_manifest_control_character "$seed_document_type" \
            || [[ ! "$seed_document_type" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
            echo "Documentation seed document type catalog line $catalog_line_number is invalid" >&2
            return 1
        fi
        if is_supported_documentation_seed_document_type "$seed_document_type"; then
            echo "Documentation seed document type catalog line $catalog_line_number duplicates $seed_document_type" >&2
            return 1
        fi
        DOCUMENTATION_SEED_DOCUMENT_TYPES+=("$seed_document_type")
        DOCUMENTATION_SEED_DOCUMENT_TYPES_LOADED="true"
    done < "$document_type_catalog_file"

    if [ "$DOCUMENTATION_SEED_DOCUMENT_TYPES_LOADED" != "true" ]; then
        echo "Documentation seed document type catalog has no records: $document_type_catalog_file" >&2
        return 1
    fi
}

is_retained_documentation_source_field() {
    local requested_field_name="$1"
    local retained_field_name
    for retained_field_name in "${DOCUMENTATION_SOURCE_FIELDS[@]+"${DOCUMENTATION_SOURCE_FIELDS[@]}"}"; do
        if [ "$retained_field_name" = "$requested_field_name" ]; then
            return 0
        fi
    done
    return 1
}

load_documentation_source_fields() {
    local field_catalog_file="$1"
    if [ ! -f "$field_catalog_file" ]; then
        echo "Documentation source field catalog not found: $field_catalog_file" >&2
        return 1
    fi

    DOCUMENTATION_SOURCE_FIELDS=()
    DOCUMENTATION_SOURCE_FIELDS_LOADED="false"
    DOCUMENTATION_SOURCE_MANIFEST_HEADER=""
    local catalog_line_number=0
    local canonical_field_name
    while IFS= read -r canonical_field_name || [ -n "$canonical_field_name" ]; do
        catalog_line_number=$((catalog_line_number + 1))
        canonical_field_name="${canonical_field_name%$'\r'}"
        if is_blank_manifest_line "$canonical_field_name" \
            || has_boundary_whitespace "$canonical_field_name" \
            || has_manifest_control_character "$canonical_field_name" \
            || [[ ! "$canonical_field_name" =~ ^[a-z][A-Za-z0-9]*$ ]]; then
            echo "Documentation source field catalog line $catalog_line_number is invalid" >&2
            return 1
        fi
        if is_retained_documentation_source_field "$canonical_field_name"; then
            echo "Documentation source field catalog line $catalog_line_number duplicates $canonical_field_name" >&2
            return 1
        fi
        DOCUMENTATION_SOURCE_FIELDS+=("$canonical_field_name")
        if [ -z "$DOCUMENTATION_SOURCE_MANIFEST_HEADER" ]; then
            DOCUMENTATION_SOURCE_MANIFEST_HEADER="$canonical_field_name"
        else
            DOCUMENTATION_SOURCE_MANIFEST_HEADER+="|$canonical_field_name"
        fi
    done < "$field_catalog_file"

    if [ -z "$DOCUMENTATION_SOURCE_MANIFEST_HEADER" ]; then
        echo "Documentation source field catalog has no records: $field_catalog_file" >&2
        return 1
    fi
    DOCUMENTATION_SOURCE_FIELDS_LOADED="true"
}

load_documentation_source_manifest_header() {
    local manifest_header="$1"
    [ "$DOCUMENTATION_SOURCE_FIELDS_LOADED" = "true" ] \
        && [ "$manifest_header" = "$DOCUMENTATION_SOURCE_MANIFEST_HEADER" ]
}

documentation_source_manifest_field_index() {
    local canonical_field_name="$1"
    if [ "$DOCUMENTATION_SOURCE_FIELDS_LOADED" != "true" ]; then
        return 1
    fi

    local canonical_field_index
    for canonical_field_index in "${!DOCUMENTATION_SOURCE_FIELDS[@]}"; do
        if [ "${DOCUMENTATION_SOURCE_FIELDS[$canonical_field_index]}" = "$canonical_field_name" ]; then
            printf '%s\n' "$canonical_field_index"
            return 0
        fi
    done
    return 1
}

documentation_source_manifest_field() {
    local manifest_projection="$1"
    local canonical_field_name="$2"
    local canonical_field_index
    if ! canonical_field_index="$(documentation_source_manifest_field_index "$canonical_field_name")"; then
        return 1
    fi

    local -a manifest_fields
    IFS='|' read -r -a manifest_fields <<< "$manifest_projection"
    printf '%s' "${manifest_fields[$canonical_field_index]-}"
}

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

is_canonical_manifest_integer() {
    local integer_text="$1"
    if [[ ! "$integer_text" =~ ^(0|[1-9][0-9]*)$ ]]; then
        return 1
    fi
    if [ "${#integer_text}" -gt "${#JAVA_API_SOURCE_MAX_INTEGER}" ]; then
        return 1
    fi
    if [ "${#integer_text}" -eq "${#JAVA_API_SOURCE_MAX_INTEGER}" ] \
        && [ "$integer_text" -gt "$JAVA_API_SOURCE_MAX_INTEGER" ]; then
        return 1
    fi
}

has_boundary_whitespace() {
    local manifest_text="$1"
    local LC_ALL=C
    [[ "$manifest_text" =~ ^[[:space:]] || "$manifest_text" =~ [[:space:]]$ ]]
}

has_manifest_control_character() {
    local manifest_text="$1"
    local LC_ALL=C
    [[ "$manifest_text" == *[[:cntrl:]]* ]]
}

is_absolute_https_remote_base_url() {
    local remote_base_url="$1"
    if [[ "$remote_base_url" != https://* || "$remote_base_url" != */ ]]; then
        return 1
    fi
    if [[ "$remote_base_url" == *[[:space:]]* \
        || "$remote_base_url" == *\\* \
        || "$remote_base_url" == *\?* \
        || "$remote_base_url" == *\#* ]]; then
        return 1
    fi

    local remote_authority_and_path="${remote_base_url#https://}"
    local remote_authority="${remote_authority_and_path%%[/?#]*}"
    if [ -z "$remote_authority" ] || [[ "$remote_authority" == *@* ]]; then
        return 1
    fi

    local remote_host
    local remote_port=""
    if [[ "$remote_authority" == \[* ]]; then
        local remote_host_and_suffix="${remote_authority#\[}"
        remote_host="${remote_host_and_suffix%%]*}"
        local remote_port_suffix="${remote_host_and_suffix#*]}"
        if [ -z "$remote_host" ] \
            || [ "$remote_authority" != "[$remote_host]$remote_port_suffix" ]; then
            return 1
        fi
        if [ -n "$remote_port_suffix" ]; then
            if [[ ! "$remote_port_suffix" =~ ^:([0-9]+)$ ]]; then
                return 1
            fi
            remote_port="${BASH_REMATCH[1]}"
        fi
        if ! is_valid_remote_ipv6_host "$remote_host"; then
            return 1
        fi
    else
        if [[ "$remote_authority" == *'['* || "$remote_authority" == *']'* ]]; then
            return 1
        fi
        local authority_without_colons="${remote_authority//:/}"
        local remote_colon_count=$(( ${#remote_authority} - ${#authority_without_colons} ))
        if [ "$remote_colon_count" -gt 1 ]; then
            return 1
        fi
        if [ "$remote_colon_count" -eq 1 ]; then
            remote_host="${remote_authority%:*}"
            remote_port="${remote_authority##*:}"
            if [ -z "$remote_port" ]; then
                return 1
            fi
        else
            remote_host="$remote_authority"
        fi
        if ! has_valid_remote_dns_labels "$remote_host"; then
            return 1
        fi
    fi
    is_valid_optional_remote_port "$remote_port"
}

is_valid_remote_ipv6_host() {
    local remote_ipv6_host="$1"
    python3 -c 'import ipaddress, sys; ipaddress.IPv6Address(sys.argv[1])' \
        "$remote_ipv6_host" > /dev/null 2>&1
}

has_valid_remote_dns_labels() {
    local remote_dns_host="$1"
    if [ -z "$remote_dns_host" ] \
        || [[ "$remote_dns_host" == .* \
        || "$remote_dns_host" == *. \
        || "$remote_dns_host" == *..* ]]; then
        return 1
    fi

    local IFS='.'
    local -a remote_dns_labels
    read -r -a remote_dns_labels <<< "$remote_dns_host"
    local remote_dns_label
    local LC_ALL=C
    for remote_dns_label in "${remote_dns_labels[@]}"; do
        if [[ ! "$remote_dns_label" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$ ]]; then
            return 1
        fi
    done
}

is_valid_optional_remote_port() {
    local remote_port="$1"
    if [ -z "$remote_port" ]; then
        return 0
    fi
    if [[ ! "$remote_port" =~ ^[0-9]+$ ]]; then
        return 1
    fi
    local normalized_remote_port="$remote_port"
    while [ "${#normalized_remote_port}" -gt 1 ] && [[ "$normalized_remote_port" == 0* ]]; do
        normalized_remote_port="${normalized_remote_port#0}"
    done
    if [ "${#normalized_remote_port}" -gt "${#DOCUMENTATION_REMOTE_URL_MAXIMUM_PORT}" ]; then
        return 1
    fi
    [ "$normalized_remote_port" -ge "$DOCUMENTATION_REMOTE_URL_MINIMUM_PORT" ] \
        && [ "$normalized_remote_port" -le "$DOCUMENTATION_REMOTE_URL_MAXIMUM_PORT" ]
}

is_absolute_https_remote_url() {
    local remote_url="$1"
    if [[ "$remote_url" != https://* \
        || "$remote_url" == *[[:space:]]* \
        || "$remote_url" == *\\* \
        || "$remote_url" == *\?* \
        || "$remote_url" == *\#* ]]; then
        return 1
    fi
    local remote_authority_and_path="${remote_url#https://}"
    local remote_authority="${remote_authority_and_path%%/*}"
    if [ -z "$remote_authority" ] || [[ "$remote_authority" == *@* ]]; then
        return 1
    fi
    is_absolute_https_remote_base_url "https://$remote_authority/"
}

is_absolute_http_remote_base_url() {
    local remote_base_url="$1"
    if [[ "$remote_base_url" == https://* ]]; then
        is_absolute_https_remote_base_url "$remote_base_url"
        return
    fi
    if [[ "$remote_base_url" != http://* ]]; then
        return 1
    fi
    is_absolute_https_remote_base_url "https://${remote_base_url#http://}"
}

is_normalized_relative_mirror_path() {
    local relative_mirror_path="$1"
    if [[ "$relative_mirror_path" == /* \
        || "$relative_mirror_path" == */ \
        || "$relative_mirror_path" == *//* \
        || "$relative_mirror_path" == *\\* ]]; then
        return 1
    fi

    local IFS='/'
    local -a mirror_path_segments
    read -r -a mirror_path_segments <<< "$relative_mirror_path"
    local mirror_path_segment
    for mirror_path_segment in "${mirror_path_segments[@]}"; do
        if [ -z "$mirror_path_segment" ] \
            || [ "$mirror_path_segment" = "." ] \
            || [ "$mirror_path_segment" = ".." ]; then
            return 1
        fi
    done
}

documentation_mirror_root_contains() {
    local containing_mirror_root="$1"
    local candidate_mirror_root="$2"
    [[ "$candidate_mirror_root" = "$containing_mirror_root" \
        || "$candidate_mirror_root" == "$containing_mirror_root/"* ]]
}

documentation_mirror_roots_overlap() {
    local first_mirror_root="$1"
    local second_mirror_root="$2"
    documentation_mirror_root_contains "$first_mirror_root" "$second_mirror_root" \
        || documentation_mirror_root_contains "$second_mirror_root" "$first_mirror_root"
}

is_blank_manifest_line() {
    local manifest_line="$1"
    local LC_ALL=C
    [[ "$manifest_line" =~ ^[[:space:]]*$ ]]
}

count_html_files() {
    local directory_path="$1"
    if [ -d "$directory_path" ]; then
        find "$directory_path" -name "*.html" 2>/dev/null | wc -l | tr -d ' '
    else
        echo "0"
    fi
}

extract_javadoc_comment_version() {
    local file_path="$1"
    if [ -z "$file_path" ] || [ ! -f "$file_path" ]; then
        echo ""
        return 0
    fi
    local line
    line="$(grep -E '<!-- Generated by javadoc \\([0-9]+' "$file_path" 2>/dev/null | head -n 1 || true)"
    if [ -z "$line" ]; then
        echo ""
        return 0
    fi
    echo "$line" | sed -E 's/.*javadoc \\(([0-9]+)\\).*/\\1/'
}

load_java_api_documentation_sources() {
    local source_manifest_file="$1"
    if [ ! -f "$source_manifest_file" ]; then
        echo "Java API source manifest not found: $source_manifest_file" >&2
        return 1
    fi

    JAVA_API_SOURCE_PROJECTIONS=()
    local manifest_header=""
    local expected_delimiter_count=-1
    local manifest_line_number=0
    local manifest_line
    local retained_java_releases=("")
    local retained_relative_mirror_paths=("")
    while IFS= read -r manifest_line || [ -n "$manifest_line" ]; do
        manifest_line_number=$((manifest_line_number + 1))
        manifest_line="${manifest_line%$'\r'}"
        if [ "$manifest_line_number" -eq 1 ]; then
            manifest_header="$manifest_line"
            if [ -z "$manifest_header" ]; then
                echo "Java API source manifest header cannot be blank: $source_manifest_file" >&2
                return 1
            fi
            if [ "$manifest_header" != "$JAVA_API_SOURCE_MANIFEST_HEADER" ]; then
                echo "Java API source manifest header is invalid: $source_manifest_file" >&2
                return 1
            fi
            local header_delimiters="${manifest_header//[^|]/}"
            expected_delimiter_count=${#header_delimiters}
            continue
        fi
        if is_blank_manifest_line "$manifest_line"; then
            echo "Java API source manifest line $manifest_line_number cannot be blank" >&2
            return 1
        fi

        local source_delimiters="${manifest_line//[^|]/}"
        if [ "${#source_delimiters}" -ne "$expected_delimiter_count" ]; then
            echo "Java API source manifest line $manifest_line_number has an invalid column count" >&2
            return 1
        fi

        local javaRelease
        local remoteBaseUrl
        local relativeMirrorPath
        local displayName
        local cutDirectories
        local minimumHtmlFiles
        local rejectRegex
        local allowPartial
        IFS='|' read -r javaRelease remoteBaseUrl relativeMirrorPath displayName cutDirectories minimumHtmlFiles rejectRegex allowPartial <<< "$manifest_line"

        if ! is_canonical_manifest_integer "$javaRelease" || [ "$javaRelease" = "0" ]; then
            echo "Java API source manifest line $manifest_line_number has an invalid Java release" >&2
            return 1
        fi
        if [ -z "$remoteBaseUrl" ] || [ -z "$relativeMirrorPath" ] || [ -z "$displayName" ]; then
            echo "Java API source manifest line $manifest_line_number has a blank required field" >&2
            return 1
        fi
        if has_boundary_whitespace "$remoteBaseUrl" \
            || has_boundary_whitespace "$relativeMirrorPath" \
            || has_boundary_whitespace "$displayName" \
            || has_boundary_whitespace "$rejectRegex" \
            || has_manifest_control_character "$remoteBaseUrl" \
            || has_manifest_control_character "$relativeMirrorPath" \
            || has_manifest_control_character "$displayName" \
            || has_manifest_control_character "$rejectRegex"; then
            echo "Java API source manifest line $manifest_line_number has invalid text fields" >&2
            return 1
        fi
        if ! is_absolute_https_remote_base_url "$remoteBaseUrl"; then
            echo "Java API source manifest line $manifest_line_number has an invalid remote base URL" >&2
            return 1
        fi
        if ! is_normalized_relative_mirror_path "$relativeMirrorPath"; then
            echo "Java API source manifest line $manifest_line_number has an invalid relative mirror path" >&2
            return 1
        fi
        if ! is_canonical_manifest_integer "$cutDirectories"; then
            echo "Java API source manifest line $manifest_line_number has invalid cut directories" >&2
            return 1
        fi
        if ! is_canonical_manifest_integer "$minimumHtmlFiles" || [ "$minimumHtmlFiles" = "0" ]; then
            echo "Java API source manifest line $manifest_line_number has invalid minimum HTML files" >&2
            return 1
        fi
        if [ "$allowPartial" != "true" ] && [ "$allowPartial" != "false" ]; then
            echo "Java API source manifest line $manifest_line_number has invalid allow-partial semantics" >&2
            return 1
        fi
        local retained_java_release
        for retained_java_release in "${retained_java_releases[@]+"${retained_java_releases[@]}"}"; do
            if [ "$retained_java_release" = "$javaRelease" ]; then
                echo "Java API source manifest line $manifest_line_number duplicates Java release $javaRelease" >&2
                return 1
            fi
        done
        local retained_relative_mirror_path
        for retained_relative_mirror_path in "${retained_relative_mirror_paths[@]+"${retained_relative_mirror_paths[@]}"}"; do
            if [ "$retained_relative_mirror_path" = "$relativeMirrorPath" ]; then
                echo "Java API source manifest line $manifest_line_number duplicates mirror path $relativeMirrorPath" >&2
                return 1
            fi
        done

        retained_java_releases+=("$javaRelease")
        retained_relative_mirror_paths+=("$relativeMirrorPath")
        JAVA_API_SOURCE_PROJECTIONS+=("$manifest_line")
    done < "$source_manifest_file"

    if [ -z "$manifest_header" ]; then
        echo "Java API source manifest is empty: $source_manifest_file" >&2
        return 1
    fi
    if [ "${#JAVA_API_SOURCE_PROJECTIONS[@]}" -eq 0 ]; then
        echo "No canonical Java API sources found in $source_manifest_file" >&2
        return 1
    fi
}

append_java_api_fetch_sources() {
    local java_api_source_projection
    for java_api_source_projection in "${JAVA_API_SOURCE_PROJECTIONS[@]}"; do
        DOC_SOURCES+=("$java_api_source_projection")
    done
}

load_documentation_sources() {
    local source_manifest_file="$1"
    if [ ! -f "$source_manifest_file" ]; then
        echo "Documentation source manifest not found: $source_manifest_file" >&2
        return 1
    fi
    if ! load_documentation_source_fields "$DOCUMENTATION_SOURCE_FIELD_CATALOG"; then
        return 1
    fi
    if ! load_documentation_seed_document_types "$DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG"; then
        return 1
    fi

    DOCUMENTATION_SOURCE_PROJECTIONS=()
    DOCUMENTATION_SOURCE_DOC_SET_PROJECTIONS=()
    DOCUMENTATION_SOURCE_FETCH_PROJECTIONS=()
    local loaded_documentation_source_projections=()
    local loaded_documentation_source_doc_set_projections=()
    local loaded_documentation_source_fetch_projections=()
    local manifest_header=""
    local expected_manifest_column_count=0
    local manifest_line_number=0
    local manifest_line
    local retained_relative_mirror_paths=()
    local retained_superseded_relative_mirror_paths=()
    local retained_doc_sets=()
    while IFS= read -r manifest_line || [ -n "$manifest_line" ]; do
        manifest_line_number=$((manifest_line_number + 1))
        manifest_line="${manifest_line%$'\r'}"
        if [ "$manifest_line_number" -eq 1 ]; then
            manifest_header="$manifest_line"
            if [ -z "$manifest_header" ]; then
                echo "Documentation source manifest header cannot be blank: $source_manifest_file" >&2
                return 1
            fi
            if ! load_documentation_source_manifest_header "$manifest_header"; then
                echo "Documentation source manifest header is invalid: $source_manifest_file" >&2
                return 1
            fi
            expected_manifest_column_count=${#DOCUMENTATION_SOURCE_FIELDS[@]}
            continue
        fi
        if is_blank_manifest_line "$manifest_line"; then
            echo "Documentation source manifest line $manifest_line_number cannot be blank" >&2
            return 1
        fi

        local source_delimiters="${manifest_line//[^|]/}"
        if [ $(( ${#source_delimiters} + 1 )) -ne "$expected_manifest_column_count" ]; then
            echo "Documentation source manifest line $manifest_line_number has an invalid column count" >&2
            return 1
        fi

        local fetchUrl
        local citationBaseUrl
        local relativeMirrorPath
        local displayName
        local docSet
        local sourceKind
        local docType
        local docVersion
        local minimumHtmlFiles
        local rejectRegex
        local allowPartial
        local seedDocumentType
        local seedDiscoveryUrl
        local seedSourcePrefix
        local supersededRelativeMirrorPath
        local -a manifest_fields
        IFS='|' read -r -a manifest_fields <<< "$manifest_line"
        local manifest_field_index
        for manifest_field_index in "${!DOCUMENTATION_SOURCE_FIELDS[@]}"; do
            printf -v "${DOCUMENTATION_SOURCE_FIELDS[$manifest_field_index]}" \
                '%s' \
                "${manifest_fields[$manifest_field_index]-}"
        done

        if [ -z "$fetchUrl" ] \
            || [ -z "$citationBaseUrl" ] \
            || [ -z "$relativeMirrorPath" ] \
            || [ -z "$displayName" ] \
            || [ -z "$docSet" ] \
            || [ -z "$sourceKind" ] \
            || [ -z "$docType" ] \
            || [ -z "$minimumHtmlFiles" ] \
            || [ -z "$allowPartial" ]; then
            echo "Documentation source manifest line $manifest_line_number has a blank required field" >&2
            return 1
        fi
        if has_boundary_whitespace "$fetchUrl" \
            || has_boundary_whitespace "$citationBaseUrl" \
            || has_boundary_whitespace "$relativeMirrorPath" \
            || has_boundary_whitespace "$displayName" \
            || has_boundary_whitespace "$docSet" \
            || has_boundary_whitespace "$sourceKind" \
            || has_boundary_whitespace "$docType" \
            || has_boundary_whitespace "$docVersion" \
            || has_boundary_whitespace "$rejectRegex" \
            || has_boundary_whitespace "$seedDocumentType" \
            || has_boundary_whitespace "$seedDiscoveryUrl" \
            || has_boundary_whitespace "$seedSourcePrefix" \
            || has_boundary_whitespace "$supersededRelativeMirrorPath" \
            || has_manifest_control_character "$fetchUrl" \
            || has_manifest_control_character "$citationBaseUrl" \
            || has_manifest_control_character "$relativeMirrorPath" \
            || has_manifest_control_character "$displayName" \
            || has_manifest_control_character "$docSet" \
            || has_manifest_control_character "$sourceKind" \
            || has_manifest_control_character "$docType" \
            || has_manifest_control_character "$docVersion" \
            || has_manifest_control_character "$rejectRegex" \
            || has_manifest_control_character "$seedDocumentType" \
            || has_manifest_control_character "$seedDiscoveryUrl" \
            || has_manifest_control_character "$seedSourcePrefix" \
            || has_manifest_control_character "$supersededRelativeMirrorPath"; then
            echo "Documentation source manifest line $manifest_line_number has invalid text fields" >&2
            return 1
        fi
        if ! is_absolute_https_remote_base_url "$fetchUrl"; then
            echo "Documentation source manifest line $manifest_line_number has an invalid fetch URL" >&2
            return 1
        fi
        if ! is_absolute_https_remote_base_url "$citationBaseUrl"; then
            echo "Documentation source manifest line $manifest_line_number has an invalid citation base URL" >&2
            return 1
        fi
        if ! is_normalized_relative_mirror_path "$relativeMirrorPath"; then
            echo "Documentation source manifest line $manifest_line_number has an invalid relative mirror path" >&2
            return 1
        fi
        if [ -n "$supersededRelativeMirrorPath" ]; then
            if ! is_normalized_relative_mirror_path "$supersededRelativeMirrorPath" \
                || documentation_mirror_root_contains \
                    "$supersededRelativeMirrorPath" \
                    "$relativeMirrorPath"; then
                echo "Documentation source manifest line $manifest_line_number has an invalid superseded mirror path" >&2
                return 1
            fi
        fi
        if [[ "$docSet" == *,* ]]; then
            echo "Documentation source manifest line $manifest_line_number has an invalid comma-delimited docSet" >&2
            return 1
        fi
        if ! is_canonical_manifest_integer "$minimumHtmlFiles" || [ "$minimumHtmlFiles" = "0" ]; then
            echo "Documentation source manifest line $manifest_line_number has invalid minimum HTML files" >&2
            return 1
        fi
        if [ "$allowPartial" != "true" ] && [ "$allowPartial" != "false" ]; then
            echo "Documentation source manifest line $manifest_line_number has invalid allow-partial semantics" >&2
            return 1
        fi
        if [ -n "$seedDiscoveryUrl" ]; then
            if [ -z "$seedDocumentType" ] || [ -z "$seedSourcePrefix" ]; then
                echo "Documentation source manifest line $manifest_line_number has incomplete seed discovery fields" >&2
                return 1
            fi
            if ! is_supported_documentation_seed_document_type "$seedDocumentType"; then
                echo "Documentation source manifest line $manifest_line_number has an unsupported seed document type" >&2
                return 1
            fi
            if ! is_absolute_https_remote_url "$seedDiscoveryUrl"; then
                echo "Documentation source manifest line $manifest_line_number has an invalid seed discovery URL" >&2
                return 1
            fi
            if ! is_absolute_http_remote_base_url "$seedSourcePrefix"; then
                echo "Documentation source manifest line $manifest_line_number has an invalid seed source prefix" >&2
                return 1
            fi
        elif [ -n "$seedDocumentType" ] || [ -n "$seedSourcePrefix" ]; then
            echo "Documentation source manifest line $manifest_line_number has incomplete seed discovery fields" >&2
            return 1
        fi
        local retained_relative_mirror_path
        for retained_relative_mirror_path in "${retained_relative_mirror_paths[@]+"${retained_relative_mirror_paths[@]}"}"; do
            if [ "$retained_relative_mirror_path" = "$relativeMirrorPath" ]; then
                echo "Documentation source manifest line $manifest_line_number duplicates mirror path $relativeMirrorPath" >&2
                return 1
            fi
        done
        local retained_doc_set
        for retained_doc_set in "${retained_doc_sets[@]+"${retained_doc_sets[@]}"}"; do
            if [ "$retained_doc_set" = "$docSet" ]; then
                echo "Documentation source manifest line $manifest_line_number duplicates docSet $docSet" >&2
                return 1
            fi
        done
        if [ -n "$supersededRelativeMirrorPath" ]; then
            local retained_superseded_relative_mirror_path
            for retained_superseded_relative_mirror_path in "${retained_superseded_relative_mirror_paths[@]+"${retained_superseded_relative_mirror_paths[@]}"}"; do
                if [ "$retained_superseded_relative_mirror_path" = "$supersededRelativeMirrorPath" ]; then
                    echo "Documentation source manifest line $manifest_line_number duplicates superseded mirror path $supersededRelativeMirrorPath" >&2
                    return 1
                fi
            done
        fi

        retained_relative_mirror_paths+=("$relativeMirrorPath")
        retained_superseded_relative_mirror_paths+=("$supersededRelativeMirrorPath")
        retained_doc_sets+=("$docSet")
        loaded_documentation_source_projections+=("$manifest_line")
        loaded_documentation_source_doc_set_projections+=("$docSet")
        local cutDirectories
        cutDirectories="$(documentation_fetch_cut_directories "$fetchUrl")"
        loaded_documentation_source_fetch_projections+=(
            "|$fetchUrl|$relativeMirrorPath|$displayName|$cutDirectories|$minimumHtmlFiles|$rejectRegex|$allowPartial|$seedDocumentType|$seedDiscoveryUrl|$seedSourcePrefix|$supersededRelativeMirrorPath"
        )
    done < "$source_manifest_file"

    if [ -z "$manifest_header" ]; then
        echo "Documentation source manifest is empty: $source_manifest_file" >&2
        return 1
    fi
    if [ "${#loaded_documentation_source_projections[@]}" -eq 0 ]; then
        echo "No canonical documentation sources found in $source_manifest_file" >&2
        return 1
    fi
    local source_index
    for source_index in "${!retained_superseded_relative_mirror_paths[@]}"; do
        local retained_superseded_relative_mirror_path="${retained_superseded_relative_mirror_paths[$source_index]}"
        if [ -z "$retained_superseded_relative_mirror_path" ]; then
            continue
        fi
        local active_source_index
        for active_source_index in "${!retained_relative_mirror_paths[@]}"; do
            if [ "$active_source_index" -eq "$source_index" ]; then
                continue
            fi
            local retained_relative_mirror_path="${retained_relative_mirror_paths[$active_source_index]}"
            if documentation_mirror_roots_overlap \
                "$retained_superseded_relative_mirror_path" \
                "$retained_relative_mirror_path"; then
                echo "Documentation source superseded mirror path overlaps active mirror path: $retained_superseded_relative_mirror_path" >&2
                return 1
            fi
        done
    done
    DOCUMENTATION_SOURCE_PROJECTIONS=("${loaded_documentation_source_projections[@]}")
    DOCUMENTATION_SOURCE_DOC_SET_PROJECTIONS=("${loaded_documentation_source_doc_set_projections[@]}")
    DOCUMENTATION_SOURCE_FETCH_PROJECTIONS=("${loaded_documentation_source_fetch_projections[@]}")
}

render_java_javadoc_versions_metadata() {
    local docs_root="$1"
    local metadata_separator=""
    local java_api_source_projection
    for java_api_source_projection in "${JAVA_API_SOURCE_PROJECTIONS[@]}"; do
        local javaRelease
        local remoteBaseUrl
        local relativeMirrorPath
        local displayName
        local cutDirectories
        local minimumHtmlFiles
        local rejectRegex
        local allowPartial
        IFS='|' read -r javaRelease remoteBaseUrl relativeMirrorPath displayName cutDirectories minimumHtmlFiles rejectRegex allowPartial <<< "$java_api_source_projection"
        local javadoc_version
        javadoc_version="$(extract_javadoc_comment_version "$docs_root/$relativeMirrorPath/api/index.html")"
        printf '%s    "java%s_javadoc": "%s"' "$metadata_separator" "$javaRelease" "$javadoc_version"
        metadata_separator=$',\n'
    done
}

render_java_complete_directories_metadata() {
    local docs_root="$1"
    local metadata_separator=""
    local java_api_source_projection
    for java_api_source_projection in "${JAVA_API_SOURCE_PROJECTIONS[@]}"; do
        local javaRelease
        local remoteBaseUrl
        local relativeMirrorPath
        local displayName
        local cutDirectories
        local minimumHtmlFiles
        local rejectRegex
        local allowPartial
        IFS='|' read -r javaRelease remoteBaseUrl relativeMirrorPath displayName cutDirectories minimumHtmlFiles rejectRegex allowPartial <<< "$java_api_source_projection"
        local html_file_count
        html_file_count="$(count_html_files "$docs_root/$relativeMirrorPath")"
        printf '%s    "java%s_complete": "%s"' "$metadata_separator" "$javaRelease" "$html_file_count"
        metadata_separator=$',\n'
    done
}
