#!/bin/bash

# Test script for OpenAI streaming service migration
# This script tests the new streaming implementation

echo "🚀 Testing OpenAI Java SDK Migration"
echo "===================================="

# Check if the service is running
echo "1. Starting the application..."
make run &
SERVER_PID=$!

# Wait for server to start
echo "2. Waiting for server to start (30 seconds)..."
sleep 30

# Test the streaming endpoint
echo "3. Testing chat streaming endpoint..."
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello, test streaming with OpenAI Java SDK"}' \
  --max-time 30 \
  > streaming_test_output.txt 2>&1 &

CURL_PID=$!
sleep 10
kill $CURL_PID 2>/dev/null || true

echo "4. Checking streaming output..."
if [ -f "streaming_test_output.txt" ]; then
    echo "   Output file size: $(wc -c < streaming_test_output.txt) bytes"
    echo "   First few lines:"
    head -5 streaming_test_output.txt
    
    # Check for streaming artifacts we're trying to fix
    if grep -q "\[DONE\]" streaming_test_output.txt; then
        echo "   ❌ Found [DONE] artifact - may need OpenAI service configuration"
    else
        echo "   ✅ No [DONE] artifacts found"
    fi
    
    if grep -q "event: done" streaming_test_output.txt; then
        echo "   ❌ Found 'event: done' artifact - may need OpenAI service configuration"
    else
        echo "   ✅ No 'event: done' artifacts found"
    fi
else
    echo "   ❌ No output file generated"
fi

# Test guided learning endpoint
echo "5. Testing guided learning streaming endpoint..."
curl -N -X POST http://localhost:8080/api/guided/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "test-session", "latest": "What is Java?", "slug": "introduction-to-java"}' \
  --max-time 30 \
  > guided_streaming_test_output.txt 2>&1 &

CURL_PID=$!
sleep 10
kill $CURL_PID 2>/dev/null || true

echo "6. Checking guided streaming output..."
if [ -f "guided_streaming_test_output.txt" ]; then
    echo "   Output file size: $(wc -c < guided_streaming_test_output.txt) bytes"
    echo "   First few lines:"
    head -5 guided_streaming_test_output.txt
else
    echo "   ❌ No guided output file generated"
fi

# Check logs for OpenAI service usage
echo "7. Checking application logs..."
if [ -f "app.log" ]; then
    echo "   Recent log entries:"
    tail -10 app.log | grep -E "(OpenAI|OPENAI)" || echo "   No OpenAI-specific log entries found"
else
    echo "   ❌ No app.log file found"
fi

# Cleanup
echo "8. Cleaning up..."
kill $SERVER_PID 2>/dev/null || true
sleep 5

echo "===================================="
echo "✅ OpenAI streaming test completed!"
echo ""
echo "📝 Summary:"
echo "   - Check streaming_test_output.txt for chat streaming results"
echo "   - Check guided_streaming_test_output.txt for guided streaming results"
echo "   - Look for 'Using OpenAI Java SDK for streaming' in logs to confirm new service is used"
echo "   - If you see fallback messages, ensure GITHUB_TOKEN or OPENAI_API_KEY is set"
echo ""
echo "🔧 Next steps:"
echo "   - If streaming works without artifacts, the migration is successful!"
echo "   - If you see fallbacks, configure API credentials in .env file"
echo "   - Monitor for the specific issues mentioned in your documentation"


