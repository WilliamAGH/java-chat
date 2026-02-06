# Retrieval pipeline

End-to-end reference for the 5-stage RAG retrieval pipeline that turns a user query into LLM-ready context.

For ingestion (how documents enter Qdrant), see [ingestion.md](ingestion.md).
For operational commands, see [pipeline-commands.md](pipeline-commands.md).
For environment variable reference, see [configuration.md](configuration.md).

## Pipeline overview

```
ChatController.stream()
  -> ChatService.buildStructuredPromptWithContextOutcome()
      -> RetrievalService.retrieveOutcome()
          |-- 1. QueryVersionExtractor   -- version detection + query boosting
          |-- 2. HybridSearchService     -- parallel dense+sparse search with RRF fusion
          |-- 3. dedupeByHashThenUrl()   -- UUID -> SHA-256 hash -> URL dedup
          +-- 4. RerankerService         -- LLM reranking with Caffeine caching
      -> SearchQualityLevel              -- quality assessment for LLM calibration
      -> 5. PromptTruncator              -- priority-based truncation
  -> OpenAIStreamingService.streamResponse()
```

| Stage | File | Entry point |
|---|---|---|
| Version extraction | `util/QueryVersionExtractor.java` | `boostQueryWithVersionContext()` |
| Hybrid search | `service/HybridSearchService.java` | `searchOutcome()` |
| Deduplication | `service/RetrievalService.java` | `dedupeByHashThenUrl()` |
| LLM reranking | `service/RerankerService.java` | `rerank()` |
| Prompt assembly | `application/prompt/PromptTruncator.java` | `truncate()` |

All paths are relative to `src/main/java/com/williamcallahan/javachat/`.

---

## 1. Query enhancement

**No LLM calls** -- all enhancement is deterministic regex and string manipulation.

### Version detection

`QueryVersionExtractor` (`util/QueryVersionExtractor.java`) detects Java version mentions in queries using the regex pattern `\b(?:java\s*se|javase|java|jdk)[\s-]*(\d{1,2})\b` (case-insensitive).

Matches: `Java 25`, `JDK 24`, `java25`, `jdk-25`, `Java SE 24`, `JavaSE 25`.

### Query boosting

`boostQueryWithVersionContext()` prepends synonym expansions to the raw query:

```
JDK 25 Java SE 25 Java 25 release features documentation: <original query>
```

This improves dense-embedding recall by injecting version-related terms the embedding model can anchor on.

### Metadata filter generation

`extractFilterPatterns()` returns URL and text tokens used for both server-side Qdrant filtering and client-side post-filtering:

- **URL tokens**: `java25`, `jdk25`, `java-25`, `jdk-25`, `/javase/25`, `/java/javase/25`, `/java/se/25`
- **Text tokens**: `java se 25`, `jdk 25`

If a version is detected, `RetrievalService` builds a `RetrievalConstraint.forDocVersion(versionNumber)` that pushes a `docVersion` keyword filter to Qdrant server-side via `QdrantRetrievalConstraintBuilder`. When the constraint is server-side, client-side filtering is skipped; otherwise `VersionFilterPatterns.matchesMetadata(url, title)` applies as a fallback.

**Orchestration**: `RetrievalService.retrieveOutcome()` lines 105-107.

---

## 2. Hybrid search

`HybridSearchService` (`service/HybridSearchService.java`) performs parallel hybrid search across 4 Qdrant collections using the direct gRPC client (`io.qdrant:client`), not Spring AI VectorStore abstractions.

### Dense + sparse encoding

Each query is encoded into two vectors:

| Vector | Named vector | Model | Dimensions | Source |
|---|---|---|---|---|
| Dense | `dense` | Qwen3-embedding-8B | 4096 | `EmbeddingClient.embed()` |
| Sparse | `bm25` | Murmur3 feature-hashed TF | variable | `LexicalSparseVectorEncoder.encode()` |

### Sparse vector encoding

`LexicalSparseVectorEncoder` (`service/LexicalSparseVectorEncoder.java`) builds BM25-style sparse vectors:

1. Normalize text via `AsciiTextNormalizer.toLowerAscii()` + `toLowerCase(Locale.ROOT)`
2. Tokenize with Lucene `StandardAnalyzer`; discard tokens shorter than 2 characters
3. Hash each token with Murmur3-32 (seed=0), sign-extend to unsigned long
4. Count term frequencies in `Map<Long, Integer>`
5. Cap at 256 unique tokens (sorted by count descending, tie-break by index)
6. Return `SparseVector(indices, values)` sorted by index ascending

IDF weighting is applied server-side by Qdrant's `modifier=idf` on the sparse vector config (set during collection creation by `QdrantIndexInitializer`). The encoder only sends raw term counts.

### Parallel fan-out

For each of the 4 collections, a `QueryPoints` request is built with two prefetch stages fused by RRF:

```
QueryPoints {
  prefetch: [
    { nearest: denseVector,  using: "dense", limit: prefetchLimit, filter: ... },
    { nearest: sparseVector, using: "bm25",  limit: prefetchLimit, filter: ... }
  ],
  query: rrf(k=rrfK),
  limit: topK
}
```

All 4 requests fire asynchronously via `qdrantClient.queryAsync()` (returns `ListenableFuture`, converted to `CompletableFuture`). Each future is awaited with the configured timeout (default 5s).

The sparse prefetch is skipped if the sparse vector has no indices (empty query after tokenization).

**RRF formula** (server-side Qdrant): `score = Sum(1 / (k + rank_i))` where `k` defaults to 60.

### UUID dedup during merge

As collection results merge, `mergePoints()` deduplicates by Qdrant point UUID in a `LinkedHashMap<String, ScoredResult>`. If the same UUID appears in multiple collections, the higher score wins.

### Strict mode

When `app.qdrant.fail-on-partial-search-error=true` (default), any collection query failure throws `HybridSearchPartialFailureException`. When false, partial results are returned with notices.

### Collections

| Collection | Default name | Property |
|---|---|---|
| Books | `java-chat-books` | `app.qdrant.collections.books` |
| Docs | `java-docs` | `app.qdrant.collections.docs` |
| Articles | `java-articles` | `app.qdrant.collections.articles` |
| PDFs | `java-pdfs` | `app.qdrant.collections.pdfs` |

### Metadata extraction

Each Qdrant point is converted to a Spring AI `Document` with typed metadata fields: `url`, `title`, `package`, `hash`, `docSet`, `docPath`, `sourceName`, `sourceKind`, `docVersion`, `docType`, `chunkIndex`, `pageStart`, `pageEnd`, `score`, `collection`.

---

## 3. Deduplication

Three-layer dedup, applied in sequence:

| Layer | Location | Key | Winner |
|---|---|---|---|
| UUID | `HybridSearchService.mergePoints()` | Qdrant point UUID | Highest score |
| Content hash | `RetrievalService.dedupeByHashThenUrl()` | `hash` metadata (SHA-256) | First seen |
| URL | `RetrievalService.dedupeByHashThenUrl()` | `url` metadata | First seen |

Both hash and URL dedup use `LinkedHashMap.putIfAbsent` to preserve reranker ordering. Documents with neither hash nor URL are kept unconditionally (with a warning log).

**File**: `RetrievalService.java` lines 182-213.

---

## 4. LLM reranking

`RerankerService` (`service/RerankerService.java`) reorders search results by relevance using an LLM call.

### Prompt criteria

The reranking prompt instructs the LLM to consider:

- **Java-specific context** and domain relevance
- **Version relevance** to the query
- **Source authority** -- official docs preferred over blogs/third-party
- **Stable vs. early-access** -- stable release docs preferred over preview content
- **Learning value** for the user

Each document is presented as `[index] title | url` followed by the first 500 characters of content.

### LLM call

- Model: same provider as chat (OpenAI/GitHub Models)
- Temperature: `0.0` (deterministic)
- Timeout: configurable via `app.rag.reranker-timeout` (default 12s)
- Response format: `{"order": [0, 3, 1, 2, ...]}` (0-based indices)

### Response parsing

1. Prefer fenced code blocks, then find first `{...}` via brace-depth tracking
2. Deserialize to `RerankOrderResponse(List<Integer> order)`
3. Map indices back to original documents; skip null/out-of-range indices
4. Limit output to `searchReturnK` (default 6)

### Caching

Results are cached in a Caffeine cache (`reranker-cache`) keyed by:

```
query + ":" + docsHash + ":" + returnK
```

where `docsHash` is `Integer.toHexString()` of concatenated document URLs (or text hashCodes).

### No fallback

On any failure (timeout, parse error, LLM unavailable), `RerankerService` throws `RerankingFailureException`. There is no fallback to original ordering.

---

## 5. Structured prompt assembly

After retrieval completes, the prompt is assembled and truncated to fit the model's token budget.

### Prompt structure

`StructuredPrompt` (`domain/prompt/StructuredPrompt.java`) renders segments in this order:

1. System prompt (with appended SEARCH CONTEXT quality note)
2. Context documents, each prefixed with `[CTX N] <normalized URL>`
3. Conversation history turns (role-prefixed)
4. Current user query

### Search quality signaling

`ChatService.buildStructuredPromptWithContextOutcome()` (line 186-195) appends a quality note to the system prompt based on `SearchQualityLevel.describeQuality()`:

| Level | Trigger | Message injected into system prompt |
|---|---|---|
| `NONE` | No documents retrieved | "No relevant documents found. Using general knowledge only." |
| `KEYWORD_SEARCH` | Any document URL contains `local-search` or `keyword` | "Found N documents via keyword search (embedding service unavailable). Results may be less semantically relevant." |
| `HIGH_QUALITY` | All documents have text longer than 100 characters | "Found N high-quality relevant documents via semantic search." |
| `MIXED_QUALITY` | Some documents below 100 character threshold | "Found N documents (M high-quality) via search. Some results may be less relevant." |

