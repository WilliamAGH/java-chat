---
description: "Java Chat - AI-powered Java learning with streaming responses, citations, and knowledge augmentation"
alwaysApply: true
---

# Java Chat Agent Rules

## Document Organization [ORG]

- [ORG1] Purpose: keep every critical rule within the first ~250 lines; move long examples/notes to Appendix.
- [ORG2] Structure: Rule Summary first, then detailed sections keyed by short hashes (e.g., `[GT1a]`).
- [ORG3] Usage: cite hashes when giving guidance or checking compliance; add new rules without renumbering older ones.
- [ORG4] Directive language: rules use imperative/prohibitive phrasing ("do X", "no Y", "never Z"); no discretionary hedges ("prefer", "consider", "try to", "ideally", "when possible") that give agents interpretive latitude.

## Rule Summary [SUM]

- [ZA1a-c] Zero Tolerance Policy (zero assumptions, validation workflow, forbidden practices)
- [GT1a-l] Git, history safety, hooks/signing, lock files, and clean commits
- [CC1a-d] Clean Code & DDD (Mandatory)
- [ID1a-d] Idiomatic Patterns & Defaults
- [RC1a-f] Root Cause Resolution (single implementation, no fallbacks, no shims/workarounds)
- [FS1a-k] File Creation & Clean Architecture (search first, strict types, single responsibility)
- [TY1a-d] Type Safety (strict generics, no raw types, no unchecked casts)
- [FV1a-h] Frontend Validation (Zod schemas, discriminated unions, never swallow errors)
- [AB1a-d] Abstraction Discipline (reuse-first, no anemic wrappers)
- [NO1a-e] Null/Optional Discipline (no null returns, use Optional/empty collections)
- [AR1a-f] Architecture & Boundaries (canonical roots, layer rules, framework-free domain)
- [CS1a-h] Code Smells (primitive obsession, data clumps, magic literals)
- [VR1a-c] Verification Loops (build/test/run)
- [JD1a-h] Javadoc Standards (mandatory, why > what)
- [ND1a-h] Naming Discipline (intent-revealing, banned generics, constant naming, type names)

## [ZA1] Epistemic Humility (Zero Assumptions)

- [ZA1a] **Assume Blindness**: Your training data for APIs/versions is FALSE until verified.
- [ZA1b] **Scout Phase**: Before coding, use tools (`context7`, `perplexity`) and check local sources (`~/.m2`, `~/.gradle`, `node_modules`) to verify existence/signatures of APIs.
- [ZA1c] **Source Verification**: For dependency code questions, inspect `~/.m2` JARs or `~/.gradle/caches/` (for Java) or `frontend/node_modules` (for frontend) first; fallback to upstream GitHub; never answer without referencing code.
- [ZA1d] **Forbidden Practices**:
  - No `Map<String, Object>`, raw types, unchecked casts, `@SuppressWarnings`, or `eslint-disable` in production.
  - No trusting memory—verify every import/API/config against current docs.
- [ZA1e] **Mandatory Research**: You MUST research dependency questions and correct usage. Never use legacy or `@deprecated` usage from dependencies. Ensure correct usage by reviewing related code directly in `node_modules` or Gradle caches and using online tool calls.
- [ZA1f] **Dependency Search**: To search `node_modules` efficiently with `ast-grep`, target specific packages: `ast-grep run --pattern '...' frontend/node_modules/<package>`. Do NOT scan the entire `node_modules` folder.

## [GT1] Git, History, Hooks, Lock Files

