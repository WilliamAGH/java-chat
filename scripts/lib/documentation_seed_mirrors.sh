#!/bin/bash

documentation_fetch_staging_root() {
    printf '%s/.documentation-fetch-staging\n' "$(dirname "$DOCS_ROOT")"
}

create_documentation_fetch_staging_directory() {
    local relative_mirror_path="$1"
    local staging_root
    staging_root="$(documentation_fetch_staging_root)"
    if ! mkdir -p "$staging_root"; then
        return 1
    fi

    local mirror_basename
    mirror_basename="$(basename "$relative_mirror_path")"
    local staging_directory
    if ! staging_directory="$(mktemp -d "$staging_root/${mirror_basename}.replacement.XXXXXX")" \
        || [[ "$staging_directory" != "$staging_root/"* ]]; then
        return 1
    fi
    printf '%s\n' "$staging_directory"
}

discard_documentation_fetch_staging_directory() {
    local staging_directory="$1"
    local staging_root
    staging_root="$(documentation_fetch_staging_root)"
    if [ ! -d "$staging_directory" ] || [[ "$staging_directory" != "$staging_root/"* ]]; then
        return 1
    fi
    find "$staging_directory" -depth -delete
}

restore_retired_documentation_mirror() {
    local retired_mirror_path="$1"
    local active_mirror_path="$2"
    local documentation_source_name="$3"
    if ! mkdir -p "$(dirname "$active_mirror_path")" \
        || ! mv "$retired_mirror_path" "$active_mirror_path"; then
        log "${RED}✗ Could not roll back retired mirror state for $documentation_source_name${NC}"
        return 1
    fi
}

# Publishes one fully validated rolling mirror. Existing active and segment-adjacent
# lifecycle roots move together into one quarantine transaction before the staged
# directory is renamed onto the canonical path.
publish_staged_documentation_mirror() {
    local staging_directory="$1"
    local relative_mirror_path="$2"
    local superseded_relative_mirror_path="$3"
    local documentation_source_name="$4"
    local target_directory="$DOCS_ROOT/$relative_mirror_path"
    local superseded_mirror_path="$DOCS_ROOT/$superseded_relative_mirror_path"
    local quarantine_directory=""
    local retired_target_path=""
    local retired_superseded_path=""
    local target_retired="false"
    local superseded_root_retired="false"
    local rollback_failed="false"

    if [ ! -d "$staging_directory" ]; then
        log "${RED}✗ Validated staging mirror is missing for $documentation_source_name${NC}"
        return 1
    fi

    if [ -e "$target_directory" ] || [ -e "$superseded_mirror_path" ]; then
        if ! quarantine_directory="$(create_documentation_quarantine_directory \
            "$(basename "$relative_mirror_path")" \
            "replaced")"; then
            log "${RED}✗ Could not create replacement quarantine for $documentation_source_name${NC}"
            return 1
        fi
    fi

    if [ -e "$target_directory" ]; then
        retired_target_path="$quarantine_directory/$relative_mirror_path"
        if ! mkdir -p "$(dirname "$retired_target_path")" \
            || ! mv "$target_directory" "$retired_target_path"; then
            log "${RED}✗ Could not retire the prior active mirror for $documentation_source_name${NC}"
            return 1
        fi
        target_retired="true"
    fi

    # A strict child lifecycle root moved with target_directory. A segment-adjacent
    # lifecycle root remains at its original path and must join the same transaction.
    if [ -e "$superseded_mirror_path" ]; then
        retired_superseded_path="$quarantine_directory/$superseded_relative_mirror_path"
        if ! mkdir -p "$(dirname "$retired_superseded_path")" \
            || ! mv "$superseded_mirror_path" "$retired_superseded_path"; then
            if [ "$target_retired" = "true" ]; then
                if ! restore_retired_documentation_mirror \
                    "$retired_target_path" \
                    "$target_directory" \
                    "$documentation_source_name"; then
                    rollback_failed="true"
                fi
            fi
            log "${RED}✗ Could not retire the superseded mirror for $documentation_source_name${NC}"
            return 1
        fi
        superseded_root_retired="true"
    fi

    if ! mkdir -p "$(dirname "$target_directory")" \
        || ! mv "$staging_directory" "$target_directory"; then
        if [ "$superseded_root_retired" = "true" ]; then
            if ! restore_retired_documentation_mirror \
                "$retired_superseded_path" \
                "$superseded_mirror_path" \
                "$documentation_source_name"; then
                rollback_failed="true"
            fi
        fi
        if [ "$target_retired" = "true" ]; then
            if ! restore_retired_documentation_mirror \
                "$retired_target_path" \
                "$target_directory" \
                "$documentation_source_name"; then
                rollback_failed="true"
            fi
        fi
        if [ "$rollback_failed" = "true" ]; then
            log "${RED}✗ Replacement publication rollback was incomplete for $documentation_source_name${NC}"
        fi
        log "${RED}✗ Could not publish the validated mirror for $documentation_source_name${NC}"
        return 1
    fi

    if [ -n "$quarantine_directory" ]; then
        log "${YELLOW}⚠ Retired prior mirror state outside data/docs: $documentation_source_name -> $quarantine_directory${NC}"
    fi
}

