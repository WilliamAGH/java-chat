# All Parsing and Markdown Logic Documentation

## Executive Summary

This document provides a comprehensive analysis of all parsing and markdown processing logic in the Java Chat application. The system uses a hybrid server-side/client-side architecture with both legacy regex-based processing and modern AST-based processing, creating complexity that needs systematic documentation.

## Architecture Mind Map

```
📋 MARKDOWN PROCESSING ARCHITECTURE
├── 🎯 ENTRY POINTS
│   ├── /api/chat/stream (ChatController.stream)
│   ├── /api/markdown/render (MarkdownController.render)
│   └── /api/markdown/render/structured (MarkdownController.renderStructured)
│
├── 🔧 SERVER-SIDE PROCESSING
│   ├── UnifiedMarkdownService (AST-based, AGENTS.md compliant)
│   │   ├── CitationProcessor (AST visitor for links)
│   │   ├── EnrichmentProcessor (AST visitor for {{markers}})
│   │   └── Flexmark parser with custom extensions
│   │
│   └── MarkdownService (Legacy, regex-based, deprecated)
│       ├── preprocessMarkdown() - extensive regex preprocessing
│       ├── preserveEnrichments() - placeholder system
│       └── postProcessHtml() - DOM manipulation
│
├── 🌐 CLIENT-SIDE PROCESSING
│   ├── chat.html streaming logic
│   │   ├── formatText() - server-first, client-fallback
│   │   ├── clientMarkdownFallback() - minimal parser
│   │   └── preserveEnrichments() - mirror server placeholders
│   │
│   └── markdown-utils.js (MU namespace)
│       ├── normalizeInlineOrderedLists() - list marker fixing
│       ├── promoteLikelyJavaBlocks() - code fence promotion
│       ├── applyInlineEnrichments() - DOM enrichment rendering
│       └── createCitationPill() - citation UI components
│
├── 📊 STREAMING FLOW (GPT-5 → User)
│   ├── ChatService.streamAnswer() → Flux<String>
│   ├── ChatController.stream() → SSE events
│   ├── normalizeDelta() - token joining/cleanup
│   ├── UnifiedMarkdownService.process() - final markdown processing
│   └── ChatMemoryService.addAssistant() - persistence
│
└── 🔄 KEY TRANSITIONS & ISSUES
    ├── Regex → AST migration (incomplete)
    ├── Server → Client processing split
    ├── Enrichment duplication prevention
    └── Code block rendering consistency
```

## Detailed Component Analysis

### 1. Server-Side Processing Components

#### UnifiedMarkdownService (Primary, AST-based)

**File**: `src/main/java/com/williamcallahan/javachat/service/markdown/UnifiedMarkdownService.java`

**Purpose**: AGENTS.md compliant markdown processing using Flexmark AST instead of regex.

**Key Methods**:
- `process(String markdown)` - Main entry point, returns `ProcessedMarkdown`
- `extractAndPlaceholderizeEnrichments()` - Handles `{{type:content}}` markers
- `renderEnrichmentBlocksFromPlaceholders()` - Converts placeholders to HTML
- `renderInlineLists()` - DOM-based list processing (replaces regex)
- `postProcessHtml()` - Safe DOM manipulation for styling

**Processing Flow**:
```
Input Markdown
    ↓
Pre-normalize (code fences, spacing)
    ↓
Extract enrichments → placeholders
    ↓
Flexmark AST parsing
    ↓
Extract citations (CitationProcessor)
    ↓
Extract enrichments (EnrichmentProcessor)
    ↓
Render HTML from AST
    ↓
Restore enrichment placeholders
    ↓
DOM-based list rendering
    ↓
HTML post-processing
    ↓
Return ProcessedMarkdown
```

**Configuration**:
- Flexmark extensions: Tables, Strikethrough, TaskList, Autolink
- Soft breaks: `\n` (no forced `<br/>`)
- Hard breaks: `<br />\n`
- Code fences: `language-` prefix for Prism.js
- XSS protection: HTML escaping enabled

#### MarkdownService (Legacy, Deprecated)

**File**: `src/main/java/com/williamcallahan/javachat/service/MarkdownService.java`

**Purpose**: Original regex-based processing, being phased out.

