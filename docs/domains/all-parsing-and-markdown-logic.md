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
│   └── Svelte frontend (frontend/src/lib/)
│       ├── markdown.ts - parseMarkdown() with DOMPurify sanitization
│       ├── javaLanguageDetection.ts - auto-detect Java code blocks
│       └── AssistantMarkdownBody.svelte - streaming-aware markdown rendering
│
├── 📊 STREAMING FLOW (GPT-5.2 → User)
│   ├── ChatService.streamAnswer() → Flux<String> (uses OpenAIStreamingService)
│   ├── ChatController.stream() → SSE events
│   ├── normalizeDelta() - token joining/cleanup
│   ├── UnifiedMarkdownService.process() - final markdown processing
│   └── ChatMemoryService.addExchange() - atomic user/assistant persistence
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

#### Svelte Frontend Markdown Processing

**File**: `frontend/src/lib/services/markdown.ts`

**Purpose**: Client-side markdown parsing with DOMPurify sanitization for the Svelte frontend.

**Key Functions**:
- `parseMarkdown(markdownText, isStreaming)` - Main entry point, parses markdown to sanitized HTML
- `escapeHtml()` - Safe HTML escaping for inline code
- `createEnrichmentExtension()` - Custom marked extension for `{{type:content}}` markers

**Streaming-Aware Parsing**:
- Uses separate `COMPLETE_MARKDOWN_PARSER` and `STREAMING_MARKDOWN_PARSER` instances
- `isStreaming` flag enables graceful handling of incomplete enrichment markers
- DOMPurify sanitization for XSS protection

**Java Language Detection**:

**File**: `frontend/src/lib/services/javaLanguageDetection.ts`

- `applyJavaLanguageDetection()` - Auto-detects Java code in unmarked blocks
- Adds `language-java` class for syntax highlighting

**Component Integration**:

**File**: `frontend/src/lib/components/AssistantMarkdownBody.svelte`

- Renders assistant message markdown with streaming support
- Applies Java language detection and syntax highlighting
- Shows streaming cursor animation during message assembly

### 3. Streaming and GPT-5.2 Processing

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

#### GPT-5.2 Response Handling

**Server-Side**:
1. Raw markdown from GPT-5.2
2. Token-level streaming via SSE
3. Final `UnifiedMarkdownService.process()` on complete response
4. Structured citations and enrichments extracted
5. HTML rendering with proper escaping

**Client-Side (Svelte)**:
1. SSE event processing
2. Progressive markdown rendering via `parseMarkdown()`
3. Enrichment card injection (client-side enrichment extension)
4. Code syntax highlighting (Prism.js)
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
User Query → ChatService → GPT-5.2 API
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
- `build.gradle.kts` - Flexmark dependencies
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
GPT-5.2 (tokens)
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

- ChatController.stream: buffer tokens (10/100ms); clean joins via `normalizeDelta()`; frame SSE (`data:` + blank line) with 20s heartbeats; on complete, `UnifiedMarkdownService.process(fullResponse)` → persist to `ChatMemory`.
- GuidedLearningController.stream: same SSE framing/backpressure; combine chunks; on complete process via `MarkdownService.processStructured()` (delegates to unified service) and persist.
- OpenAIStreamingService: primary streaming via official OpenAI Java SDK; no manual SSE parsing.
- ChatService: assemble prompt with retrieval context; stream via `ResilientApiClient`; optional non-streaming `processResponseWithMarkdown()`.
- MarkdownController: `/render` and `/preview` route to `processStructured()`; `/render/structured` returns HTML + structured metadata; cache stats/clear proxy unified service.
- UnifiedMarkdownService: pre-normalize (no regex), extract/restore enrichments, Flexmark AST → HTML (escaped raw HTML; soft=`\n`, hard=`<br />`; `language-` code), DOM post-process (`renderInlineLists`, styling hooks, readability helpers), cache result.
- MarkdownService: legacy wrapper; call `processStructured()`; deprecated regex preprocessors retained only for fallback.
- MarkdownStreamProcessor: deprecated streaming bufferer; replaced by client debounced re-renders + server AST.

