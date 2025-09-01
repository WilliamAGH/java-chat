#!/bin/bash

echo "Testing Secure API Key Logging"
echo "==============================="
echo ""

# Test with dev profile (shows last 4 chars)
echo "1. Testing with DEV profile (shows last 4 chars of keys):"
echo "   Setting SPRING_PROFILE=dev"
echo ""
export SPRING_PROFILE=dev
export GITHUB_TOKEN="ghp_testtoken1234567890abcdefghijklmnop"
export OPENAI_API_KEY=""

echo "Starting application with GitHub token ending in 'mnop'..."
timeout 5 ./mvnw spring-boot:run 2>&1 | grep -A 10 "API Key Configuration Status" || true

echo ""
echo "---"
echo ""

# Test with prod profile (hides key details)
echo "2. Testing with PROD profile (only shows presence):"
echo "   Setting SPRING_PROFILE=prod"
echo ""
export SPRING_PROFILE=prod
export GITHUB_TOKEN="ghp_testtoken1234567890abcdefghijklmnop"
export OPENAI_API_KEY="sk-proj-testopenaikey123456789"

echo "Starting application with both tokens configured..."
timeout 5 ./mvnw spring-boot:run 2>&1 | grep -A 10 "API Key Configuration Status" || true

echo ""
echo "---"
echo ""

# Test with no keys
echo "3. Testing with no API keys configured:"
echo "   Unsetting all API keys"
echo ""
unset GITHUB_TOKEN
unset OPENAI_API_KEY
export SPRING_PROFILE=dev

echo "Starting application without API keys..."
timeout 5 ./mvnw spring-boot:run 2>&1 | grep -A 10 "API Key Configuration Status" || true

echo ""
echo "==============================="
echo "Secure logging test complete!"
echo ""
echo "Summary:"
echo "- In DEV mode: Shows last 4 characters of API keys"
echo "- In PROD mode: Only shows if keys are configured (no details)"
echo "- Always warns when no API keys are configured"