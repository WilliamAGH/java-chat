#!/bin/bash

echo "🔍 STREAMING DIAGNOSTICS - OpenAI vs Spring AI"
echo "=============================================="

# Test 1: Check what raw chunks look like from OpenAI streaming
echo "1. Testing OpenAI streaming raw output..."
curl -N -X POST http://localhost:8085/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Say: Hello world"}' \
  --max-time 10 2>/dev/null > openai_raw_output.txt &

CURL_PID=$!
sleep 8
kill $CURL_PID 2>/dev/null || true

echo "2. OpenAI raw output analysis:"
if [ -f "openai_raw_output.txt" ]; then
    echo "   File size: $(wc -c < openai_raw_output.txt) bytes"
    echo "   First 200 chars:"
    head -c 200 openai_raw_output.txt
    echo ""
    echo "   Checking for spaces between words..."
    if grep -q "Hello world" openai_raw_output.txt; then
        echo "   ✅ Found 'Hello world' with space"
    elif grep -q "Helloworld" openai_raw_output.txt; then
        echo "   ❌ Found 'Helloworld' without space - CONCATENATION ISSUE"
    else
        echo "   ? Could not find test phrase"
    fi
else
    echo "   ❌ No output file generated"
fi

echo ""
echo "3. Checking recent application logs for chunk details..."
tail -20 final_test.log | grep -E "(Received content chunk|chunk:|delta)" | head -10

echo ""
echo "4. Comparing with expected SSE format..."
echo "   Expected: Each chunk should contain individual words/tokens with spaces"
echo "   Problem:  If chunks are individual characters, spaces get lost"

echo ""
echo "5. Checking OpenAI service configuration..."
grep -E "(OpenAI|GPT-5|model)" final_test.log | tail -5

echo ""
echo "=============================================="
echo "📋 DIAGNOSIS SUMMARY"
echo "=============================================="
echo "If you see 'Helloworld' instead of 'Hello world':"
echo "  → OpenAI SDK is returning individual characters/tokens without preserving word boundaries"
echo "  → Need to check how ChatCompletionChunk.choices().delta().content() is structured"
echo "  → May need to add space handling logic in our streaming service"
echo ""
echo "Next steps:"
echo "  1. Check if OpenAI chunks include space tokens separately"
echo "  2. Compare with Spring AI chunk structure"  
echo "  3. Add proper token joining logic if needed"