### Client Components and Behaviors

- chat.html: assemble SSE events (multi `data:` lines; commit on blank line); maintain `fullText`; debounce ~120ms with immediate flush on sentence end, double newline, or closing code fence; on flush POST `/api/markdown/render/structured` → inject HTML → conservative `upgradeCodeBlocks`, copy buttons, Prism; UX: loading dots, typing cursor, citations/enrichment after completion.
- guided.html: similar streaming + `renderMarkdown(text)` → structured endpoint first; then code upgrades, copy, highlight, tooltips.
- markdown-utils.js (MU): fallback-only transforms (normalize opening fences; conservative Java promo; inline list normalization); client enrichments only if server didn’t render; build citation pills from anchors.

### What processes what, where, and when

- Markdown (server-side endpoints): server processes via `UnifiedMarkdownService.process` for REST endpoints; returns HTML with citations/enrichments.
- Markdown (Svelte frontend): client-side `parseMarkdown()` with DOMPurify; streaming-aware enrichment handling; no server round-trip during streaming.
- Code blocks: server pre-normalizes/AST → `<pre><code class="language-...">`; client adds Java language detection + Prism highlight.
- HTML: server escapes raw HTML and adds structural classes; client sanitizes with DOMPurify.
- Paragraphs: soft=`\n` (space in paragraphs), hard=`<br />`; server may split long paragraphs; client doesn't re-paragraph.
- Streaming: server buffers (10/100ms) → SSE; Svelte client renders progressively with `parseMarkdown(markdownText, true)`; server processes once more on completion for persistence.

### Server vs Client boundaries

- Server (REST endpoints)
  - Markdown-to-HTML rendering for API consumers, enrichment card generation, inline list normalization, code fence normalization, XSS escaping, final persisted representation.

- Svelte Client (frontend)
  - Streaming SSE assembly, client-side markdown rendering via `parseMarkdown()`, enrichment extension processing, syntax highlighting, copy buttons, citation pills, UX flourishes (cursor, loading dots).
  - Two parser modes: streaming (graceful incomplete markers) and complete (strict parsing).

### Known issues, duplications, and rough edges

- Dual caches (legacy vs unified) — unified matters; legacy only for compat.
- Enrichments may double-render in fallback; client no-ops when cards exist, but edge risk remains.
- Streaming jitter: whole-bubble re-render causes layout shifts + repeated Prism; transient missing `language-` class; cursor flicker.
- List normalization in both server and MU fallback; prefer server and avoid client mutations when reachable.
- Citations: client renders pills; server supplies structured data (by design, but document clearly).

### Improvements to reduce “momentary ugliness” during streaming

- Short-term: render-diff tail only; Prism on newly inserted nodes; batch updates via `requestAnimationFrame`; adaptive debounce (60–180ms) with immediate flush on fence closes/double newlines/2nd list item.
- Medium-term: server hint events (`event: status` with `{block,state}`) to guide client flush timing.
- Recommended: server-streamed block HTML via a `StreamingMarkdownRenderer` envelope `{type:"html", blockType, content}`; client appends blocks; no per-flush `/render/structured` round-trips (see `docs/potential-sse-migration-plan-sep-2-2025.md`).

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

- Server: (a) UnifiedMarkdownService → add `renderEnrichmentMarkdown`; integrate into `buildEnrichmentHtml`; add `removeContextMarkers(doc)` and `normalizeWhitespace(doc)` in post-process; (b) Controllers → drop `data: [DONE]` (optionally keep `event: done` without data); (c) `normalizeDelta` → expand punctuation/hyphen rules.
- Client: (a) SSE reader → discard `[DONE]`; remove global `data:` stripping; (b) implement two-lane shadow with cross-fade; Prism scoped to appended nodes; (c) MU utilities → fallback-only; idempotent copy/highlight additions.

### Acceptance criteria

- Enrichment cards render inline/fenced code correctly across all types; `[CTX n]` never appears in prose; punctuation/hyphen spacing correct without touching code/pre/enrichment; no `event: done`/`[DONE]` in UI; streaming polish: fewer layout shifts, no cursor flicker, frame budget respected.
