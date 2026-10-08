# HTTP API

Responses are JSON, except the progress stream. IDs are corpus-scoped. Credentials remain server-side.

| Method and path | Request | Response |
| --- | --- | --- |
| POST /api/corpora | multipart `name`, repeated `files`, optional `offline=true` | 202 Corpus (BUILDING) |
| GET /api/corpora | none | `{corpora:[Corpus], activeId:string}` |
| POST /api/corpora/demo | none | 202 demo Corpus, live when configured |
| POST /api/corpora/demo-offline | none | 202 deterministic demo Corpus, zero model calls |
| POST /api/corpora/{id}/activate | none | Corpus and persisted active selection |
| GET /api/corpora/{id}/progress | optional Last-Event-ID header | `text/event-stream`, named `progress` events |
| GET /api/corpora/{id}/graph | none | corpus, documents, passage metadata, entities, relationships, communities |
| GET /api/corpora/{id}/text-units/{passageId} | none | full source Passage |
| GET /api/corpora/{id}/chunks/{chunkId} | none | full Chunk and embedding |
| POST /api/corpora/{id}/query | `{question,mode}` | Answer with citations, timing and trace ID |
| POST /api/corpora/{id}/compare | `{question,mode}` where mode is a graph method | Comparison of graph mode and VECTOR |
| POST /api/corpora/{id}/compare-all | `{question}` | sequential LOCAL, GLOBAL, DRIFT, VECTOR Comparison |
| GET /api/traces/{traceId} | none | ordered recorded Trace |

Modes are `LOCAL`, `GLOBAL`, `DRIFT`, `VECTOR`; omitted mode defaults to LOCAL. Questions contain 1–4,000 characters. Upload names contain 1–120 characters. Query/comparison require a READY corpus.

```sh
curl -X POST http://localhost:8080/api/corpora/demo-offline
# Use the returned ID:
curl -N http://localhost:8080/api/corpora/CORPUS_ID/progress
curl http://localhost:8080/api/corpora/CORPUS_ID/graph
curl -H 'Content-Type: application/json' \
  -d '{"question":"How did Sherlock Holmes solve the bank robbery?","mode":"LOCAL"}' \
  http://localhost:8080/api/corpora/CORPUS_ID/query
curl -F 'name=Case notes' -F 'offline=true' -F 'files=@notes.txt' \
  http://localhost:8080/api/corpora
```

Progress data includes `sequence`, `stage`, `message`, passage/entity/relationship/community counts and `terminal`. Replay events after a restart summarize the stored terminal corpus status; active BUILDING processing is not resumed. Expected terminal stream closure is normal.

Answers contain `mode`, `question`, `text`, numbered `citations`, `traceId`, `noAnswer`, `timing`, `contextSize`, `documentCoverage` and vector `rankedChunks`. Timings split retrieval, embedding and model generation in integer milliseconds. Each citation contains number, source ID, filename, excerpt and `sourceKind` (`passage` or `chunk`). Traces record selections, traversals, source reads, follow-ups, embedding and synthesis. Context item identities occur in their corresponding trace.

Comparisons contain `results`, per-method `errors`, an evidence `matrix`, measured passage coverage `evidenceOverlap`, and an explanation. Matrix states are `NOT_RETRIEVED`, `BELOW_CUTOFF`, `IN_CONTEXT`, `CITED`. Local/Global/DRIFT use passage rows; Vector has exact chunk rows plus mapped passage coverage.

Errors use `{code,message}`: 400 invalid input, 404 absent corpus/source/trace, 409 corpus/model state, 413 multipart limit, 503 ingestion queue full, 500 unexpected processing error. Failed asynchronous ingestion stays visible through corpus metadata with failed stage and reason. Retry by creating a new corpus; the failed corpus and earlier collections remain available in history.
