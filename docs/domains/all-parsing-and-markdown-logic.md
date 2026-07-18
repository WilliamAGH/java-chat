# Markdown Processing Boundaries

This document describes the ownership boundaries for Markdown processing. Source code remains the authority for implementation details; this guide intentionally does not mirror method inventories, parser settings, marker catalogs, or rendering algorithms.

## Architecture

```text
LLM text chunks
    |
    v
Spring SSE endpoints ------------------------+
    |                                        |
    | text events                            | completed plain Markdown
    v                                        v
Svelte stream owner                    In-memory chat history commit
    |
    v
frontend Markdown service
    |
    v
sanitized HTML -> message/lesson components

Independent API consumers
    |
    v
MarkdownController -> MarkdownService -> UnifiedMarkdownService
                                      -> structured server result
```

The browser and server have distinct consumers. The Svelte application renders streamed Markdown locally so it does not make a Markdown-rendering request for every partial response. The server renderer exists for the Markdown REST API and for server-owned lesson rendering. Chat history keeps the completed plain Markdown response in process-local memory.

## Canonical owners

### Browser rendering

- [`frontend/src/lib/services/markdown.ts`](../../frontend/src/lib/services/markdown.ts) owns browser Markdown parsing, streaming-aware handling, enrichment rendering, and HTML sanitization.
- [`frontend/src/lib/components/AssistantMarkdownBody.svelte`](../../frontend/src/lib/components/AssistantMarkdownBody.svelte) owns assistant-message presentation, post-stream language detection, syntax highlighting, and the streaming cursor.
- [`frontend/src/lib/components/LearnView.svelte`](../../frontend/src/lib/components/LearnView.svelte) owns guided-lesson request lifecycle and renders lesson Markdown through the browser Markdown service.
- [`frontend/src/lib/components/MessageBubble.svelte`](../../frontend/src/lib/components/MessageBubble.svelte) selects assistant Markdown presentation for chat messages.

Do not duplicate parser modes, supported constructs, sanitization rules, or enrichment presentation details in documentation. Read the linked sources and their tests when changing browser behavior.

### Server rendering

- [`src/main/java/com/williamcallahan/javachat/service/markdown/UnifiedMarkdownService.java`](../../src/main/java/com/williamcallahan/javachat/service/markdown/UnifiedMarkdownService.java) owns server-side Markdown parsing, structured extraction, HTML rendering, and render caching.
- [`src/main/java/com/williamcallahan/javachat/service/MarkdownService.java`](../../src/main/java/com/williamcallahan/javachat/service/MarkdownService.java) is the Spring-facing facade for the unified renderer. It does not define a second parsing pipeline.
- [`src/main/java/com/williamcallahan/javachat/web/MarkdownController.java`](../../src/main/java/com/williamcallahan/javachat/web/MarkdownController.java) owns the public Markdown rendering and cache-management HTTP contract.
- [`src/main/java/com/williamcallahan/javachat/domain/markdown/ProcessedMarkdown.java`](../../src/main/java/com/williamcallahan/javachat/domain/markdown/ProcessedMarkdown.java) owns the structured server result contract.

Do not describe `MarkdownService` as a legacy regex fallback. It delegates to the unified AST renderer, and there is no alternate server rendering path.

### Enrichment catalog

[`src/main/resources/enrichment-kinds.manifest`](../../src/main/resources/enrichment-kinds.manifest) is the single semantic owner for enrichment tokens and presentation metadata. Server and frontend code project that manifest. Documentation must not reproduce its inventory.

## Streaming and persistence

[`ChatController`](../../src/main/java/com/williamcallahan/javachat/web/ChatController.java) and [`GuidedLearningController`](../../src/main/java/com/williamcallahan/javachat/web/GuidedLearningController.java) own their SSE request lifecycles. They emit text and typed terminal events; the Svelte request owner accumulates the text and progressively re-renders it through the browser Markdown service.

Only a successfully completed stream is committed to in-memory chat history. Terminal failures, cancellation, and backpressure overflow do not retain a partial assistant response. The frontend also guards request ownership so callbacks from an aborted or superseded request cannot mutate the active view.

## Security boundary

Both rendering paths treat model output as untrusted:

- The browser service sanitizes generated HTML before a Svelte component inserts it.
- The server renderer escapes raw HTML and returns its typed structured contract.

Any change that permits new elements, attributes, URL schemes, or raw HTML must update the canonical renderer and its security tests. Do not add component-level bypasses or a fallback renderer.

## Change checklist

When changing Markdown behavior:

1. Change the canonical browser or server owner, not this document.
2. Update the owning unit tests and any API integration tests.
3. Keep enrichment names and presentation metadata projected from the manifest.
4. Verify complete and partial streaming input when browser behavior changes.
5. Verify raw HTML, malformed Markdown, links, code blocks, and enrichment content remain safely rendered.
6. Keep server API behavior and Svelte behavior explicit; do not imply that the Svelte stream calls the server Markdown API.

## Historical implementations

The former static-page Markdown utilities and per-chunk server-rendering flow are not part of the current Svelte architecture. Historical filenames, removed DOM utilities, and retired request flows belong in Git history, not in this current-state guide.
