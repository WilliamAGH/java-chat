# BM42 Upgrade Plan — Minimal‑Change Hybrid (Preferred) + Full Sparse Option (Alt)

This plan updates our Java‑docs/books retrieval to use BM42 alongside dense vectors in Qdrant with the lightest, surgical code changes. It prioritizes a no‑reindex, low‑risk path using Qdrant’s Query API for BM42‑backed text search, while documenting a deeper alternative that requires re‑ingestion.

---

## Executive Summary
- Preferred path: keep existing dense vector ingestion; add a BM42 text query via Qdrant Query API on `payload.content`; fuse with dense results using RRF client‑side. No re‑ingestion.
- Alternative (deeper): ingest explicit sparse vectors per point and use server‑side hybrid. Requires re‑ingestion and a sparse model runtime.
- Rollout: feature‑flagged, idempotent index creation, parallel queries, BM25 fallback if BM42 not available.

---

## Current System Snapshot (repo‑aware)
- Dense vectors stored in Qdrant via Spring AI `VectorStore` (`DocsIngestionService`, `vectorStore.add`).
- Metadata per chunk via `DocumentFactory`: `url`, `title`, `chunkIndex`, `package`, `hash`.
- Retrieval pipeline: `RetrievalService` performs vector search; `RerankerService` re‑ranks; `LocalSearchService` keywords fallback.
- Qdrant payload indexes created in `QdrantIndexInitializer` for `url`, `hash`, `chunkIndex`.

---

## Option A — Minimal‑Change Hybrid via Query API (Preferred)

Goal: Use Qdrant’s text query (BM42 when supported) over `payload.content`, fuse with dense vector results in app. Avoid re‑ingestion and preserve existing collection.

### Qdrant prerequisites
- Qdrant version with Query API and `text` operator; BM42 model flag supported in recent releases.
- Create a `full_text` payload index on `content` for performance.

### Surgical code changes
1) `QdrantIndexInitializer`
   - Add idempotent payload index: `field_name = "content"`, `field_schema = "full_text"`.
   - Keep existing index creation pattern (try POST, then PUT, multiple base URLs).

2) New `QdrantQueryService`
   - Responsibility: call `POST /collections/{collection}/points/query` using `RestTemplate`.
   - Public API:
     - `List<Result> queryTextBm42(String text, int limit, @Nullable Map<String,Object> filter)`
       - Sends body:
         ```json
         { "query": { "text": { "text": "<user_query>", "field": "content", "model": "bm42" }},
           "with_payload": true, "limit": 50 }
         ```
       - If BM42 unsupported (400/422), retry without `model` to get BM25 behavior.
     - `record Result(String id, double score, Map<String,Object> payload)`

3) New `HybridRetrievalService`
   - Parallelize (WebFlux) two branches:
     - Dense: `vectorStore.similaritySearch(SearchRequest.builder().query(q).topK(topKVector).build())`.
     - Sparse: `qdrantQueryService.queryTextBm42(q, topKSparse)`.
   - Normalize to Spring AI `Document` using `payload.content` as text; preserve `url`, `title`, `hash` from payload.
   - Fuse via Reciprocal Rank Fusion (RRF): `rrf = 1/(k + rank_dense) + w * 1/(k + rank_sparse)`; tune `k` and `w`.
   - Deduplicate by `hash` (primary) or `url` (secondary). Optionally pass fused set to `RerankerService` and select `searchReturnK`.

4) `RetrievalService`
   - Add feature‑flagged branch: if `app.rag.hybrid.mode` is `bm25` or `bm42`, delegate to `HybridRetrievalService`; else keep current vector‑only path.

5) `application.properties`
   - Add flags:
     - `app.rag.hybrid.mode=${RAG_HYBRID_MODE:off}`              # off | bm25 | bm42
     - `app.rag.hybrid.topK.vector=${RAG_HYBRID_TOPK_VECTOR:24}`
     - `app.rag.hybrid.topK.sparse=${RAG_HYBRID_TOPK_SPARSE:50}`
     - `app.rag.hybrid.rrf.k=${RAG_HYBRID_RRF_K:60}`
     - `app.rag.hybrid.weight.sparse=${RAG_HYBRID_WEIGHT_SPARSE:1.0}`
     - `app.rag.hybrid.timeout.ms=${RAG_HYBRID_TIMEOUT_MS:600}`
     - `app.qdrant.text.field=${QDRANT_TEXT_FIELD:content}`

### Validation steps
- Pre‑check: `scroll` a few points to ensure `payload.content` exists.
- API spot check (BM42):
  ```json
  POST /collections/{collection}/points/query
  { "query": { "text": { "text": "java records", "field": "content", "model": "bm42" }},
    "with_payload": true, "limit": 10 }
  ```
- Fallback: same request without `model`.
- In‑app: compare hybrid vs vector‑only on 30–50 queries (APIs, book topics). Track citation accuracy and latency.

### Risks & mitigations
- BM42 flag not supported → auto BM25 fallback; keep `mode=bm25` until server upgrade.
- Score scales differ → RRF avoids absolute score normalization; tune `k` and `w`.
- Latency overhead → parallelize branches; cap sparse timeout; typical +40–120ms.
- `payload.content` missing → add a minimal writer to ensure text lands in `content` on upsert (only if needed).

---

## Option B — Explicit Sparse Vectors Per Point (Alternative)

Goal: Store a named sparse vector (BM42) alongside dense vectors; use Qdrant’s server‑side hybrid in one call.

### Changes
- Extend collection schema with a named sparse vector; re‑ingest all documents computing sparse vectors (fastembed/SPLADE/BM42 encoder runtime).
- Upsert both dense + sparse per point during ingestion; use Query API hybrid/ensemble with server‑side RRF.

### Tradeoffs
- Pros: maximal control, single server‑side hybrid call, potentially best quality/perf.
- Cons: full re‑ingestion, new model infra, higher complexity and cost.

---

## Rollout & Timeline (Preferred path)
1) Day 0: Pre‑flight checks; add `full_text` index; ship feature‑flags (mode=off).
2) Day 1: Enable `bm25` hybrid; validate relevance/latency; tune `k`/`w`.
3) Day 2: Enable `bm42` on staging; verify improvement over `bm25`.
4) Day 3: Roll to prod; monitor metrics; iterate.
5) Optional: Evaluate Option B if quality demands justify re‑ingestion.

---

## References
- Qdrant BM42: https://qdrant.tech/articles/bm42/
- Qdrant Hybrid Queries & Query API: https://qdrant.tech/documentation/concepts/hybrid-queries/ and https://qdrant.tech/documentation/concepts/search/
- LlamaIndex example (shape inspiration): https://docs.llamaindex.ai/en/stable/examples/vector_stores/qdrant_bm42/