# Writes the manifest-governed local paths represented by the current Java API seed.
write_java_api_seed_mirror_paths() {
    local remote_base_url="$1"
    local seed_file="$2"
    local cut_directories="$3"
    local mirror_paths_file="$4"

    if ! python3 "$SCRIPT_DIR/documentation_seed.py" \
        --project-mirror-paths-file \
        --input "$seed_file" \
        --output "$mirror_paths_file" \
        --required-prefix "$remote_base_url" \
        --cut-directories "$cut_directories"; then
        log "${RED}✗ Java API seed mirror-path projection failed${NC}"
        return 1
    fi

    if [ ! -s "$mirror_paths_file" ]; then
        log "${RED}✗ Java API seed produced no mirror paths${NC}"
        return 1
    fi
}

# Quarantines HTML files absent from an exact current seed projection.
# The quarantine is a sibling of data/docs because local ingestion recursively scans data/docs.
reconcile_seeded_html_mirror() {
    local target_dir="$1"
    local documentation_source_name="$2"
    local mirror_paths_file="$3"
    local quarantine_category="$4"
    if [ ! -d "$target_dir" ] || [ ! -s "$mirror_paths_file" ]; then
        log "${RED}✗ Seed mirror reconciliation requires nonempty current paths for $documentation_source_name${NC}"
        return 1
    fi
    local quarantine_dir=""
    local quarantined_html_count=0
    local stale_html_file
    while IFS= read -r -d '' stale_html_file; do
        local stale_relative_path="${stale_html_file#"$target_dir"/}"
        if grep -Fqx -- "$stale_relative_path" "$mirror_paths_file"; then
            continue
        fi

        if [ -z "$quarantine_dir" ]; then
            local mirror_basename
            mirror_basename="$(basename "$target_dir")"
            if ! quarantine_dir="$(create_documentation_quarantine_directory \
                "$mirror_basename" \
                "$quarantine_category")"; then
                log "${RED}✗ Could not create a safe seeded-mirror quarantine directory${NC}"
                return 1
            fi
        fi

        local quarantine_file="$quarantine_dir/$stale_relative_path"
        if ! mkdir -p "$(dirname "$quarantine_file")"; then
            log "${RED}✗ Could not create seeded-mirror quarantine parents: $quarantine_file${NC}"
            return 1
        fi
        if ! mv "$stale_html_file" "$quarantine_file"; then
            return 1
        fi
        quarantined_html_count=$((quarantined_html_count + 1))
    done < <(find "$target_dir" -type f \( -name "*.html" -o -name "*.htm" \) -print0)

    if [ "$quarantined_html_count" -gt 0 ]; then
        log "${YELLOW}⚠ Quarantined $quarantined_html_count unseeded HTML file(s) for $documentation_source_name -> $quarantine_dir${NC}"
    fi
}

# Requires the on-disk HTML inventory to equal the current seed projection.
verify_seeded_html_mirror() {
    local target_dir="$1"
    local documentation_source_name="$2"
    local mirror_paths_file="$3"
    local expected_mirror_path
    while IFS= read -r expected_mirror_path || [ -n "$expected_mirror_path" ]; do
        if [ -z "$expected_mirror_path" ] || [ ! -f "$target_dir/$expected_mirror_path" ]; then
            log "${RED}✗ $documentation_source_name is missing seeded HTML path: $expected_mirror_path${NC}"
            return 1
        fi
    done < "$mirror_paths_file"

    local mirrored_html_file
    while IFS= read -r -d '' mirrored_html_file; do
        local mirrored_relative_path="${mirrored_html_file#"$target_dir"/}"
        if ! grep -Fqx -- "$mirrored_relative_path" "$mirror_paths_file"; then
            log "${RED}✗ $documentation_source_name contains an unseeded HTML path: $mirrored_relative_path${NC}"
            return 1
        fi
    done < <(find "$target_dir" -type f \( -name "*.html" -o -name "*.htm" \) -print0)
}