**Key Issues**:
- Uses extensive regex processing (violates AGENTS.md)
- Complex preprocessing pipeline with multiple regex passes
- Manual string manipulation instead of structured parsing
- Deprecated methods marked with `@Deprecated`

**Legacy Methods**:
- `preprocessMarkdown()` - 15+ regex operations
- `fixInlineCodeBlocks()` - Complex pattern matching
- `preserveEnrichments()` - ZZENRICHZ placeholder system
- `postProcessHtml()` - String-based HTML manipulation

#### CitationProcessor (AST-based)

**File**: `src/main/java/com/williamcallahan/javachat/service/markdown/CitationProcessor.java`

**Purpose**: Extracts citations from markdown links using AST visitor pattern.

**Processing**:
- Visits `Link` nodes in Flexmark AST
- Extracts URL, title, and determines citation type
- Creates `MarkdownCitation` objects with position metadata
- Filters out non-citation links (mailto, javascript, etc.)

#### EnrichmentProcessor (Hybrid AST/Regex)

**File**: `src/main/java/com/williamcallahan/javachat/service/markdown/EnrichmentProcessor.java`

**Purpose**: Processes `{{type:content}}` enrichment markers.

**Current State**: Transitional - uses regex during migration to AST.

**Enrichment Types**:
- `{{hint:content}}` → Hint objects
- `{{warning:content}}` → Warning objects
- `{{background:content}}` → Background objects
- `{{example:content}}` → Example objects (with code block support)
- `{{reminder:content}}` → Reminder objects

### 2. Client-Side Processing Components

#### chat.html Streaming Logic

**File**: `src/main/resources/static/chat.html`

**Streaming Flow**:
```
User Input
    ↓
fetch('/api/chat/stream')
    ↓
SSE Event Processing
    ↓
Buffer tokens (10 tokens/100ms)
    ↓
normalizeDelta() - clean token joins
    ↓
formatText() - markdown processing
    ↓
DOM updates with debouncing
    ↓
Final UnifiedMarkdownService.process()
```

**Key Functions**:
- `formatText()` - Tries server processing first, falls back to client
- `clientMarkdownFallback()` - Minimal client parser
- `preserveEnrichments()` - Mirrors server placeholder system
- `upgradeCodeBlocks()` - Safe code block enhancement

#### markdown-utils.js (MU)

**File**: `src/main/resources/static/js/markdown-utils.js`

**Purpose**: Shared utilities for consistent markdown processing across views.

**Key Functions**:
- `normalizeInlineOrderedLists()` - Fixes list markers in prose
- `promoteLikelyJavaBlocks()` - Promotes Java code to fenced blocks
- `applyInlineEnrichments()` - Renders enrichment cards
- `createCitationPill()` - Citation UI components
- `createCitationsRow()` - Citation collections

**List Processing**:
- Supports: `1. 2. 3.`, `i. ii. iii.`, `a. b. c.`, `- * + • → ▸ ◆ □ ▪`
- Requires trigger phrases: `:`, `such as`, `include`, etc.
- Handles nested lists with colon notation

### 3. Streaming and GPT-5 Processing

#### ChatController.stream()

**File**: `src/main/java/com/williamcallahan/javachat/web/ChatController.java`

**Streaming Architecture**:
```
ChatService.streamAnswer() → Flux<String>
    ↓
Buffer tokens (10/100ms)
    ↓
normalizeDelta() - clean joins
    ↓
SSE formatting (data: lines)
    ↓
Heartbeat injection (20s intervals)
    ↓
Client-side rendering
    ↓
Final markdown processing
    ↓
ChatMemory persistence
```

**Token Processing**:
- `normalizeDelta()` removes spaces before punctuation
- Handles contractions (`don't` → no extra space)
- Buffers small tokens to reduce SSE overhead
- Maintains proper sentence spacing

#### GPT-5 Response Handling

**Server-Side**:
1. Raw markdown from GPT-5
2. Token-level streaming via SSE
3. Final `UnifiedMarkdownService.process()` on complete response
4. Structured citations and enrichments extracted
5. HTML rendering with proper escaping

**Client-Side**:
1. SSE event processing
2. Progressive markdown rendering
3. Enrichment card injection
4. Code syntax highlighting
5. Citation pill rendering

### 4. Data Structures and Types

