#!/bin/bash

# Test script to verify that enrichment formatting is preserved after full render
# This tests the fix for the issue where beautiful formatting disappears

echo "Testing enrichment preservation after full render..."

# Test markdown content with enrichments
MARKDOWN='{{background:This is background context that should appear in a green box with proper styling}}

Here is some regular text.

{{example:
public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}
}}

More regular text.

{{hint:This is a helpful hint that should appear in an orange-tinted box}}'

# Send to the structured endpoint (used during streaming)
echo "Testing /api/markdown/render/structured endpoint..."
RESPONSE=$(curl -s -X POST http://localhost:8080/api/markdown/render/structured \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$MARKDOWN\"}")

# Check for proper class names and attributes
echo "Checking for correct HTML structure..."
echo "$RESPONSE" | jq -r '.html' > /tmp/enrichment_test.html

# Check for the correct class names
if grep -q 'class="inline-enrichment background"' /tmp/enrichment_test.html; then
    echo "✓ Found correct inline-enrichment background class"
else
    echo "✗ Missing inline-enrichment background class"
fi

if grep -q 'data-enrichment-type="background"' /tmp/enrichment_test.html; then
    echo "✓ Found data-enrichment-type attribute"
else
    echo "✗ Missing data-enrichment-type attribute"
fi

if grep -q 'class="inline-enrichment-header"' /tmp/enrichment_test.html; then
    echo "✓ Found correct inline-enrichment-header class"
else
    echo "✗ Missing inline-enrichment-header class"
fi

if grep -q 'class="enrichment-text"' /tmp/enrichment_test.html; then
    echo "✓ Found correct enrichment-text class"
else
    echo "✗ Missing enrichment-text class"
fi

# Check for SVG icons
if grep -q '<svg viewBox="0 0 24 24"' /tmp/enrichment_test.html; then
    echo "✓ Found SVG icons in enrichment headers"
else
    echo "✗ Missing SVG icons in enrichment headers"
fi

# Check for proper span wrapping of titles
if grep -q '<span>Background Context</span>' /tmp/enrichment_test.html; then
    echo "✓ Found properly wrapped title text"
else
    echo "✗ Missing properly wrapped title text"
fi

echo ""
echo "HTML output sample:"
echo "==================="
cat /tmp/enrichment_test.html | head -50
echo "==================="
echo ""

# Also test the legacy endpoint
echo "Testing /api/markdown/render endpoint..."
RESPONSE2=$(curl -s -X POST http://localhost:8080/api/markdown/render \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$MARKDOWN\"}")

echo "$RESPONSE2" | jq -r '.html' > /tmp/enrichment_test2.html

# Quick check on legacy endpoint
if grep -q 'class="inline-enrichment' /tmp/enrichment_test2.html; then
    echo "✓ Legacy endpoint also generates correct classes"
else
    echo "✗ Legacy endpoint missing correct classes"
fi

echo ""
echo "Test complete! Check the browser to verify visual appearance."