# Implementation checkpoints

Each task branch starts from the completed predecessor. Prior branches remain fallback snapshots.

1. `task/01-core`: Maven modules, domain contracts, deterministic ingestion, four retrieval methods, grounding, trace and library operations.
2. `task/02-adapters`: Neo4j persistence, GDS Leiden, LangChain4j live models, PDFBox parsing, contract and integration tests.
3. `task/03-web-api`: asynchronous ingestion, reconnectable progress, corpus history, validated APIs, restart recovery.
4. `task/04-workspace`: graph exploration, chat, source inspection, replay, comparison, contextual help, browser tests.
5. `task/05-delivery`: Docker Compose, configuration, documentation, complete validation and limitations.

Step 1 complete: Java 21 core, typed ports, deterministic extraction, normalization, ingestion, four retrieval algorithms, citation validation, recorded traces and graph/source library operations. `mvn -q -pl core test` passed (3 tests). Full retrieval/adapter workflow tests follow in step 2.

Step 2 complete: Neo4j transactional corpus-scoped persistence and embedding properties; pinned GDS Leiden adapter; explicit atomic file fallback; LangChain4j JSON extraction and embedding/answer integration; PDFBox text extraction. Core and adapter suite passed, including all four retrieval modes, provenance, source removal and file restart persistence. Real Neo4j/GDS integration is queued while its image downloads; live APIs are not invoked without user credentials.

Step 3 complete: all specified REST routes, bounded asynchronous ingestion, reconnectable SSE with heartbeat and terminal closure, persistent activation/history, startup interruption recovery, structured errors and upload validation. API tests passed for upload, four modes, source resolution, trace, comparisons, failures and offline showcase with deliberately unusable live credentials configured.