#### ProcessedMarkdown (Result Object)

```java
public record ProcessedMarkdown(
    String html,
    List<MarkdownCitation> citations,
    List<MarkdownEnrichment> enrichments,
    List<ProcessingWarning> warnings,
    long processingTimeMs
)
```

#### MarkdownEnrichment (Sealed Interface)

```java
public sealed interface MarkdownEnrichment
    permits Hint, Warning, Background, Example, Reminder {
    String type();
    String content();
    EnrichmentPriority priority();
    int position();
}
```

#### MarkdownCitation (Citation Data)

```java
public record MarkdownCitation(
    String url,
    String title,
    String snippet,
    CitationType type,
    int position
)
```

### 5. Known Issues and Code Duplications

#### Major Issues

1. **Regex vs AST Processing Split**
   - Legacy `MarkdownService` still used in some paths
   - Migration incomplete - both systems active
   - Different behavior between regex and AST processing

2. **Enrichment Duplication**
   - Server processes `{{markers}}` into HTML cards
   - Client also processes `{{markers}}` for fallback
   - Risk of double-processing same content
   - Deduplication logic in `loadEnrichment()`

3. **Code Block Rendering Inconsistency**
   - Server: `UnifiedMarkdownService` handles fenced blocks
   - Client: `upgradeCodeBlocks()` modifies DOM
   - Different language detection logic
   - Potential conflicts in rendering

4. **List Processing Complexity**
   - Server: DOM-based list rendering in `renderInlineLists()`
   - Client: Regex-based in `markdown-utils.js`
   - Different trigger phrase requirements
   - Inconsistent behavior

#### Code Duplications

1. **Enrichment Placeholder System**
   - Server: `ZZENRICHZ${type}ZSTARTZZZ${content}ZZENRICHZ${type}ZENDZZZ`
   - Client: Same pattern in `preserveEnrichments()`
   - Manual synchronization required

2. **Citation Pill Rendering**
   - Server: Generates HTML in `UnifiedMarkdownService`
   - Client: `MU.createCitationPill()` creates DOM elements
   - Different styling approaches

3. **List Marker Detection**
   - Server: Complex regex patterns in `fixInlineLists()`
   - Client: Similar patterns in `normalizeInlineOrderedLists()`
   - Logic should be unified

#### Outstanding Issues

1. **Streaming Performance**
   - Token buffering may cause latency
   - Debounced rendering (120ms) affects responsiveness
   - Memory usage with large responses

2. **Error Handling**
   - Limited fallback when server processing fails
   - Silent failures in client-side processing
   - No structured error reporting

3. **Cache Inconsistency**
   - `MarkdownService` and `UnifiedMarkdownService` have separate caches
   - Different cache keys and invalidation logic
   - Potential cache misses for same content

4. **Mobile Responsiveness**
   - Streaming animations may not work well on slow connections
   - Touch event handling needs optimization
   - Memory usage on mobile devices

### 6. Processing Flow Examples

#### Complete Markdown Processing Flow

```
User Query → ChatService → GPT-5 API
                              ↓
Raw Markdown Response ← Streaming Tokens
                              ↓
Token Buffering (10 tokens/100ms)
                              ↓
normalizeDelta() - Clean token joins
                              ↓
SSE Events (data: lines)
                              ↓
Client: Progressive DOM updates
                              ↓
Client: formatText() → Server markdown API
                              ↓
UnifiedMarkdownService.process()
                              ↓
AST Parsing → Citations → Enrichments
                              ↓
HTML Rendering with enrichments
                              ↓
Client: DOM injection + syntax highlighting
                              ↓
ChatMemory persistence
```

#### Enrichment Processing Example

```
Input: "Here's a tip: {{hint:Use Optional for null safety}}"

Server Processing:
1. extractAndPlaceholderizeEnrichments()
   → "Here's a tip: ENRICHMENT_123"
2. Flexmark AST parsing
3. EnrichmentProcessor.extractEnrichments()
   → Hint("Use Optional for null safety", MEDIUM, 15)
4. renderEnrichmentBlocksFromPlaceholders()
   → "<div class="inline-enrichment hint">..."

Client Fallback:
1. preserveEnrichments()
   → "Here's a tip: ZZENRICHZhintZSTARTZZZUse Optional...ZZENRICHZhintZENDZZZ"
2. applyInlineEnrichments()
   → DOM enrichment card creation
```

