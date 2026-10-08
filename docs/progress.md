# Implementation checkpoints

Status: all five implementation stages complete. The final application is on `main` and `task/05-delivery`.

Each task branch starts from the completed predecessor. Prior branches remain fallback snapshots.

1. `task/01-core`: Maven modules, domain contracts, deterministic ingestion, four retrieval methods, grounding, trace and library operations.
2. `task/02-adapters`: Neo4j persistence, GDS Leiden, LangChain4j live models, PDFBox parsing, contract and integration tests.
3. `task/03-web-api`: asynchronous ingestion, reconnectable progress, corpus history, validated APIs, restart recovery.
4. `task/04-workspace`: graph exploration, chat, source inspection, replay, comparison, contextual help, browser tests.
5. `task/05-delivery`: Docker Compose, configuration, documentation, complete validation and limitations.

Step 1 complete: Java 21 core, typed ports, deterministic extraction, normalization, ingestion, four retrieval algorithms, citation validation, recorded traces and graph/source library operations. `mvn -q -pl core test` passed (3 tests). Full retrieval/adapter workflow tests follow in step 2.

Step 2 complete: Neo4j transactional corpus-scoped persistence and embedding properties; pinned GDS Leiden adapter; explicit atomic file fallback; LangChain4j JSON extraction and embedding/answer integration; PDFBox text extraction. Core and adapter suite passed, including all four retrieval modes, provenance, source removal and file restart persistence. Real Neo4j/GDS integration is queued while its image downloads; live APIs are not invoked without user credentials.

Step 3 complete: all specified REST routes, bounded asynchronous ingestion, reconnectable SSE with heartbeat and terminal closure, persistent activation/history, startup interruption recovery, structured errors and upload validation. API tests passed for upload, four modes, source resolution, trace, comparisons, failures and offline showcase with deliberately unusable live credentials configured.

Step 4 complete: full-height light workspace, locally bundled Cytoscape.js, graph/relationship/community inspection, keyboard entity suggestions, safely rendered chat/citations, replay and DRIFT branches, pairwise/four-way comparison, evidence matrix, expected-evidence diagnostics, help and responsive panels. Four Playwright workflows passed at desktop and small widths; screenshots are in docs/screenshots. Browser tests identified and resolved asynchronous corpus activation/terminal-progress races. Additional grounding and mock-provider tests passed. Neo4j/GDS integration passed against the pinned container, including weighted Leiden and database reopen.


Step 5 complete: Java 25 Docker application, Neo4j 5.26.12/GDS 2.13.2 Compose stack with persistent storage, example configuration, startup/test commands, architecture/API documentation and explicit limitations. GDS downloads use retries and a verified SHA-256; Maven builds cache dependencies. Sources were formatted for review.

Final validation (2026-10-08):

- `NEO4J_TEST_URI=bolt://localhost:17687 NEO4J_TEST_PASSWORD=explorer-test-password mvn -q verify`: 19 Java tests passed, zero failures/errors/skips, including actual database/GDS tests.
- `APP_URL=http://localhost:18080 npm run test:browser`: four browser workflows passed against the actual Java 25 Docker + Neo4j deployment. Browser screenshots reviewed; community positions and active replay contrast corrected. The initially stalled Chromium installer was recovered using its official downloaded artifact.
- Compose build/start passed on isolated validation ports: application 18080, Neo4j HTTP 17474, Bolt 17688. A local run was also verified with the explicit file profile.
- `python3 tests/integration/persistence.py before --url http://localhost:18080`: all four modes, citations resolving to recorded evidence, three GDS communities and Last-Event-ID reconnection passed.
- Stopped and restarted both app and database without removing storage; the matching `after` check passed for corpus/graph/vector persistence, active selection and trace expiration.
- JavaScript syntax and Git whitespace checks passed. Temporary file-profile server and isolated test database were cleaned up; the validated Compose stack remains available at http://localhost:18080.

Paid live providers were not called. Live integration was validated with a mock OpenAI-compatible HTTP service. See docs/validation.md for the remaining operating and quality limitations.
