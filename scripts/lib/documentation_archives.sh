#!/bin/bash

# Downloads and extracts one version-pinned HTML documentation archive into its publication stage.
fetch_documentation_archive() {
    local archive_url="$1"
    local target_directory="$2"
    local source_name="$3"
    local minimum_html_files="$4"
    local archive_format="$5"
    local archive_strip_components="$6"
    local archive_publication_root="${7:-}"

    if [[ ! "$archive_strip_components" =~ ^[0-9]+$ ]]; then
        log "${RED}✗ $source_name archive strip-components must be a non-negative integer${NC}"
        return 1
    fi
    if [ -n "$archive_publication_root" ] \
        && { [[ "$archive_publication_root" == /* ]] \
            || [[ "$archive_publication_root" == *\\* ]] \
            || [[ "/$archive_publication_root/" == */../* ]] \
            || [[ "/$archive_publication_root/" == */./* ]]; }; then
        log "${RED}✗ $source_name archive publication root must be a safe relative path${NC}"
        return 1
    fi

    local archive_path
    if ! archive_path="$(mktemp "$target_directory/.documentation-archive.XXXXXX")"; then
        log "${RED}✗ Could not create the temporary documentation archive for $source_name${NC}"
        return 1
    fi

    local download_exit_code
    wget2 \
        --output-document="$archive_path" \
        --max-redirect=5 \
        "${DOCUMENTATION_SEED_NETWORK_POLICY_ARGUMENTS[@]}" \
        --user-agent="java-chat-doc-fetcher/1.0" \
        "$archive_url" 2>&1 | tee -a "$LOG_FILE"
    download_exit_code="${PIPESTATUS[0]}"
    if [ "$download_exit_code" -ne 0 ]; then
        rm -f "$archive_path"
        log "${RED}✗ Failed to download the documentation archive for $source_name${NC}"
        return 1
    fi

    local archive_listing
    local archive_member_listing
    case "$archive_format" in
        zip)
            if ! archive_listing="$(unzip -Z1 "$archive_path")"; then
                rm -f "$archive_path"
                log "${RED}✗ $source_name did not provide a readable ZIP documentation archive${NC}"
                return 1
            fi
            if ! archive_member_listing="$(unzip -Z -l "$archive_path")"; then
                rm -f "$archive_path"
                log "${RED}✗ $source_name ZIP member metadata is unreadable${NC}"
                return 1
            fi
            if [ "$archive_strip_components" -ne 0 ]; then
                rm -f "$archive_path"
                log "${RED}✗ $source_name ZIP documentation cannot strip path components${NC}"
                return 1
            fi
            if [ -n "$archive_publication_root" ] \
                && ! awk -v publication_prefix="${archive_publication_root%/}/" \
                    'index($0, publication_prefix) == 1 && length($0) > length(publication_prefix) { found = 1 } END { exit !found }' \
                    <<< "$archive_listing"; then
                rm -f "$archive_path"
                log "${RED}✗ $source_name ZIP archive is missing its publication root${NC}"
                return 1
            fi
            ;;
        tar-bz2)
            if [ -n "$archive_publication_root" ]; then
                rm -f "$archive_path"
                log "${RED}✗ $source_name tar documentation cannot select a publication root${NC}"
                return 1
            fi
            if ! archive_listing="$(tar -tjf "$archive_path")"; then
                rm -f "$archive_path"
                log "${RED}✗ $source_name did not provide a readable tar.bz2 documentation archive${NC}"
                return 1
            fi
            if ! archive_member_listing="$(tar -tvjf "$archive_path")"; then
                rm -f "$archive_path"
                log "${RED}✗ $source_name tar member metadata is unreadable${NC}"
                return 1
            fi
            ;;
        *)
            rm -f "$archive_path"
            log "${RED}✗ Unsupported documentation archive format for $source_name: $archive_format${NC}"
            return 1
            ;;
    esac

    if grep -Eq '(^/|(^|/)\.\.(/|$)|\\)' <<< "$archive_listing"; then
        rm -f "$archive_path"
        log "${RED}✗ $source_name archive contains an unsafe extraction path${NC}"
        return 1
    fi
    if grep -Eq '^[lhbcps]' <<< "$archive_member_listing"; then
        rm -f "$archive_path"
        log "${RED}✗ $source_name archive contains a non-regular filesystem member${NC}"
        return 1
    fi

    local extraction_exit_code=0
    case "$archive_format" in
        zip)
            if [ -n "$archive_publication_root" ]; then
                local archive_extraction_directory
                if ! archive_extraction_directory="$(
                    mktemp -d "$target_directory/.documentation-archive-extraction.XXXXXX"
                )"; then
                    extraction_exit_code=1
                else
                    unzip -q -o "$archive_path" "${archive_publication_root%/}/*" \
                        -d "$archive_extraction_directory" || extraction_exit_code=$?
                fi
                if [ "$extraction_exit_code" -eq 0 ]; then
                    local publication_source_directory="$archive_extraction_directory/${archive_publication_root%/}"
                    while IFS= read -r -d '' publication_entry; do
                        if ! mv -- "$publication_entry" "$target_directory/"; then
                            extraction_exit_code=1
                            break
                        fi
                    done < <(find "$publication_source_directory" -mindepth 1 -maxdepth 1 -print0)
                fi
                if [ -n "${archive_extraction_directory:-}" ]; then
                    find "$archive_extraction_directory" -depth -type d -empty -delete
                fi
            else
                unzip -q -o "$archive_path" -d "$target_directory" || extraction_exit_code=$?
            fi
            ;;
        tar-bz2)
            tar -xjf "$archive_path" \
                --directory "$target_directory" \
                --strip-components="$archive_strip_components" \
                --no-same-owner \
                --no-same-permissions || extraction_exit_code=$?
            ;;
    esac
    rm -f "$archive_path"
    if [ "$extraction_exit_code" -ne 0 ]; then
        log "${RED}✗ Failed to extract the documentation archive for $source_name${NC}"
        return 1
    fi

    validate_fetch_result 0 "$target_directory" "$source_name" "$minimum_html_files" "false"
}