### 7. Recommendations

#### Immediate Actions

1. **Complete AST Migration**
   - Remove legacy `MarkdownService` usage
   - Update all controllers to use `UnifiedMarkdownService`
   - Delete deprecated regex-based methods

2. **Unify Enrichment Processing**
   - Single source of truth for enrichment rendering
   - Eliminate client-side duplication
   - Consistent placeholder system

3. **Standardize List Processing**
   - Move all list logic to server-side AST processing
   - Remove client-side list manipulation
   - Consistent trigger phrase handling

#### Long-term Improvements

1. **Performance Optimization**
   - Implement streaming markdown processing
   - Reduce token buffering latency
   - Optimize cache hit rates

2. **Error Handling**
   - Structured error reporting
   - Graceful degradation paths
   - User-friendly error messages

3. **Testing Coverage**
   - Unit tests for all processing components
   - Integration tests for streaming flows
   - Performance regression tests

### 8. Configuration and Environment

#### Key Configuration Files
- `application.properties` - Basic settings
- `pom.xml` - Flexmark dependencies
- `UnifiedMarkdownService` constructor - Flexmark options

#### Environment Variables
- `GITHUB_TOKEN` - For GitHub Models API
- `QDRANT_URL` - Vector database endpoint
- `EMBEDDING_MODEL` - Text embedding model

#### Build Dependencies
- `flexmark-java` - Markdown parsing
- `jsoup` - HTML manipulation
- `caffeine` - Caching
- `prism.js` - Client syntax highlighting

---

## Conclusion

The markdown processing system in Java Chat represents a complex hybrid architecture undergoing migration from regex-based to AST-based processing. While the new `UnifiedMarkdownService` provides AGENTS.md compliance and better maintainability, the coexistence of legacy systems creates complexity and potential inconsistencies.

Key success factors for the migration:
1. Complete elimination of regex-based processing
2. Unified enrichment and citation handling
3. Consistent list processing across server/client
4. Comprehensive testing of all processing paths

The current system successfully handles the core requirements of streaming GPT responses with rich markdown formatting, but the architectural complexity suggests a need for focused cleanup efforts.

## 2025-09-05 Deep Dive Update (File-by-File Map + Streaming Realities)

This update consolidates how every relevant component behaves, when/where markdown is processed, and how code blocks, HTML, line breaks, paragraphs, and GPT‑5 streaming are handled. It also clarifies server vs client responsibilities and calls out rough edges during streaming with concrete improvements.

### Refined Mind Map (current state)
```
GPT-5 (tokens)
  → ResilientApiClient (parse JSON/SSE) 
    → ChatService.streamAnswer(Flux<String>)
      → ChatController.stream (SSE): data: <delta>
        → Browser (chat.html / guided.html)
          → accumulate fullText (debounced ~120ms)
            → POST /api/markdown/render/structured (server)
              → UnifiedMarkdownService.process(markdown)
                ↳ Flexmark AST → HTML (+ citations, enrichments)
                ↳ DOM-safe list normalization + post-processing
          → inject HTML, Prism highlight, add copy buttons
      (on complete)
      → UnifiedMarkdownService.process(fullResponse) persisted in ChatMemory
```

### Server Components and Behaviors

- ChatController.stream (`src/main/java/.../web/ChatController.java`)
  - Buffers model deltas (`bufferTimeout(10, 100ms)`) to reduce SSE event spam.
  - Normalizes token joins via `normalizeDelta()` (removes stray spaces before punctuation and contractions).
  - Frames SSE correctly (`data:` per line + blank line separator) and sends keepalive comments every 20s.
  - On completion, runs `UnifiedMarkdownService.process(fullResponse)` and stores the processed HTML in `ChatMemory` as the assistant turn.

- GuidedLearningController.stream (`.../web/GuidedLearningController.java`)
  - Same SSE framing/backpressure strategy. Combines chunks, appends to buffer, and on completion processes final `sb.toString()` via `MarkdownService.processStructured()` (which calls `UnifiedMarkdownService`).

