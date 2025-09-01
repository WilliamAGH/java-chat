---
description: "java-chat — simple, beautiful Java learning chat that aggregates live docs, augments with LLMs, and streams answers with citations in a ShadCN-inspired UI"
alwaysApply: true
---

# java-chat Agents & Architecture Guide (AGENTS.md)

This document defines how agents, backend services, and the (future) UI for java-chat should behave. It sets the quality bar, UX principles, architecture, and operational rules. Follow this file as the source of truth for agent behavior and system decisions.

Vision: Make it incredibly easy to learn Java. Users ask natural questions; the app retrieves and interprets authoritative documentation, augments with LLM reasoning, optionally performs web enrichment, and streams back concise, accurate, and beautifully presented answers with citations and “URL pill” links.

Design/UX Quality Bar (non-negotiable):
- Beautiful, clean, crisp ShadCN-inspired UI
- Live, fresh, performant streaming that matches or exceeds the quality of v0.dev, ChatGPT, Claude, Perplexity, and modern Apple design
- Always provide citations and clickable URL pill boxes
- Minimal cognitive load; elegant typography, motion, and spacing; accessible by default (WCAG 2.2 AA)


## Core Principles

1) Simplicity over cleverness
- Thin architecture. Prefer Spring Boot defaults. Clear, direct code paths.
- Avoid unnecessary abstractions. Every layer must reduce complexity.

2) Truthfulness with citations
- Ground answers in authoritative sources (official JDK docs, Spring docs, Javadoc, reputable tutorials).
- Cite everything that influences the response. If confidence is low or sources conflict, say so.

3) Streaming-first experience
- Stream tokens quickly and steadily. Defer heavy computation until necessary.
- Show “insights” and citations as they become available. Do not block the stream waiting for all sources.

4) Gradual web enrichment
- Start with local indexes and curated docs. If recall is inadequate, escalate to web search/scrape/fetch.
- Respect robots, rate limits, and safety rules. Never leak secrets.

5) Beauty and performance
- ShadCN-inspired modern UI components, tasteful motion, excellent readability.
- Low-jitter streaming, responsive updates, resilient to flaky networks.

6) Accessibility and inclusion
- Keyboard-first. Screen reader friendly. High-contrast theme support. Prefers-reduced-motion compliant.


## User Experience Spec (high-level)

Chat surface
- Streaming answer area with structured sections: short answer, deeper explanation, examples, and references
- Inline tooltips for key terms; hover to see definitions from authoritative sources
- “URL pills” for each citation: favicon, domain, title, and action to open in new tab
- Code blocks with copy button, syntax highlight, and line numbers when applicable
- Collapsible “background & context” section for curious learners

Information architecture
- Start with the most helpful, accurate synthesis
- Then provide supporting context, caveats, and deeper links
- Present citations inline and at the end; each reference must map to a real URL

Interaction model
- Suggestions for follow-up prompts (e.g., “Show me an example with streams and Optionals”)
- Cmd/Ctrl+K universal search to quickly open docs or past threads
- Keyboard shortcuts for copy, open all citations, and toggle details

Loading and resilience
- Skeletons and shimmer states for first paint
- Progressive hydration of citations and tooltips as sources resolve
- Clear retry affordances if a source fetch fails (never dead-end the user)


## System Behavior and Agent Roles

High-level flow
1) Understand: Classify the user query (concept, API reference, how-to, error, performance, etc.).
2) Retrieve: Pull relevant passages from local aggregated documentation/embeddings.
3) Enrich (when needed): If recall/confidence is low, perform targeted web search + scrape + parse.
4) Synthesize: Use LLM to interpret the query and the retrieved passages; produce an accurate, concise answer.
5) Cite: Attach citations for all used sources; generate URL pill metadata.
6) Stream: Send partials immediately; upgrade with more details/citations as they become available.

Agents (logical responsibilities)
- QueryRouter: Determines query type and selects retrieval strategy.
- Retriever: Fetches from vector store + curated indexes; may do re-ranking.
- WebEnrichment: Executes controlled search/scrape/fetch only if needed.
- Synthesizer: SOTA LLM that merges retrieved content into a coherent response.
- CitationVerifier: Validates that each claim maps to a cited source; filters weak sources.
- UIStreamer: Packages messages into a streaming-friendly protocol (SSE/WebFlux) with incremental updates.

Non-goals
- No heavy microservices or orchestration. Keep it small, elegant, maintainable.
- No brittle scraping of low-quality sites. Prefer official and high-signal sources.


## Backend Architecture (current project)

Stack (aligns with pom.xml)
- Java 21, Spring Boot 3.5.5
- Spring AI (OpenAI-compatible) via GitHub Models
- Qdrant vector store (Spring AI starter)
- Spring WebFlux for streaming responses

Key modules/dependencies
- org.springframework.ai:spring-ai-starter-model-openai
- org.springframework.ai:spring-ai-starter-vector-store-qdrant
- org.springframework.boot:spring-boot-starter-web + webflux

