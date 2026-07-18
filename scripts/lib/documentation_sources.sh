#!/bin/bash

# Counts mirrored HTML files for fetch completeness checks.
count_html_files() {
    local directory_path="$1"
    if [ -d "$directory_path" ]; then
        find "$directory_path" -name "*.html" 2>/dev/null | wc -l | tr -d ' '
    else
        echo "0"
    fi
}