- [GT1a] Never bypass pre-commit hooks or commit signing.
- [GT1b] If hooks fail, fix the environment; do not force the commit.
- [GT1c] Read-only git commands (e.g., `git status`, `git diff`, `git log`, `git show`) never require permission. Any git command that writes to the working tree, index, or history requires explicit permission.
- [GT1d] Commit message standards: one logical change per commit; describe the change and purpose; no tooling/AI references; no `Co-authored-by` or AI attribution.
- [GT1e] Do not amend or rewrite history (no `--amend`, no force pushes) without explicit user permission.
- [GT1f] Do not change branches (checkout/merge/rebase/pull) unless the user explicitly instructs it.
- [GT1g] Destructive git commands are prohibited unless explicitly ordered by the user (e.g., `git restore`, `git reset`, force checkout).
- [GT1h] Never delete lock files automatically (including `.git/index.lock`). Stop and ask for instruction.
- [GT1i] Treat existing staged/unstaged changes as intentional unless the user says otherwise; never “clean up” someone else’s work unprompted.
- [GT1j] Git commands that write to the working tree, index, or history require elevated permissions; never run without escalation.
- [GT1k] **Do Not Block On Baseline Diffs**: If `git status` already shows modified files when you start, assume those changes are intentional and continue the requested task without stopping to ask about them. Avoid touching unrelated files.
- [GT1l] **Stop Only On Concurrent Drift**: Only stop and ask for direction if a file changes unexpectedly *during your work* in a way that conflicts with edits you are actively making (e.g., a file you are editing changes on disk between reads/writes). Otherwise, proceed and keep changes scoped.

## [CC1] Clean Code & DDD (Mandatory)