Configuration (Makefile-driven runtime args)
- spring.ai.openai.api-key: sourced from $GITHUB_TOKEN (do not hardcode)
- spring.ai.openai.base-url: defaults to https://models.github.ai/inference unless overridden
- spring.ai.openai.chat.options.model: defaults to openai/gpt-5-mini
- spring.ai.openai.embedding.options.model: defaults to openai/text-embedding-3-large

Secrets & safety
- Never print or log secrets. Do not echo tokens in terminals, logs, or UIs.
- Use environment variables (e.g., GITHUB_TOKEN) loaded from .env during local dev.
- For any web fetches, ensure PII/secret-safe requests and sanitized outputs.

Vector store
- Local Qdrant via Docker Compose; start/stop with Makefile targets.
- Ingestion endpoint is provided for indexing curated sources.


## Commands and Verification Loops

Build and run
```bash path=null start=null
make build                    # mvnw -DskipTests package
make run                      # builds then runs the jar with runtime args
make dev                      # spring-boot:run with devtools and arguments
```

Docker services
```bash path=null start=null
make compose-up               # start Qdrant
make compose-down             # stop Qdrant
make compose-logs             # tail logs
make compose-ps               # list services
```

Health checks
```bash path=null start=null
curl -sS http://localhost:8080/actuator/health
```

Ingestion and examples (adjust as needed)
```bash path=null start=null
# Ingest (example param)
curl -sS -X POST "http://localhost:8080/api/ingest?maxPages=1000"

# Sample citations lookup
curl -sS "http://localhost:8080/api/chat/citations?q=records"
```

Secrets for local dev (.env loaded by Makefile if present)
```bash path=null start=null
GITHUB_TOKEN={{YOUR_GITHUB_TOKEN_WITH_MODELS_READ}}
# Optional overrides
GITHUB_MODELS_BASE_URL=https://models.github.ai/inference
GITHUB_MODELS_CHAT_MODEL=openai/gpt-5-mini
GITHUB_MODELS_EMBED_MODEL=openai/text-embedding-3-large
```

Verification loop (must pass before merging)
```bash path=null start=null
make build                                         # expect BUILD SUCCESS
ls -la target/*.jar                                # jar exists
make run & sleep 8; curl -sS :8080/actuator/health # healthy, then stop app
```


## Retrieval & Synthesis Rules

Retrieval (RAG)
- Prefer curated, authoritative Java resources (JDK docs, OpenJDK Javadoc, Spring docs, high-signal tutorials).
- Retrieve 5–15 candidates; re-rank by semantic and lexical signals.
- Deduplicate content; collapse near-duplicates; keep canonical source.

Synthesis
- Start with a concise, accurate answer; then provide layered explanation.
- Include examples tailored to the user’s context (Java version, library).
- Use disciplined formatting and headings; avoid verbosity.

Citations
- Every claim that comes from a source must include a citation.
- Provide URL pill metadata: title, domain, favicon (when possible).
- If a link is temporarily unavailable, mark as “pending” and update when fetched.

Web enrichment (conditional)
- Trigger if local recall is low, sources are outdated, or the user explicitly asks.
- Use safe search and polite fetch (rate limit, robots-aware).
- Clearly label any web-enriched sections and cite.

Hallucination guardrails
- If unsure, say “I’m not certain” and offer links for verification.
- Prefer quoting directly from sources rather than paraphrasing when nuance matters.


## Streaming Strategy

- Start streaming immediately with a skeletal outline.
- Emit sections in this rough order: short answer → key steps → examples → citations → extras.
- Append/tool in citations as they are confirmed; don’t delay the answer.
- Use SSE with backpressure; keep token cadence smooth; retry gracefully.


## Front-End (UI) Expectations

Quality bar and style
- ShadCN-inspired components (clean, modern, tasteful motion)
- Typography: elegant scale, high legibility, great contrast
- Motion: subtle, purpose-driven, respects prefers-reduced-motion

Components
- Chat composer with hotkeys and slash commands
- Streaming message list with partial updates and progressive citations
- Tooltip system for term definitions
- URL pill boxes: favicon, domain, title; open in a new tab
- Code blocks with syntax highlight, copy, and collapsible sections
- Command palette (Cmd/Ctrl+K) for quick doc search and actions

Performance & accessibility
- Optimistic rendering with server-confirmed updates
- A11y: Focus management, ARIA roles, keyboard navigation
- Theming: light/dark, high-contrast modes

Front-end stack guidance (when present)
- If a separate /web app is introduced, use Next.js + shadcn/ui with pnpm by default (bun acceptable).
- Keep UI and API cleanly separated; stream over SSE or fetch APIs.


## Safety, Privacy, and Secrets

- Never reveal or echo secrets (tokens, API keys). Use environment variables only.
- Sanitize all user-provided URLs and inputs before fetching.
- Respect content licenses and robots.txt. Cite and link back to sources.
- Avoid sending sensitive content to third parties. When in doubt, omit or ask for confirmation.