# Quarantines Java API HTML files that are absent from the current canonical seed.
reconcile_java_api_seed_mirror() {
    local remote_base_url="$1"
    local target_dir="$2"
    local documentation_source_name="$3"
    local cut_directories="$4"
    local seed_file="$target_dir/.oracle-javadoc-seed.txt"

    if [ ! -d "$target_dir" ] || [ ! -s "$seed_file" ]; then
        log "${RED}✗ Java API mirror reconciliation requires a nonempty current seed for $documentation_source_name${NC}"
        return 1
    fi

    local mirror_paths_file
    mirror_paths_file="$(mktemp "$target_dir/.java-api-seed-paths.XXXXXX")"
    if ! write_java_api_seed_mirror_paths "$remote_base_url" "$seed_file" "$cut_directories" "$mirror_paths_file" \
        || ! reconcile_seeded_html_mirror \
            "$target_dir" "$documentation_source_name" "$mirror_paths_file" "unseeded-java-api"; then
        rm -f "$mirror_paths_file"
        return 1
    fi
    rm -f "$mirror_paths_file"
}

# Requires every canonical Java API seed path, and no unseeded HTML path, to exist in the mirror.
verify_java_api_seed_mirror() {
    local remote_base_url="$1"
    local target_dir="$2"
    local documentation_source_name="$3"
    local cut_directories="$4"
    local seed_file="$target_dir/.oracle-javadoc-seed.txt"

    if [ ! -d "$target_dir" ] || [ ! -s "$seed_file" ]; then
        log "${RED}✗ Java API mirror verification requires a nonempty current seed for $documentation_source_name${NC}"
        return 1
    fi

    local mirror_paths_file
    if ! mirror_paths_file="$(mktemp "$target_dir/.java-api-seed-paths.XXXXXX")"; then
        log "${RED}✗ Could not create Java API mirror paths for $documentation_source_name${NC}"
        return 1
    fi
    if ! write_java_api_seed_mirror_paths "$remote_base_url" "$seed_file" "$cut_directories" "$mirror_paths_file" \
        || ! verify_seeded_html_mirror "$target_dir" "$documentation_source_name" "$mirror_paths_file"; then
        rm -f "$mirror_paths_file"
        return 1
    fi
    rm -f "$mirror_paths_file"
}

# Generates the explicit Java API URL seed from the manifest-owned remote base.
generate_java_api_javadoc_seed() {
    local remote_base_url="$1"
    local target_dir="$2"
    local seed_file="$target_dir/.oracle-javadoc-seed.txt"
    local generated_seed_file
    generated_seed_file="$(mktemp "$target_dir/.oracle-javadoc-seed.XXXXXX")"

    log "${BLUE}ℹ Java API Javadoc source; generating explicit URL seed list...${NC}"
    local generator_exit_code
    if python3 "$SCRIPT_DIR/oracle_javadoc_seed.py" \
        --base-url "$remote_base_url" \
        --output "$generated_seed_file" 2>&1 | tee -a "$LOG_FILE"; then
        generator_exit_code="${PIPESTATUS[0]}"
    else
        generator_exit_code="${PIPESTATUS[0]}"
        rm -f "$generated_seed_file"
        log "${RED}✗ Java API Javadoc seed generation failed (exit code: $generator_exit_code)${NC}"
        return 1
    fi
    if [ "$generator_exit_code" -ne 0 ] || [ ! -s "$generated_seed_file" ]; then
        rm -f "$generated_seed_file"
        log "${RED}✗ Java API Javadoc seed generation produced no URLs${NC}"
        return 1
    fi
    if ! mv "$generated_seed_file" "$seed_file"; then
        rm -f "$generated_seed_file"
        log "${RED}✗ Java API Javadoc seed could not replace the active seed${NC}"
        return 1
    fi
    local seed_url_count
    seed_url_count="$(wc -l "$seed_file" | awk '{print $1}')"
    log "${BLUE}ℹ Java API Javadoc seed URLs: $seed_url_count${NC}"
}