When the quality message contains "less relevant" or "keyword search", an additional low-quality search prompt is appended from `SystemPromptConfig.getLowQualitySearchPrompt()`. This calibrates LLM confidence -- the model is told explicitly when its context may be unreliable.

### Priority-based truncation

`PromptTruncator` (`application/prompt/PromptTruncator.java`) fits the assembled prompt within a model-specific token budget:

| Priority | Segment type | Truncation behavior |
|---|---|---|
| CRITICAL | System prompt | Never truncated |
| HIGH | Current user query | Never truncated |
| MEDIUM | Conversation history | Oldest turns removed first |
| LOW | Context documents | Least relevant removed first |

**Algorithm** (lines 49-101):

1. Reserve tokens for system prompt + current query (non-negotiable)
2. If those alone exceed the budget, return minimal prompt (system + query only)
3. Fit conversation history newest-first into remaining budget
4. Fit context documents in reranker order (most relevant first) into remaining budget
5. Re-index surviving documents with sequential `[CTX N]` markers

### Token budgets

Token budgets are determined by `OpenAIStreamingService` based on the active model:

| Model family | Token budget | Constant |
|---|---|---|
| GPT-5.x | 7,000 | `MAX_TOKENS_GPT5_INPUT` (`OpenAIStreamingService.java:54`) |
| All others | 100,000 | `MAX_TOKENS_DEFAULT_INPUT` (`OpenAIStreamingService.java:57`) |

Token estimation uses a conservative `(text.length() / 4) + 1` approximation (~4 characters per token for English text).

For token-constrained models (GPT-5.x), RAG retrieval is also reduced upstream: max 3 documents (`RAG_LIMIT_CONSTRAINED`) with max 600 tokens each (`RAG_TOKEN_LIMIT_CONSTRAINED`), defined in `ModelConfiguration.java`.

---

## Configuration reference

All properties are bound via `AppProperties` (`@ConfigurationProperties(prefix = "app")`). See [configuration.md](configuration.md) for environment variable names.

### Hybrid search (`app.qdrant.*`)

| Property | Default | Description |
|---|---|---|
| `app.qdrant.dense-vector-name` | `dense` | Named vector key for dense embeddings |
| `app.qdrant.sparse-vector-name` | `bm25` | Named vector key for sparse BM25 tokens |
| `app.qdrant.prefetch-limit` | `20` | Per-stage candidate count for each dense/sparse prefetch before RRF fusion |
| `app.qdrant.rrf-k` | `60` | RRF k parameter: `score = Sum(1 / (k + rank))` |
| `app.qdrant.query-timeout` | `5s` | Timeout for hybrid search fan-out across all collections |
| `app.qdrant.fail-on-partial-search-error` | `true` | Fail retrieval if any collection query fails |
| `app.qdrant.ensure-payload-indexes` | `true` | Create payload indexes on startup for metadata filtering |
| `app.qdrant.ensure-collections` | `true` | Auto-create hybrid collections on startup if missing |

### Collections (`app.qdrant.collections.*`)

| Property | Default |
|---|---|
| `app.qdrant.collections.books` | `java-chat-books` |
| `app.qdrant.collections.docs` | `java-docs` |
| `app.qdrant.collections.articles` | `java-articles` |
| `app.qdrant.collections.pdfs` | `java-pdfs` |

All four must be non-blank and distinct (validated on startup).

### RAG tuning (`app.rag.*`)

| Property | Default | Validation | Description |
|---|---|---|---|
| `app.rag.search-top-k` | `12` | Must be > 0 | Candidates fetched from hybrid search before reranking |
| `app.rag.search-return-k` | `6` | Must be > 0, must be <= `search-top-k` | Results returned to the LLM after reranking |
| `app.rag.reranker-timeout` | `12s` | Must be positive | Timeout for LLM reranking call |
| `app.rag.search-citations` | `3` | Must be >= 0 | Citation references included in the response |
| `app.rag.search-mmr-lambda` | `0.5` | Must be in [0.0, 1.0] | MMR lambda (higher = relevance, lower = diversity) |
| `app.rag.chunk-max-tokens` | `900` | Must be > 0 | Max tokens per ingested chunk (see [ingestion.md](ingestion.md)) |
| `app.rag.chunk-overlap-tokens` | `150` | Must be >= 0, must be < `chunk-max-tokens` | Overlap between consecutive chunks |

### Embeddings (`app.embeddings.*`)

| Property | Default | Description |
|---|---|---|
| `app.embeddings.dimensions` | `4096` (in `application.properties`; code default `1536`) | Must match the active embedding model output size |

For embedding provider selection (local vs. remote vs. OpenAI), see [configuration.md#embeddings](configuration.md#embeddings).