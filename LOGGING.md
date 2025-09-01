# Comprehensive Server-Side Logging

## Overview
The Java Chat application now includes comprehensive logging for all processing steps in the RAG pipeline. Each step is tracked with a unique request ID for correlation.

## Logging Configuration
- **Log File**: `logs/pipeline.log` (rolling daily)
- **Console**: Color-coded output with timestamps
- **Logger Name**: `PIPELINE` for all pipeline operations

## Processing Steps Logged

### STEP 1: EMBEDDING GENERATION
- Logs when text is converted to embeddings
- Tracks input text length
- Records processing time

### STEP 2: DOCUMENT PARSING  
- Logs document chunking and processing
- Tracks processing method used
- Records completion time

### STEP 3: QUERY INTENT INTERPRETATION
- Logs user query analysis
- Detects intent categories:
  - EXPLANATION
  - EXAMPLE
  - COMPARISON
  - RECOMMENDATION
  - TROUBLESHOOTING
  - GENERAL_QUERY

### STEP 4: RAG RETRIEVAL
- Logs vector search operations
- Records number of documents retrieved
- Tracks retrieval duration

### STEP 5: LLM INTERACTION
- Logs prompt context size
- Tracks streaming response initiation
- Records response streaming duration

### STEP 6: RESPONSE CATEGORIZATION
- Logs enrichment extraction:
  - **HINTS**: Practical tips (2-3 items)
  - **REMINDERS**: Critical things to remember (2-3 items)
  - **BACKGROUND**: Conceptual explanations (2-3 items)
- Analyzes content for categories:
  - HELPFUL_REMINDERS
  - CODE_EXAMPLES
  - BEST_PRACTICES
  - WARNINGS
  - DOCUMENTATION_REFS
  - PERFORMANCE_TIPS

### STEP 7: CITATION GENERATION
- Logs citation creation
- Records number of citations generated
- Tracks processing time

## Streaming Response Logging
- Tracks chunk count every 10 chunks
- Records total characters streamed
- Logs completion with total statistics

## Request Tracking
- Each request gets a unique ID: `REQ-[timestamp]-[threadId]`
- All related operations are correlated via this ID
- Request IDs are thread-local for proper isolation

## Log Levels
- **INFO**: Major pipeline steps and summaries
- **DEBUG**: Detailed content and intermediate steps
- **ERROR**: Failures and exceptions

## Viewing Logs

### Real-time Console
```bash
make run
# Color-coded output appears in console
```

### Pipeline Log File
```bash
tail -f logs/pipeline.log
```

### Filtering by Request
```bash
grep "REQ-1234567890-1" logs/pipeline.log
```

## Example Log Output
```
[REQ-1234567890-1] ============================================
[REQ-1234567890-1] NEW CHAT REQUEST - Session: default
[REQ-1234567890-1] User query: How do Java streams work?
[REQ-1234567890-1] ============================================
[REQ-1234567890-1] STEP 1: EMBEDDING GENERATION - Starting
[REQ-1234567890-1] STEP 1: EMBEDDING GENERATION - Completed in 45ms
[REQ-1234567890-1] STEP 3: QUERY INTENT INTERPRETATION - Starting
[REQ-1234567890-1] Detected intent: EXPLANATION
[REQ-1234567890-1] STEP 4: RAG RETRIEVAL - Starting vector search
[REQ-1234567890-1] STEP 4: RAG RETRIEVAL - Retrieved documents in 123ms
[REQ-1234567890-1] Retrieved 5 documents
[REQ-1234567890-1] STEP 5: LLM INTERACTION - Sending to LLM
[REQ-1234567890-1] STEP 5: LLM INTERACTION - Response streaming started in 234ms
[REQ-1234567890-1] STEP 6: RESPONSE CATEGORIZATION - Processing enrichments
[REQ-1234567890-1] Enrichment categories extracted:
[REQ-1234567890-1]   - HINTS: 3 items
[REQ-1234567890-1]   - REMINDERS: 2 items
[REQ-1234567890-1]   - BACKGROUND: 2 items
[REQ-1234567890-1] STEP 7: CITATION GENERATION - Generated 5 citations in 12ms
[REQ-1234567890-1] STREAMING COMPLETE - 42 chunks, 2048 total chars
[REQ-1234567890-1] ============================================
[REQ-1234567890-1] PIPELINE COMPLETE - All steps processed
[REQ-1234567890-1] ============================================
```

## Configuration
The logging aspect is implemented using Spring AOP in `ProcessingLogger.java` and automatically intercepts all relevant service methods to provide comprehensive tracking without modifying business logic.