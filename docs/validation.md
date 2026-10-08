# Validation and limitations

## Checks executed

- Clean multi-module Maven verification: 19 Java tests, zero failures/errors/skips when the test database was enabled.
- Actual Neo4j 5.26.12 + GDS 2.13.2: corpus isolation, idempotent writes, persisted activation, vector storage, weighted Leiden and reopen checks.
- PDFBox: searchable PDF extraction with page provenance, malformed input/type/UTF-8 checks, and retained PDF source rebuild behavior.
- Core: Unicode normalization, full case folding (including sharp-s, sigma and dotless-i distinctions), chunk overlap, duplicate resolution, provenance, all four distinct retrieval algorithms, citation rejection/renumbering, no-answer and missing-community behavior, source replacement/removal identities.
- LangChain4j: actual adapter requests against a local mock OpenAI-compatible provider for extraction, relationships, summaries, follow-ups, answers and embeddings. No paid model calls were made.
- API: upload, failed parsing, corpus readiness, source lookup, traces, comparison, active history and startup interruption recovery. Explicit offline showcase passes with a fake configured live credential and an unreachable live endpoint, demonstrating zero live API calls.
- Playwright: four workflow tests for keyboard graph inspection, citations, replay play/pause/navigation/reopen, DRIFT branches, four-way comparison, evidence marking, vector replay, JSON download, safe uploaded-text rendering, corpus switching/reload and responsive layouts at 1440/1024/600 pixels. Screenshots are in `docs/screenshots/`.

The Java 25 Docker + Neo4j/GDS Compose stack built and started successfully. The final browser suite passed against that deployment. Stopping and restarting both app and database preserved corpora, graph identities, active selection and vectors; old traces correctly returned 404. The repeatable `tests/integration/persistence.py` check also verified all four methods, source resolution and SSE reconnection. Full commands/results are recorded in `docs/progress.md`.

## Deliberate limits

- Offline processing is a deterministic teaching baseline. Proper-name detection and sentence co-mentions do not perform sophisticated entity linking or relation inference. Its hash vectors are term features, not learned semantic embeddings. Offline answers quote matching source sentences and can return no-answer for paraphrases they cannot match.
- Live model quality and real provider compatibility require your configured credentials and were not validated against a paid endpoint. JSON-object output is parsed into typed contracts; incompatible or malformed output fails the stage visibly.
- Citation validation checks retrieved source identity and numbering. It cannot prove every generated claim follows from the evidence; inspect sources. Expected-evidence diagnostics are observations, not correctness judgements.
- PDF extraction requires searchable, unlocked text. There is no OCR. Page markers are preserved in text; a passage can span more than one PDF page. Raw upload bytes are not retained after extraction.
- This is a single-user local application. There is no authentication, authorization, multi-tenant account model or multi-instance job coordinator. Compose binds host ports to loopback. Do not expose it publicly without adding those capabilities.
- Storage and retrieval favor clarity over large-corpus throughput: Neo4j stores snapshots plus materialized data, ingestion rewrites one corpus transactionally at each checkpoint, and exact cosine search ranks stored vectors in-process. There is no ANN vector index or distributed processing.
- Source deletion/replacement recomputes all remaining derived data. It preserves retained source identities and provenance but does not minimize model calls. In-process community detection uses weighted label propagation, not Leiden.
- Traces and SSE histories are in memory; restarts clear them. Up to 1,000 recent traces per model engine and 1,000 progress events per corpus are retained. Interrupted ingestion is marked FAILED, not resumed. Retrying creates a new corpus.
- Configure models and chunk settings consistently before ingestion. Changing embedding models or chunk sizes requires rebuilding affected corpora. Live corpora need their model credentials restored after restart.
- Browser tests require a separately running app and installed Chromium. First-time container/browser/dependency downloads require network access. The GDS artifact is version pinned, retried and SHA-256 verified.
