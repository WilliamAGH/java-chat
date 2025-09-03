#!/bin/bash

# Test PDF Processing Script
# This script tests processing a single PDF file

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
PDF_FILE="$PROJECT_ROOT/data/docs/books/Think Java - 2nd Edition Book.pdf"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "=============================================="
echo "PDF Processing Test"
echo "=============================================="
echo "Testing PDF: $PDF_FILE"
echo ""

# Load environment variables
if [ -f "$PROJECT_ROOT/.env" ]; then
    set -a
    source "$PROJECT_ROOT/.env"
    set +a
    echo -e "${GREEN}✓ Environment variables loaded${NC}"
else
    echo -e "${RED}✗ .env file not found${NC}"
    exit 1
fi

# Check if PDF exists
if [ ! -f "$PDF_FILE" ]; then
    echo -e "${RED}✗ PDF file not found: $PDF_FILE${NC}"
    exit 1
fi

echo -e "${GREEN}✓ PDF file found${NC}"

# Set environment to process only the books directory
export DOCS_DIR="$PROJECT_ROOT/data/docs/books"

# Build the application
echo -e "${YELLOW}Building application...${NC}"
cd "$PROJECT_ROOT"
./mvnw -DskipTests clean package > /dev/null 2>&1

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Application built successfully${NC}"
else
    echo -e "${RED}✗ Failed to build application${NC}"
    exit 1
fi

# Run the document processor
echo -e "${YELLOW}Processing PDF document...${NC}"
java -cp target/java-chat-0.0.1-SNAPSHOT.jar \
    -Dspring.profiles.active=default \
    com.williamcallahan.javachat.cli.DocumentProcessor

echo -e "${GREEN}✓ PDF processing complete${NC}"