#!/bin/bash

source .env

while true; do
    count=$(curl -s -H "api-key: $QDRANT_API_KEY" "https://$QDRANT_HOST/collections/$QDRANT_COLLECTION" | grep -o '"points_count":[0-9]*' | cut -d: -f2)
    embedding_calls=$(grep -c "EMBEDDING.*Calling API" process_qdrant.log)
    last_file=$(grep "Processing file:" process_qdrant.log | tail -1 | cut -d: -f2)
    
    echo -ne "\r[$(date +%H:%M:%S)] Vectors: $count | Embedding calls: $embedding_calls | Last: $last_file          "
    sleep 5
done