- [CC1a] **Mandatory Principles**: Clean Code principles (Robert C. Martin) and Domain-Driven Design (DDD) are **mandatory** and required in this repository.
- [CC1b] **DRY (Don't Repeat Yourself)**: Avoid redundant code. Reuse code where appropriate and consistent with clean code principles.
- [CC1c] **YAGNI (You Aren't Gonna Need It)**: Do not build features or abstractions "just in case". Implement only what is required for the current task.
- [CC1d] **Clean Architecture**: Dependencies point inward. Domain logic has zero framework imports.

## [ID1] Idiomatic Patterns & Defaults

- [ID1a] **Defaults First**: Always prefer the idiomatic, expected, and default patterns provided by the framework, library, or SDK (Spring Boot, Java 21+, etc.).
- [ID1b] **Custom Justification**: Custom implementations require a compelling reason. If you can't justify it, use the standard way.
- [ID1c] **No Reinventing**: Do not build custom utilities for things the platform already does (e.g., use standard `Optional`, `Stream`, Spring `RestTemplate`/`WebClient`).
- [ID1d] **Dependencies**: Make careful use of dependencies. Do not make assumptions—use the correct idiomatic behavior to avoid boilerplate.

## [RC1] Root Cause Resolution — No Fallbacks

- [RC1a] **One Way**: Ship one proven implementation—no fallback paths, no "try X then Y", no silent degradation.
- [RC1b] **No Shims**: **NO compatibility layers, shims, adapters, or wrappers** that hide defects.
- [RC1c] **Fix Roots**: Investigate → understand → fix root causes. Do not add band-aids to silence errors.
- [RC1d] **Dev Logging**: Dev-only logging is allowed to learn (must not change behavior, remove before shipping).
- [RC1e] **Exceptions**: Use typed exception handling patterns; propagate meaningful errors, never swallow silently.
- [RC1f] **Explicit Violations**: Any of the following is a violation that must be removed, not justified:
  - Returning "best effort" results after a dependency failure (LLM, embeddings, vector store, database, filesystem).
  - Catching and logging an exception while continuing as if the operation succeeded.
  - Adding a secondary retrieval/indexing path that runs only when the primary path fails.

## [FS1] File Creation & Clean Architecture

- [FS1a] **Search First**: Search exhaustively for existing logic → reuse or extend → only then create new files.
- [FS1b] **Single Responsibility**: New features belong in NEW files named for their single responsibility. Do not cram code into existing files.
- [FS1c] **Canonical Roots**: `boot/`, `application/`, `domain/`, `adapters/`, `support/`.
- [FS1d] **Convention over Configuration**: Prefer Spring Boot defaults and existing utilities.
- [FS1e] **No Generic Utilities**: Reject `*Utils/*Helper/*Common`. Banned: `BaseMapper<T>`, `GenericRepository<T,ID>`, `SharedUtils`.
- [FS1f] **Large Files**: >500 LOC is a monolith. Extract pieces you touch into clean-architecture roots.
- [FS1g] **Domain Value Types**: Identifiers, amounts, slugs wrap in records with constructor validation; never raw primitives across API boundaries.
- [FS1h] **Single Responsibility Methods**: No dead code; no empty try/catch that swallows exceptions.
- [FS1i] **Dependency Injection**: Never manually instantiate `ObjectMapper`, `RestTemplate`, or `HttpClient`; always inject the Spring-managed bean.
- [FS1j] **Custom Properties**: Custom `app.*` properties require `@ConfigurationProperties` binding in `AppProperties`.
- [FS1l] **Contract**: `docs/contracts/code-change.md`

## [TY1] Type Safety

- [TY1a] **Strict Generics**: No raw types (e.g., `List` without `<T>`).
- [TY1b] **No Unchecked Casts**: If a cast is unavoidable, guard with explicit conversions (e.g., `Number::intValue`) instead of suppressing.
- [TY1c] **No Suppressions**: Never use `@SuppressWarnings` to resolve lint issues; fix the code.
- [TY1d] **No `Map<String, Object>`**: Use typed records or classes.

## [FV1] Frontend Validation (Zod)

- [FV1a] **Mandatory Validation**: All external data (API responses, SSE events, localStorage) MUST be validated through Zod schemas before use. External data is `unknown` until validated.
- [FV1b] **No `as` Casts**: Never use `as Type` assertions for external data. Use Zod `safeParse()` with proper error handling.
- [FV1c] **Discriminated Unions**: Return `{ success: true, data }` or `{ success: false, error }`, never `null`. No silent fallbacks.
- [FV1d] **Never Swallow Errors**: Every validation failure MUST be logged with full context via `logZodFailure()`. No empty catch blocks.
- [FV1e] **Record Identification**: Every error log MUST identify WHICH record failed (slug, ID, URL, endpoint name).
- [FV1f] **Single Source of Truth**: All schemas live in `frontend/src/lib/validation/schemas.ts`. Types are inferred via `z.infer<>`, never duplicated.
- [FV1g] **No `parse()`**: Use `safeParse()` exclusively. `parse()` throws and can crash rendering.
- [FV1h] **Schema Matches API**: Schema `optional()`/`nullable()`/`nullish()` MUST match the actual API contract. Verify against real responses.

@see `docs/type-safety-zod-validation.md` for full patterns and examples.

## [AB1] Abstraction Discipline

- [AB1a] **No Anemic Wrappers**: Do not add classes that only forward calls without domain value.
- [AB1b] **Earn Reuse**: New abstractions must earn reuse—extend existing code first; only add new type/helper when it removes real duplication.
- [AB1c] **Behavior Locality**: Keep behavior close to objects: invariants live in domain model/services, not mappers or helpers.
- [AB1d] **Delete Unused**: Delete unused code instead of keeping it "just in case."

## [NO1] Null/Optional Discipline

- [NO1a] **No Null Returns**: Public methods never return null; singletons use `Optional<T>`; collections return empty, never null.
- [NO1b] **Domain Invariants**: Domain models enforce invariants; avoid nullable fields unless business-optional and documented.
- [NO1c] **Empty Collections**: Return `List.of()`, `Set.of()`, `Map.of()` instead of null.
- [NO1d] **No Optional Params**: Optional parameters prohibited in business logic: accept nullable `T`, check internally; call sites unwrap with `.orElse(null)`.
- [NO1e] **Usage**: Use `Optional.map/flatMap/orElseThrow`; avoid `isPresent()/get()` chains.

## [AR1] Architecture & Boundaries

- [AR1a] **Controllers** (adapters/in/web): Translate HTTP to domain, delegate to one use case, return `ResponseEntity`; no repo calls, no business logic.
- [AR1b] **Use Cases** (application/): Transactional boundary, single command, orchestrate domain/ports.
- [AR1c] **Domain** (domain/): Invariants/transformations, framework-free, no Spring imports.
- [AR1d] **Adapters** (adapters/out/): Implement ports, persist validated models, no HTTP/web concerns.
- [AR1e] **Composition**: Favor composition over inheritance; constructor injection only; services stateless.
- [AR1f] **Monoliths**: Monolith = >500 LOC or multi-concern catch-all. Shrink on touch.

## [CS1] Code Smells

- [CS1a] **Primitive Obsession**: Wrap IDs/amounts/business values in domain types when they carry invariants.
- [CS1b] **Data Clumps**: When 3+ parameters travel together, extract into a record (`DateRange`, `PageSpec`, `SearchCriteria`).
- [CS1c] **Long Params**: >4 parameters use parameter object or builder; never add 5th positional argument.
- [CS1d] **Feature Envy**: If method uses another object's data more than its own, move it there.
- [CS1e] **Switch on Type**: Replace with polymorphism when branches >3 or recur.
- [CS1f] **Temporal Coupling**: Enforce call order via state machine, builder, or combined API.
- [CS1g] **Magic Literals**: No inline numbers (except 0, 1, -1) or strings; define named constants.
- [CS1h] **Comment Deodorant**: If comment explains what, refactor until self-documenting; comments explain why only.

## [VR1] Verification Loops

- [VR1a] **Build**: `make build` or `./gradlew build`; expect success.
- [VR1b] **Tests**: `make test` or `./gradlew test`; targeted runs use `--tests ClassName`.
- [VR1c] **Runtime**: `make run &`, hit `/actuator/health` and changed endpoints; then stop.

## [JD1] Javadoc Standards

- [JD1a] **Mandatory**: Javadocs mandatory on public/protected classes, methods, and enums.
- [JD1b] **Why > What**: Focus on "why" (purpose, rationale, constraints) over "what".
- [JD1c] **Summary**: First sentence is the summary: complete sentence, present tense, third person.
- [JD1d] **Tags**: Use `@param` only when name isn't self-documenting; use `@return` only for non-obvious values.
- [JD1e] **Throws**: Include `@throws` for checked exceptions and runtime exceptions callers should handle.
- [JD1f] **Evidence**: Cite sources inline (e.g., "Per RFC 7231").
- [JD1g] **Deprecation**: `@Deprecated` annotation AND `@deprecated` tag with migration path.
- [JD1h] **No Filler**: Ban "This method...", "Gets the...". Start with verb or noun directly.

## [DS1] Dependency Source Verification

- [DS1a] **Locate**: Find source JARs in Gradle cache: `find ~/.gradle/caches/modules-2/files-2.1 -name "*-sources.jar" | grep <artifact>`.
- [DS1b] **List**: View JAR contents without extraction: `unzip -l <jar_path> | grep <ClassName>`.
- [DS1c] **Read**: Pipe specific file content to stdout: `unzip -p <jar_path> <internal/path/to/Class.java>`.
- [DS1d] **Search**: To use `ast-grep` on dependencies, pipe content directly: `unzip -p <jar> <file> | ast-grep run --pattern '...' --lang java --stdin`. No temp files required.
- [DS1e] **Efficiency**: Do not extract full JARs. Use CLI piping for instant access.
- [DS1f] **Ensure Sources**: If sources are missing, assume standard dev environment has them or instruct user to run `./gradlew downloadSources` (if alias exists).

## [TL1] Tooling & Environment

- [TL1a] **Commands**: `make run`, `make dev`, `make test`, `make build`, `make compose-up`, `make compose-down`.
- [TL1b] **Docker**: `docker compose up -d` for Qdrant vector store.
- [TL1c] **Ingest**: `curl -X POST http://localhost:8080/api/ingest ...`.
- [TL1d] **Stream**: `curl -N http://localhost:8080/api/chat/stream ...`.
- [TL1e] **Secrets**: `.env` for secrets (`GITHUB_TOKEN`, `QDRANT_URL`); never commit secrets.

## [LM1] LLM & Streaming

- [LM1a] **Settings**: Do not change any LLM settings without explicit written approval.
- [LM1b] **No Fallback**: Do not auto-fallback or regress models across providers; surface error to user.
- [LM1c] **Config**: Use values from environment variables and `application.properties` exactly as configured.
- [LM1d] **Behavior**: Allowed: logging diagnostics. Not allowed: silently changing LLM behavior.
- [LM1e] **Streaming**: TTFB < 200ms, streaming start < 500ms.
- [LM1f] **Events**: `text`, `citation`, `code`, `enrichment`, `suggestion`, `status`.
- [LM1g] **Errors**: `onErrorContinue` for partial failures; never drop entire response on single failure.
- [LM1h] **Heartbeats**: Maintain connection with periodic events during long operations.

## [MD1] Markdown & Parsing

- [MD1a] **No Regex**: No regex for HTML/Markdown processing; use proper parsers (Flexmark, DOM APIs).
- [MD1b] **Structured Data**: Parse to objects, transform, serialize.
- [MD1c] **Idiomatic**: Use Java Streams, Optional, proper HTML APIs.
- [MD1d] **Separation**: Backend handles structure, frontend handles presentation.
- [MD1e] **Fail-safe**: Graceful degradation when parsing fails; never crash on malformed input.

## [ND1] Naming Discipline

- [ND1a] **Intent-Revealing**: Every identifier (variable, parameter, method, class, constant) must be domain-specific and intent-revealing; a reader must understand purpose without context from surrounding code.
- [ND1b] **Banned Single-Letter Variables**: `a`, `b`, `c`, `d`, `n`, `o`, `p`, `s`, `t`, `v`, `x`, `y`, `z` are prohibited as variable/parameter names. Exceptions: `i`, `j`, `k` in trivial indexed `for` loops; single-char lambda params only when the type is unambiguous in a one-expression pipeline (e.g., `list.stream().map(e -> e.name())`); `e` in `catch` blocks.
- [ND1c] **Banned Generic Nouns**: These names (and close variants) are prohibited as variable, parameter, or field names: `data`, `info`, `value`, `val`, `item`, `items`, `obj`, `object`, `result`, `results`, `response`, `payload`, `content`, `stuff`, `thing`, `entry`, `element`, `record`, `temp`, `tmp`, `misc`, `foo`, `bar`, `baz`, `dummy`, `sample`. Use domain-specific names that describe what the variable holds (e.g., `embeddingVector` not `data`; `chatMessage` not `item`; `rankedDocuments` not `results`).
- [ND1d] **Banned Abbreviations**: No abbreviated variable names: `val`, `res`, `req`, `resp`, `msg`, `str`, `num`, `cnt`, `idx`, `len`, `buf`, `ctx`, `cfg`, `mgr`, `impl`, `proc`, `ref`, `desc`, `doc`, `col`, `cb`. Use full words: `message`, `request`, `response`, `count`, `index`, `length`, `buffer`, `context`, `configuration`, `manager`, `description`, `document`, `column`, `callback`.
- [ND1e] **Constants**: `UPPER_SNAKE_CASE` with domain-qualifying prefix; no bare generic constant names like `VALUE`, `DATA`, `DEFAULT`, `THRESHOLD`, `LIMIT`, `SIZE`, `TIMEOUT`, `COUNT`, `MAX`, `MIN` without a qualifying domain noun (e.g., `EMBEDDING_DIMENSION` not `SIZE`; `RERANKER_TIMEOUT_MS` not `TIMEOUT`; `MAX_RETRIEVAL_RESULTS` not `MAX`).
- [ND1f] **Type Names**: No generic suffixes `*Data`, `*Info`, `*Object`, `*Item`, `*Wrapper`, `*Holder`, `*Container`, `*Manager` (unless the class genuinely manages a lifecycle); class and record names declare their domain role.
- [ND1g] **Alias Consistency**: The same concept uses the same name across method signatures, variable assignments, log messages, and documentation; do not alias the same thing with different names in the same scope or call chain.
- [ND1h] **Legacy Fix-on-Touch**: When editing code that uses banned or generic names, rename them in the same edit; never introduce new generic names into existing code.
