#!/bin/bash

# Test script to index a single document with full visibility
# This helps debug the indexing pipeline

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."

# shellcheck source=lib/common_qdrant.sh
source "$SCRIPT_DIR/lib/common_qdrant.sh"

echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}${CYAN}           SINGLE DOCUMENT INDEXING TEST${NC}"
echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo ""

load_env_file
apply_pipeline_defaults

# 1. Check services
echo -e "\n${BOLD}${YELLOW}1. Checking Services${NC}"
echo -e "───────────────────────────"

# Check embedding server
EMBED_URL="${LOCAL_EMBEDDING_SERVER_URL:-http://127.0.0.1:1234}/v1/models"
EMBED_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$EMBED_URL" 2>/dev/null)
if [ "$EMBED_STATUS" = "200" ]; then
    echo -e "Embedding server: ${GREEN}Available${NC} at $LOCAL_EMBEDDING_SERVER_URL"
    MODEL=$(curl -s "$EMBED_URL" | jq -r '.data[0].id' 2>/dev/null || echo "unknown")
    echo -e "   Model: ${CYAN}$MODEL${NC}"
else
    echo -e "Embedding server: ${RED}Not responding${NC}"
    exit 1
fi

# Check Qdrant
QDRANT_BASE_URL="$(qdrant_rest_base_url)"
QDRANT_URL="${QDRANT_BASE_URL}/collections/${QDRANT_COLLECTION}"
QDRANT_INFO=$(qdrant_curl -s "$QDRANT_URL" 2>/dev/null)
if [ $? -eq 0 ]; then
    VECTOR_COUNT=$(echo "$QDRANT_INFO" | jq -r '.result.points_count' 2>/dev/null || echo "0")
    echo -e "Qdrant: ${GREEN}Connected${NC}"
    echo -e "   Collection: ${CYAN}$QDRANT_COLLECTION${NC}"
    echo -e "   Current vectors: ${CYAN}$VECTOR_COUNT${NC}"
else
    echo -e "Qdrant: ${RED}Connection failed${NC}"
    exit 1
fi

# 2. Create test document
echo -e "\n${BOLD}${YELLOW}2. Creating Test Document${NC}"
echo -e "───────────────────────────"

TEST_DIR="$PROJECT_ROOT/data/test-single"
TEST_FILE="$TEST_DIR/test-java25.html"
mkdir -p "$TEST_DIR"

cat > "$TEST_FILE" << 'EOF'
<!DOCTYPE html>
<html>
<head><title>Java 25 Test Document</title></head>
<body>
<h1>Java 25 New Features</h1>
<p>Java 25 introduces several groundbreaking features that revolutionize Java development:</p>
<ul>
<li>Pattern Matching for switch has been finalized with additional enhancements</li>
<li>Virtual Threads are now production-ready with improved performance</li>
<li>String Templates provide a safer way to compose strings with embedded expressions</li>
<li>The Foreign Function & Memory API reaches general availability</li>
<li>Value Objects preview allows defining immutable, identity-free objects</li>
</ul>
<p>These features make Java 25 a significant milestone in the language's evolution.</p>
</body>
</html>
EOF

echo -e "Created test document: ${CYAN}$TEST_FILE${NC}"
echo -e "   Content: Java 25 features (for testing retrieval)"

# 3. Generate embedding manually
echo -e "\n${BOLD}${YELLOW}3. Testing Embedding Generation${NC}"
echo -e "───────────────────────────"

TEST_TEXT="Java 25 introduces virtual threads and pattern matching improvements"
echo -e "Test text: ${CYAN}\"$TEST_TEXT\"${NC}"

# Call embedding API directly
EMBED_RESPONSE=$(curl -s -X POST "$LOCAL_EMBEDDING_SERVER_URL/v1/embeddings" \
    -H "Content-Type: application/json" \
    -d "{\"model\": \"text-embedding-qwen3-embedding-8b\", \"input\": \"$TEST_TEXT\"}" 2>/dev/null)

if [ $? -eq 0 ]; then
    EMBED_DIM=$(echo "$EMBED_RESPONSE" | jq '.data[0].embedding | length' 2>/dev/null || echo "0")
    if [ "$EMBED_DIM" -gt 0 ]; then
        echo -e "Embedding generated: ${GREEN}$EMBED_DIM dimensions${NC}"
        SAMPLE_VALUES=$(echo "$EMBED_RESPONSE" | jq '.data[0].embedding[:5]' 2>/dev/null)
        echo -e "   Sample values: ${CYAN}$SAMPLE_VALUES${NC}"
    else
        echo -e "Failed to generate embedding"
        echo -e "   Response: $EMBED_RESPONSE"
        exit 1
    fi
else
    echo -e "Embedding API call failed"
    exit 1
fi

# 4. Build and run indexing
echo -e "\n${BOLD}${YELLOW}4. Running Document Indexing${NC}"
echo -e "───────────────────────────"

cd "$PROJECT_ROOT"

# Build if needed
build_application /dev/null

app_jar=$(locate_app_jar)

# Create temporary log
TEST_LOG="$PROJECT_ROOT/test-index.log"
> "$TEST_LOG"

echo -e "Starting indexing process..."
echo -e "Log file: ${CYAN}$TEST_LOG${NC}"
echo ""