- ResilientApiClient (`.../service/ResilientApiClient.java`)
  - Handles OpenAI and GitHub Models streaming variants.
  - For OpenAI: attempts to parse raw JSON chunks first, falls back to SSE JSON decoding via `extractStreamContent()` (reads `data:` lines → parse JSON → `choices[0].delta.content`).
  - For GitHub Models: always parses `data:` JSON lines from `https://models.github.ai/inference/v1/chat/completions`.
  - Strips accidental SSE artifacts when necessary.

- ChatService (`.../service/ChatService.java`)
  - Builds prompt with retrieval context and hands off to `ResilientApiClient.streamLLM()`.
  - Provides `processResponseWithMarkdown()` using `MarkdownService.processStructured()` for non-streaming use if needed.

- MarkdownController (`.../web/MarkdownController.java`)
  - `/api/markdown/render` → legacy wrapper that now routes to `processStructured()`.
  - `/api/markdown/preview` → uncached preview via `processStructured()`.
  - `/api/markdown/render/structured` → direct `UnifiedMarkdownService.process()` returning structured fields: HTML, citations, enrichments, warnings, timing, cleanliness.
  - Cache stats/clear endpoints proxy `UnifiedMarkdownService` cache.

- UnifiedMarkdownService (primary, AST-based) (`.../service/markdown/UnifiedMarkdownService.java`)
  - Pre-normalizes markdown without regex: ensures code-fence separation and closure; promotes bullets in prose conservatively before parsing.
  - Extracts `{{hint|warning|background|example|reminder:...}}` as placeholders to avoid AST fragmentation; builds enrichment HTML cards on reinsert.
  - Flexmark AST → HTML with options:
    - Escape raw HTML; soft-breaks are newlines; hard breaks become `<br />`.
    - Code blocks get `language-` classes for Prism.
  - DOM-safe post-processing with Jsoup:
    - `renderInlineLists()` converts inline bullets/ordered markers in paragraphs into `<ul>/<ol>` with preserved leading text and nested blocks (skips within `pre/code/enrichment`).
    - Adds styling hooks: `table.markdown-table`, `blockquote.markdown-quote`.
    - Readability helpers: sentence spacing normalization and splitting of very long paragraphs (heuristic, conservative).
  - Returns `ProcessedMarkdown(html, citations, enrichments, warnings, processingTimeMs)` and caches results (Caffeine).

- MarkdownService (legacy wrapper, deprecated methods) (`.../service/MarkdownService.java`)
  - New code should call `processStructured()` which delegates to `UnifiedMarkdownService`.
  - Retains older regex-heavy preprocessors (deprecated) for fallback compatibility only; not used in primary paths.

- MarkdownStreamProcessor (deprecated) (`.../service/MarkdownStreamProcessor.java`)
  - Intelligent buffering for block boundaries during streaming (code/list/sentence/paragraph). No longer in active use; replaced by client debounced re-renders + server AST processing.

### Client Components and Behaviors