## Project Conventions (java-chat)

- Prefer Spring Boot defaults; configuration over code where possible.
- Keep controllers/services small and explicit. Constructor injection.
- Write JavaDoc as concise fragments (no full sentences) when documenting public APIs.
- Tests are encouraged (unit first). Stream correctness tests are valuable.


## Learning-First Content Architecture (Beautiful Separation of Layers)

Goal: Keep the application simple yet deeply effective for learning Java. For every query, produce a structured, layered response that separates knowledge, wisdom, background, info, tooltips, suggestions, and citations. Avoid a generic "chatbot" blob. Ensure each layer is independently useful and beautifully presented.

Layers and definitions
- Short Answer: One-paragraph answer optimized for correctness and speed; sets user expectations.
- Knowledge (Canonical Facts): Precise definitions, API contracts, signatures, constraints; grounded in authoritative docs.
- Wisdom (Practice & Judgment): Best practices, trade-offs, pitfalls, gotchas, performance notes, version differences.
- Background (Conceptual Framing): Why the concept exists, when to use it, related ideas, historical context; links to deeper readings.
- Info (Implementation Details): Step-by-step guidance, parameters, return types, compatibility matrices, minimal runnable examples.
- Tooltips (Micro-Definitions): Inline definitions for terms (e.g., Optional, Stream, sealed classes) shown on hover/tap.
- Suggestions (Next Steps): 2–3 high-signal follow-ups (e.g., “Show an example with Streams + Optionals”, “Compare List vs Set performance”).
- Citations (Sources): Verifiable links to exact sections in official docs; render as URL pills with favicon, title, domain.

Pipeline mapping (keep it simple)
- QueryRouter: Classify intent (concept, API reference, how-to, error, performance). Sets initial layer priorities.
- Retriever: Fetch 5–15 passages from embeddings + curated indexes; dedupe; prefer canonical sources.
- Synthesizer: Produce a structured JSON with slots: short_answer, knowledge, wisdom, background, info, tooltips[], suggestions[], citations[].
- CitationVerifier: Ensure every factual claim traces to at least one cited source; filter weak/noisy links.
- UIStreamer: Stream sections in order; allow late-arriving citations and tooltips without blocking rendering.

Streaming order (progressive disclosure)
1) Short Answer → immediate.
2) Knowledge → canonical facts quickly.
3) Info → runnable example and steps.
4) Suggestions → short and actionable.
5) Wisdom → pitfalls/trade-offs.
6) Background → deeper context.
7) Citations → progressively appended and verified.
8) Tooltips → on-demand from a shared tooltip registry keyed by term.

Simplicity heuristics (to avoid over-engineering)
- Fixed budgets: Max 120–180 words per layer (except citations); truncate gracefully with “Show more”.
- Suggestions capped to 3. Tooltips capped to 5 per message.
- Collapse near-duplicate sources; prefer official docs over blogs when both exist.
- If confidence < threshold, label sections as “Low confidence” and link to sources for verification.
- Prefer one minimal code example over multiple variants; link to alternatives in suggestions.

Teaching ethos
- Prefer examples that compile as-is; include imports as needed.
- When nuance matters (e.g., memory model, concurrency), quote the source directly and cite.
- Adapt to Java version context where known; otherwise default to current LTS.

UI presentation (ShadCN-inspired)
- Section chips or tabs (Short, Knowledge, Info, Wisdom, Background, Citations); sensible default is Short + Knowledge visible, others collapsible.
- URL pills with favicon, domain, title; click opens new tab; keyboard accessible.
- Tooltips surfaced via consistent glossary; keyboard navigable; screen-reader friendly descriptions.
- Smooth streaming with skeletons; partial citations appear as they verify.

Quality gates (Ultra Think pass)
- Check: Does each claim have a citation? Are sources authoritative?
- Check: Are layers distinct (no redundancy) and within token budgets?
- Check: Is the example minimal yet runnable? Are pitfalls called out?
- Check: Are suggestions actionable and contextually relevant?
- Check: Is the UI able to render each layer progressively without blocking?

Configuration toggles
- Learning Mode (default): Show all layers with conservative budgets; extra tooltips on.
- Expert Mode: Focus on Short + Knowledge + Info; Wisdom compact; Background collapsed by default.

## Checklist Before Merge

- Build success and jar present
- App runs locally; health endpoint green
- Retrieval works on at least a few representative queries
- Streaming produces smooth, incremental output
- Citations present and clickable; URL pills render as expected (if UI present)
- No secrets in logs, code, or docs


## Inspiration Borrowed from hybrid/back-end

- Convention over configuration and DRY by default
- Verification loops that prove behavior (not just code compiles)
- Documentation-first clarity: what to do and what to avoid

Adapted to java-chat’s goals: a smaller, simpler, teaching-focused app with top-tier UX and trustworthy, cited, streaming answers.

---
END OF AGENTS.MD

