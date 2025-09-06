#!/bin/bash

echo "Testing Markdown Formatting in Java Chat"
echo "========================================="
echo ""
echo "This test will send a request that should produce formatted markdown response"
echo "with paragraphs, lists, and code blocks."
echo ""

# Test with a query that should produce rich markdown
QUERY="Explain Java records with an example. Include: 1) A brief introduction 2) Key features as a bullet list 3) A code example"

echo "Sending test query: $QUERY"
echo ""

# Send request to the streaming endpoint
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d "{\"message\": \"$QUERY\", \"sessionId\": \"test-markdown-$(date +%s)\"}" \
  2>/dev/null | while IFS= read -r line; do
    # Filter out keepalive messages
    if [[ ! "$line" =~ ^:.*keepalive ]]; then
        # Show raw SSE data for debugging
        if [[ "$line" =~ ^data: ]]; then
            echo "[SSE] ${line:0:100}..."
        fi
    fi
done

echo ""
echo "Test complete. Check the browser UI to verify proper formatting:"
echo "1. Open http://localhost:8080/#chat"
echo "2. Send the same query: $QUERY"
echo "3. Verify that the response has:"
echo "   - Proper paragraph breaks"
echo "   - Formatted bullet lists"
echo "   - Syntax-highlighted code blocks"
echo "   - No 'data:' prefixes in the text"