- chat.html (`src/main/resources/static/chat.html`)
  - SSE consumption: assembles SSE events correctly (multiple `data:` lines per event; commit on blank line). Accumulates `fullText` and strips leaked `data:` tokens.
  - Debounces rendering (~120ms) with immediate flush triggers when:
    - Sentence end `[.!?]["')]*\s$`, double newline, or closing code fence ``````\n`.
  - On flush: posts `fullText` to `/api/markdown/render/structured`; injects returned HTML; then:
    - Calls `upgradeCodeBlocks` (conservative: ensure `language-` classes only), attach copy buttons, Prism highlight.
  - UX affordances: loading dots until first content, live typing cursor, copy buttons, citations/enrichment loaded after completion.

- guided.html (`src/main/resources/static/guided.html`)
  - Similar streaming/read loop with `renderMarkdown(text)` posting to `/api/markdown/render/structured` first, fallback to legacy render.
  - After injection: upgrades code blocks, attaches copy buttons, highlights, applies tooltips.

- markdown-utils.js (MU) (`src/main/resources/static/js/markdown-utils.js`)
  - Fallback-only transformations (kept minimal to avoid fighting server):
    - Normalize opening fences; conservative promotion of likely Java blocks when no fences (
      deprecated for primary paths).
    - Normalize inline ordered/bullet markers in prose when server is unavailable.
    - Enrichment rendering on client only if server left raw `{{...}}` (server usually emits cards).
    - Citation pills: converts inline `<a>` to consistent pills per UX standard.

### What processes what, where, and when

- Markdown parsing
  - Primary: server (`UnifiedMarkdownService.process`) during streaming flushes from client and once at completion for persistence.
  - Client: only as minimal fallback (`clientMarkdownFallback`) when server API is unavailable.

- Code blocks
  - Server: pre-normalizes malformed fences; Flexmark renders `<pre><code class="language-...">`; example enrichments parse fenced code inside cards.
  - Client: no structural conversion; only applies missing `language-` class heuristics and adds copy buttons; Prism highlights post-injection.

- HTML
  - Server escapes raw HTML; allows markdown-produced HTML; Jsoup post-processing adds structural classes; avoids regex HTML edits.
  - Client never uses `innerHTML` string hacks for transforms beyond the intentional content injection point; visual components created via DOM APIs.

- Line breaks and paragraphs
  - Soft breaks preserved as `\n` (browser renders as spaces in paragraphs); hard breaks become `<br />`.
  - Long paragraphs can be split (server heuristic) for readability; client avoids re-paragraphing.

- Streaming from GPT‑5 and timing
  - Tokens → buffered at server (10 tokens/100ms) → SSE `data:` frames.
  - Client accumulates `fullText`; debounced POST to `/api/markdown/render/structured` → inject returned HTML.
  - Final server-side processing occurs once at stream completion for persistence.

### Server vs Client boundaries (single source of truth)

- Server (authoritative)
  - Markdown-to-HTML rendering, enrichment card generation, inline list normalization, code fence normalization, XSS escaping, final persisted representation.

- Client (presentation-only)
  - Streaming assembly, debounced asks to server for HTML, syntax highlighting, copy buttons, citation pills for inline anchors, gentle UX flourishes (cursor, loading dots).
  - Minimal, conservative fallback markdown shaping only when server endpoints fail.

### Known issues, duplications, and rough edges

- Dual caches (legacy vs unified) — unified is the one that matters; legacy retained only for compat.
- Enrichments may be processed twice in edge cases (client fallback vs server cards). Client now no-ops if cards present, but duplication risk exists in fallback.
- Streaming jitter:
  - Re-rendering entire accumulated HTML each flush can cause layout jumps and repeated Prism work.
  - Code blocks may briefly lack `language-` classes until the next pass (minor)
  - Cursor repositioning after DOM replacement can flicker.
- List normalization exists both server-side (DOM-safe) and in MU fallback (parser-like). Keep server authoritative; avoid client mutations when server reachable.
- Citation pills are client-rendered; server provides structured citations but not pill HTML; duplication is intentional separation of concerns, but should be documented.

### Improvements to reduce “momentary ugliness” during streaming

Short-term (no protocol change):
- Render-diff instead of replace: preserve subtrees where possible (e.g., patch only changed tail container) to reduce reflow and Prism re-run scope.
- Scope Prism highlighting to only newly inserted nodes (track last child index) to avoid full re-highlight.
- Use `requestAnimationFrame` to coalesce DOM work and cursor updates into a single frame.
- Make debounce adaptive: 60–180ms based on frame budget; flush immediately on fence closures and double newlines (already done) plus at list item boundaries when a second item appears.

Medium-term (protocol-lite):
- Add server hint events: `event: status\ndata: {"block":"paragraph|list|code","state":"open|close"}` to guide client flush timing more precisely without sending HTML.

Recommended (cleanest UX): Server-streamed HTML blocks
- Implement a `StreamingMarkdownRenderer` on the server that buffers tokens and emits completed block HTML chunks via SSE with a structured envelope, e.g. `{type:"html", blockType:"paragraph|list|code", content:"..."}`.
- Client simply appends block HTML; no frequent re-posting to `/api/markdown/render/structured` during stream, which removes round-trips and reduces jitter.
- See `docs/potential-sse-migration-plan-sep-2-2025.md` for outline; aligns with `StreamEventType` vision.

### Bottom line

- Today: server is the markdown authority; client asks server for HTML repeatedly during stream, then server processes final once for persistence. This is reliable and safe but causes some transient jitter.
- Next: stream server-rendered HTML blocks to eliminate re-render churn and polish the streaming experience without sacrificing AST correctness.

## Consolidated Improvement Plan (addresses current issues)

This plan targets four user-reported issues and the broader goals of idempotence, DRY, and eliminating ugly intermediate rendering during streaming.

### Issue 1: Monotype code not formatted inside enrichment cards

- Symptom: Inline code and fenced blocks inside `{{background|reminder|hint|warning: ...}}` render as plain text.
- Root cause: `buildEnrichmentHtml` escapes text and inserts `<br>` without markdown parsing for non-`example` types.
- Server-side solution (AST-compliant, no regex):
  - Add `renderEnrichmentMarkdown(String content, boolean allowBlocks)` in `UnifiedMarkdownService` that:
    - Parses the enrichment content with the existing Flexmark `parser` and `renderer` to produce HTML.
    - For inline-only variants (e.g., hint/reminder/background/warning), split paragraphs by blank lines and render each via AST, permitting inline code and emphasis; allow fenced code blocks to render to `<pre><code>` when present (safer and expected).
    - Return HTML that we wrap inside the enrichment card body.
  - Change `buildEnrichmentHtml` to:
    - For `example`: keep current fenced code handling (already supported).
    - For others: call `renderEnrichmentMarkdown(content, true)` to get proper `<code>`/`<pre><code>`.
  - Idempotence: repeated processing returns the same HTML; no string hacks.
  - Tests: Add cases ensuring `\`inline\`` becomes `<code>`, and fenced code becomes `<pre><code class="language-...">` within enrichment cards.

