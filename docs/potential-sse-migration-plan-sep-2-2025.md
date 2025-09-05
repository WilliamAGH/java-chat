# Migration Plan: 100% Server-Side Markdown Rendering with SSE Streaming

## Current Architecture Issues
1. **Dual Rendering Paths**: Both client (formatText) and server (MarkdownService) process markdown
2. **Enrichment Double Processing**: Applied in both server and client, causing formatting breaks
3. **SSE Streaming Conflicts**: Line-by-line streaming prevents proper block-level markdown detection
4. **Race Conditions**: Async client formatting can override server-rendered HTML

## Phase 1: Immediate Stabilization (COMPLETED)
✅ Added markdown preprocessing to fix inline lists/code blocks
✅ Enhanced CSS spacing rules for all block element combinations
✅ Improved postProcessHtml to handle all element pairs
✅ Fixed common markdown input issues at the source

## Phase 2: SSE Streaming Enhancement (1-2 days)
### Goal: Buffer complete markdown blocks before rendering

#### 2.1 Server-Side Buffering
- Modify ChatService.streamAnswer() to detect markdown block boundaries
- Buffer until complete block (paragraph, list, code block) is formed
- Send complete HTML chunks via SSE, not raw markdown

#### 2.2 New SSE Message Format
```java
// Instead of: data: raw markdown text
// Send: data: {"type": "html", "content": "<rendered html>", "blockType": "paragraph|list|code"}
```

#### 2.3 Client-Side Simplification
- Remove formatText() and formatTextClientSide() functions
- Simply append server-rendered HTML chunks to DOM
- Preserve only the enrichment marker visual transformation

## Phase 3: Unified Rendering Pipeline (2-3 days)
### Goal: Single source of truth for all markdown rendering

#### 3.1 Create StreamingMarkdownRenderer
```java
public class StreamingMarkdownRenderer {
    private StringBuilder buffer;
    private MarkdownService markdownService;
    
    public Optional<String> addChunk(String chunk) {
        // Buffer chunks, detect block boundaries
        // Return rendered HTML when block is complete
    }
}
```

#### 3.2 Modify Streaming Endpoint
- Use StreamingMarkdownRenderer in chat streaming
- Send HTML blocks with proper spacing preserved
- Include metadata for client-side handling

#### 3.3 Remove Client-Side Markdown Processing
- Delete all markdown parsing from index.html
- Keep only:
  - SSE message handling
  - DOM manipulation
  - Copy/export functionality
  - Syntax highlighting (Prism.js)

## Phase 4: Enrichment Unification (1 day)
### Goal: Server-only enrichment processing

#### 4.1 Server-Side Enrichment Rendering
- Move all enrichment HTML generation to MarkdownService
- Render complete enrichment blocks server-side
- Send as pre-formatted HTML via SSE

#### 4.2 Client Cleanup
- Remove applyCustomEnrichments() function
- Remove all enrichment regex patterns
- Keep only CSS styling for enrichment classes

## Phase 5: Testing & Optimization (1 day)
### Goal: Ensure robust handling of edge cases

#### 5.1 Comprehensive Test Suite
- Test incomplete markdown blocks
- Test enrichment markers in various positions
- Test code blocks with multiple languages
- Test nested lists and mixed content

#### 5.2 Performance Optimization
- Tune buffer sizes for optimal streaming
- Add server-side caching for common patterns
- Optimize regex patterns in preprocessing

## Implementation Order
1. **Week 1**: Phases 2-3 (SSE enhancement + unified pipeline)
2. **Week 2**: Phases 4-5 (enrichment unification + testing)

## Benefits
- **Consistency**: Single rendering engine eliminates discrepancies
- **Performance**: Server-side caching, no client-side regex processing
- **Maintainability**: All markdown logic in one place (MarkdownService)
- **Reliability**: No race conditions or async formatting issues
- **Streaming**: Proper block-aware streaming maintains formatting

## Rollback Plan
- Keep current client-side formatting commented out initially
- Feature flag for new streaming format
- Gradual rollout with monitoring

## Success Metrics
- Zero formatting inconsistencies between initial and final render
- All markdown edge cases properly handled
- No visual "jumps" during streaming
- Consistent spacing for all block elements
- All enrichment markers properly rendered