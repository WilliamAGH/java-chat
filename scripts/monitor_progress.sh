#!/bin/bash

source .env

while true; do
    protocol="https"
    base_url=""
    if [ "${QDRANT_SSL:-false}" = "true" ] || [ "${QDRANT_SSL:-false}" = "1" ]; then
        rest_port="${QDRANT_REST_PORT:-8087}"
        base_url="${protocol}://${QDRANT_HOST}:${rest_port}"
    else
        protocol="http"
        rest_port="${QDRANT_REST_PORT:-8087}"
        base_url="${protocol}://${QDRANT_HOST}:${rest_port}"
    fi

    auth=()
    if [ -n "${QDRANT_API_KEY:-}" ]; then
        auth=(-H "api-key: $QDRANT_API_KEY")
    fi

    count=$(curl -s "${auth[@]}" "${base_url}/collections/${QDRANT_COLLECTION}" | grep -o '"points_count":[0-9]*' | cut -d: -f2)
    embedding_calls=$(grep -c "EMBEDDING.*Calling API" process_qdrant.log)
    last_file=$(grep "Processing file:" process_qdrant.log | tail -1 | cut -d: -f2)
    
    echo -ne "\r[$(date +%H:%M:%S)] Vectors: $count | Embedding calls: $embedding_calls | Last: $last_file          "
    sleep 5
done