### Issue 2: Bracketed citations like "[CTX 1][CTX 2]" leak into output

- Symptom: Model emits context markers (e.g., `[CTX 1][CTX 2]`) that appear in prose instead of becoming proper citation pills.
- Root cause: These markers are plain text, not markdown links; they survive the AST and client rendering path.
- Server-side solution (DOM-safe, no regex for HTML):
  - Add `removeContextMarkers(Document doc)` in `UnifiedMarkdownService.postProcessHtml` flow:
    - Traverse text nodes outside `pre, code, .inline-enrichment`.
    - Use a small state machine to remove occurrences of bracketed tokens that match `[` + `CTX` + space + digits + `]` (literal scanning, no regex). Also remove repeated sequences like `[CTX 1][CTX 2]` by collapsing to empty.
    - This is safe and deterministic; it does not attempt to convert them to superscripts—it removes them so that the proper citations row (loaded via API) remains the single citation UI.
  - Optional: add a generic guard that removes isolated bracket-only sequences with `CTX` prefix; keep numeric-only `[1]` and friends intact for future mapping if needed.
  - Tests: feed paragraphs containing `[CTX 1] [CTX 2]` and assert they do not appear in output; ensure normal `[1]` survives.

### Issue 3: Random extra spaces in final cleansed output (e.g., before `)` or before `.`)

- Symptom examples: `JVM )`, `bytecode .`, `general -purpose`, stray spaces around quotes (`“`), etc.
- Contributing factors: token-join artifacts at stream time and occasional server post-process spacing tweaks.
- Two-layer fix (join-time + final HTML):
  - Join-time (ChatController.normalizeDelta): expand the "leading punctuation" set to include `) ] } % ” ’ "` and hyphen `-` handling.
    - If incoming delta starts with a closing punctuation and the buffer ends with a space, drop that space.
    - If incoming delta starts with `-` and the buffer ends with `letter + space`, drop the space to produce `general-purpose` instead of `general -purpose`.
    - If incoming delta starts with `.”`/`.'` etc., collapse extra space from tail.
  - Final pass (UnifiedMarkdownService.postProcessHtml): add `normalizeWhitespace(Document doc)`:
    - For each text node (outside `pre, code, .inline-enrichment`), scan characters and eliminate spaces before closers `.,;:!?)]}` and after openers `([{` and opening quotes.
    - Ensure single space after sentence punctuation when followed by letters (complements `fixSentenceSpacing`).
    - Deterministic character scanning (no regex) to comply with AGENTS.md.
  - Tests: assert no space before closing punctuation and hyphens; ensure we don’t mutate code or enrichment content.

### Issue 4: Streaming artifact "event: done [DONE]" visible to users