# Run with enhanced logging
JAVA_OPTS="-Dlogging.level.com.williamcallahan.javachat.service=DEBUG"
JAVA_OPTS="$JAVA_OPTS -Dlogging.level.INDEXING=INFO"
JAVA_OPTS="$JAVA_OPTS -Dlogging.level.EMBEDDING=INFO"

# Start the app with test directory
java $JAVA_OPTS -jar "$app_jar" \
    --server.port=8090 \
    > "$TEST_LOG" 2>&1 &

APP_PID=$!
echo -e "Started app with PID: ${CYAN}$APP_PID${NC}"

# Wait for app to start
echo -e "Waiting for app to start..."
for i in {1..10}; do
    if curl -s -o /dev/null http://localhost:8090/api/chat/health/embeddings 2>/dev/null; then
        echo -e "App is ready"
        break
    fi
    sleep 1
done

# Trigger indexing via API
echo -e "\nTriggering indexing via API..."
INGEST_RESPONSE=$(curl -s -X POST "http://localhost:8090/api/ingest/local?dir=$TEST_DIR&maxFiles=10" 2>/dev/null)
echo -e "Ingestion response: ${CYAN}$INGEST_RESPONSE${NC}"

# Monitor for 20 seconds
echo -e "\n${BOLD}Monitoring indexing progress...${NC}"
PREV_COUNT=$VECTOR_COUNT
for i in {1..10}; do
    sleep 2
    
    # Check vector count
    NEW_COUNT=$(qdrant_curl -s "$QDRANT_URL" 2>/dev/null | \
        jq -r '.result.points_count' 2>/dev/null || echo "$PREV_COUNT")
    
    # Check logs for activity
    EMBEDDINGS=$(grep -c "EMBEDDING.*Generating" "$TEST_LOG" 2>/dev/null || true)
    INDEXED=$(grep -c "INDEXING.*Successfully added" "$TEST_LOG" 2>/dev/null || true)
    ERRORS=$(grep -c "ERROR" "$TEST_LOG" 2>/dev/null || true)
    
    echo -ne "\r[$i/10] Vectors: $PREV_COUNT → $NEW_COUNT | Embeddings: $EMBEDDINGS | Indexed: $INDEXED | Errors: $ERRORS"
    
    if [ "$NEW_COUNT" -gt "$PREV_COUNT" ]; then
        echo -e "\n${GREEN}Vectors increased by $((NEW_COUNT - PREV_COUNT))!${NC}"
        PREV_COUNT=$NEW_COUNT
    fi
done

echo ""

# Kill the app
kill $APP_PID 2>/dev/null || true

# 5. Show results
echo -e "\n${BOLD}${YELLOW}5. Results${NC}"
echo -e "───────────────────────────"

# Final vector count
FINAL_COUNT=$(qdrant_curl -s "$QDRANT_URL" 2>/dev/null | \
    jq -r '.result.points_count' 2>/dev/null || echo "$VECTOR_COUNT")

if [ "$FINAL_COUNT" -gt "$VECTOR_COUNT" ]; then
    echo -e "${GREEN}SUCCESS! Added $((FINAL_COUNT - VECTOR_COUNT)) vectors${NC}"
else
    echo -e "${RED}FAILED - No vectors added${NC}"
    echo -e "\n${YELLOW}Log excerpts:${NC}"
    echo "─────────────"
    grep -E "(ERROR|WARN|EMBEDDING|INDEXING)" "$TEST_LOG" | tail -20
fi

echo -e "\n${BOLD}${YELLOW}6. Test Retrieval${NC}"
echo -e "───────────────────────────"

if [ "$FINAL_COUNT" -gt "$VECTOR_COUNT" ]; then
    # Test retrieval with our indexed content
    TEST_QUERY="What are the new features in Java 25?"
    echo -e "Query: ${CYAN}\"$TEST_QUERY\"${NC}"
    
    # Search directly in Qdrant
    SEARCH_VECTOR=$(curl -s -X POST "$LOCAL_EMBEDDING_SERVER_URL/v1/embeddings" \
        -H "Content-Type: application/json" \
        -d "{\"model\": \"text-embedding-qwen3-embedding-8b\", \"input\": \"$TEST_QUERY\"}" 2>/dev/null | \
        jq '.data[0].embedding' 2>/dev/null)
    
    if [ -n "$SEARCH_VECTOR" ]; then
        SEARCH_RESULT=$(qdrant_curl -s -X POST "${QDRANT_BASE_URL}/collections/${QDRANT_COLLECTION}/points/search" \
            -H "Content-Type: application/json" \
            -d "{\"vector\": $SEARCH_VECTOR, \"limit\": 3, \"with_payload\": true}" 2>/dev/null)
        
        RESULT_COUNT=$(echo "$SEARCH_RESULT" | jq '.result | length' 2>/dev/null || echo "0")
        if [ "$RESULT_COUNT" -gt 0 ]; then
            echo -e "Found ${GREEN}$RESULT_COUNT results${NC}"
            echo "$SEARCH_RESULT" | jq -r '.result[0] | "   Score: \(.score)\n   URL: \(.payload.url)"' 2>/dev/null
        else
            echo -e "No results found"
        fi
    fi
fi

echo -e "\n${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "Full log available at: ${CYAN}$TEST_LOG${NC}"
echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}"
