# Gates: Complete local embedding staging

Scope: Generate and persist every assigned documentation and pinned-repository embedding in local Qdrant using the existing remote embedding endpoint, with zero Qdrant Cloud writes.

- [x] G1: Local Qdrant is the only vector-store target and has the required 2,560-dimensional dense plus BM25 sparse schema.
  EVIDENCE: Qdrant 1.18.3 is active at 127.0.0.1:8086/8087; all four green collections expose dense size 2560 cosine plus BM25 sparse IDF. The launcher forces the loopback endpoint after secret injection and exports an empty QDRANT_API_KEY; the deterministic launcher contract test passes.

- [x] G2: The existing remote embedding endpoint accepts the selected production batch shape with model `qwen/qwen3-embedding-4b` and returns exactly 2,560 dimensions.
  EVIDENCE: The production preflight validates paced batches of 1 and 8 with ordered numeric 2,560-dimensional vectors; an independent capacity probe also completed eight simultaneous requests of 32 inputs each in 15,777 ms.

- [x] G3: All 24 selected documentation sets totaling 24,798 files finish local ingestion with exact checkpoint accounting and zero unresolved failures.
  EVIDENCE: `process_qdrant.log` records DOCUMENT PROCESSING COMPLETE at 2026-08-24 09:32:34 PDT, all 24 exact docSet postconditions passed, the summary is 9,083 processed plus 15,715 skipped equals 24,798, no checkpoint remains outside lifecycle COMPLETE, and the local docs collection is green with 51,850 points.

- [ ] G4: All 22 clean pinned repositories finish local ingestion with zero failures and nonzero local collection counts.
  EVIDENCE: pending

- [ ] G5: Qdrant Cloud topology/configuration/exact counts remain unchanged across the local-only run.
  EVIDENCE: Current safe credential-injected snapshot equals the preserved baseline: docs 206047, books 374, articles 12, PDFs 1517. Repeat at terminal completion.

- [ ] G6: Final local Qdrant point counts, allocated disk, marker counts, and process census are measured from live state.
  EVIDENCE: pending

- [x] G7: Retry and launcher fixes are pushed and their exact replacement SHA reaches terminal-green CI.
  EVIDENCE: Retry, interruption, non-transient, shell-contract, lint, PMD, SpotBugs, frontend, formatting, failure-log archival, and Redoc-shell regression lanes pass locally. Exact SHA 450eaa42 passed Build & Test run 32695617632 including frontend, build/static analysis, and Docker/Qdrant runtime smoke; predecessor 3eb28bff passed run 32695261604.

- [ ] G8: Final Git state contains no uncommitted task code and retains only this untracked run ledger.
  EVIDENCE: pending