- Symptom: Literal `event: done [DONE]` shows up in the streamed text.
- Causes:
  - Server currently appends `event: done\ndata: [DONE]\n\n` as the terminal SSE event; client accumulates `data: [DONE]` into `fullText` and later strips only `data:` prefixes, leaving `[DONE]` (and sometimes stray `event:` text).
- Server-side fix (preferred):
  - In `ChatController.stream` and `GuidedLearningController.stream`, drop the terminal `data: [DONE]` payload. Either just complete the stream, or send a terminal SSE with only `event: done` (no data field). The client should not receive any `[DONE]` in a `data:` line.
  - Keep `takeUntil` logic server-side to close promptly.
- Client-side guard (defense-in-depth):
  - In `chat.html`/`guided.html`, when committing an SSE event, if the `eventBuf.trim()` equals `[DONE]`, discard the event instead of appending to `fullText`.
  - Continue ignoring non-`data:` lines; remove the generic `data:` stripping hack since it’s no longer required.
  - Tests: mock SSE frames to verify `[DONE]` is never appended to UI.

### Idempotence and DRY plan

- Single authority for markdown rendering: `UnifiedMarkdownService`.
  - Keep client markdown logic strictly fallback-only and minimal; don’t transform structure when server is reachable.
- Centralize enrichment rendering:
  - Always use server-side enrichment card generation; client only adds visual enhancements (pills/hover) and deduplicates via `data-enrichment-type`.
- Single whitespace normalization pass:
  - Move spacing corrections to `postProcessHtml.normalizeWhitespace` and expand `normalizeDelta` only for token-join correctness. Remove scattered spacing tweaks elsewhere.
- Citation handling:
  - Remove bracketed `CTX` markers on server; keep pills rendering entirely in client with structured citation data from API.
- Streaming termination:
  - No `[DONE]` user-visible payloads; stream completion should be via SSE close, not content.

### Eliminate ugly intermediate streaming (Two-lane “shadow” rendering)

- Goal: immediate, legible feedback without UI thrash; polished committed blocks as they are ready.
- Approach: Two-lane rendering in the client, no duplication of parsing logic.
  - Shadow lane: ephemeral monospaced bubble showing raw streamed text with minimal formatting (safe whitespace collapse, no list/code transforms). This is purely presentational and idempotent.
  - Committed lane: as soon as server returns HTML for the accumulated text (or later, server streams block HTML), append/update the committed bubble and cross-fade the corresponding shadow segment.
  - Mechanics:
    - Maintain `fullText` plus `commitIndex`. The shadow shows `fullText.substring(commitIndex)`. When server HTML is injected, set `commitIndex = fullText.length` and fade out the shadow.
    - Use `requestAnimationFrame` to batch DOM writes; constrain Prism highlighting to newly inserted committed nodes only.
    - Cursor lives only in the shadow lane; disappears when commit occurs.
  - Future protocol (best): server streams block-level HTML chunks; client appends directly to committed lane, with the shadow lane only for the brief pre-block moments.

### Implementation checklist (high level)

- Server
  - `UnifiedMarkdownService`:
    - Add `renderEnrichmentMarkdown` and integrate into `buildEnrichmentHtml`.
    - Add `removeContextMarkers(doc)` and `normalizeWhitespace(doc)` to `postProcessHtml` pipeline.
  - `ChatController`/`GuidedLearningController`:
    - Remove `data: [DONE]` terminal payload; optionally keep `event: done` without data.
  - `ChatController.normalizeDelta`:
    - Expand punctuation set and add hyphen join rules.

- Client
  - SSE reader (chat.html/guided.html):
    - Discard `[DONE]` data frames; remove global `data:` stripping.
    - Implement two-lane shadow rendering with cross-fade; scope Prism to appended nodes.
  - MU utilities:
    - Keep fallback-only transforms; ensure idempotent class additions (copy buttons/Prism) via presence checks.

### Acceptance criteria

- Enrichment cards render inline code and fenced code blocks correctly across all types.
- No `[CTX n]` artifacts in output prose; citations continue to appear as pills.
- No stray spaces before punctuation/closers; hyphenated words render correctly; no regressions inside code/pre/enrichment blocks.
- No `event: done`/`[DONE]` appears in chat UI text.
- Streaming visual polish: reduced layout shifts; cursor flicker eliminated; frame budget a respected.
