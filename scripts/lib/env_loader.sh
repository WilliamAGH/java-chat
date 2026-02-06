#!/bin/bash

# Sources an environment file while preserving variables that are already
# exported in the current process. This enforces precedence:
# current process environment > .env file values.
preserve_process_env_then_source_file() {
    local environment_file_path="$1"
    if [ -z "$environment_file_path" ]; then
        echo "Environment file path is required" >&2
        return 1
    fi
    if [ ! -f "$environment_file_path" ]; then
        echo "Environment file not found: $environment_file_path" >&2
        return 1
    fi

    local declared_variable_names=()
    local preserved_variable_values=()

    while IFS= read -r raw_environment_line || [ -n "$raw_environment_line" ]; do
        local trimmed_environment_line
        trimmed_environment_line="$(echo "$raw_environment_line" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
        [[ -z "$trimmed_environment_line" || "$trimmed_environment_line" =~ ^# ]] && continue

        local declaration_without_export="${trimmed_environment_line#export }"
        local declared_variable_name="${declaration_without_export%%=*}"
        [[ -z "$declared_variable_name" || "$declared_variable_name" == "$declaration_without_export" ]] && continue
        declared_variable_name="$(echo "$declared_variable_name" | sed -E 's/[[:space:]]+$//')"
        [[ ! "$declared_variable_name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] && continue

        if [ -n "${!declared_variable_name+x}" ]; then
            declared_variable_names+=("$declared_variable_name")
            preserved_variable_values+=("${!declared_variable_name}")
        fi
    done < "$environment_file_path"

    set -a
    # shellcheck disable=SC1090
    source "$environment_file_path"
    set +a

    local restore_index
    for restore_index in "${!declared_variable_names[@]}"; do
        export "${declared_variable_names[$restore_index]}=${preserved_variable_values[$restore_index]}"
    done
}
