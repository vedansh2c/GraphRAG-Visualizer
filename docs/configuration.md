# Configuration

Docker Compose loads `.env`; direct Java startup uses environment variables. Configuration defaults are in `web/src/main/resources/application.properties`. No key is required for offline mode.

| Variable | Default | Purpose |
| --- | --- | --- |
| APP_PORT | 8080 | Compose host application port |
| PORT | 8080 | Direct Java application port |
| STORE | neo4j | `neo4j` or explicit `file` profile (direct Java) |
| DATA_DIR | .data | File profile directory |
| NEO4J_URI | bolt://localhost:7687 | Direct Java database URI; Compose uses its service hostname |
| NEO4J_USER | neo4j | Database user |
| NEO4J_PASSWORD | explorer-local-password | Local database password |
| NEO4J_DATABASE | neo4j | Database name |
| NEO4J_HTTP_PORT / NEO4J_BOLT_PORT | 7474 / 7687 | Compose host database ports |
| COMMUNITY_DETECTOR | gds | `gds` or explicit `inprocess`; file profile always uses in-process detection |
| MODEL_API_KEY | empty | Live-model credential; empty selects deterministic models |
| MODEL_BASE_URL | https://api.openai.com/v1 | OpenAI-compatible endpoint |
| CHAT_MODEL | gpt-4.1-mini | Chat/extraction model |
| EMBEDDING_MODEL | text-embedding-3-small | Embedding model |
| MODEL_TIMEOUT | 90 | Request timeout in seconds; live requests allow one retry |
| MAX_FILE_SIZE | 10MB | Spring multipart limit per file |
| MAX_REQUEST_SIZE | 40MB | Spring multipart total request limit |
| MAX_FILES | 20 | Maximum documents per upload |
| MAX_TOTAL_BYTES | 40000000 | Combined document bytes, excluding multipart framing |
| PASSAGE_SIZE / PASSAGE_OVERLAP | 6000 / 600 | Extraction passage character sizes |
| CHUNK_SIZE | 500 | Independent vector baseline chunk size; overlap is min(50,size/10) |
| SEED_LIMIT | 3 | Local seed entities |
| RELATIONSHIP_LIMIT / PASSAGE_LIMIT | 10 / 5 | Local relationships and passages per retrieval branch |
| COMMUNITY_LIMIT / PASSAGES_PER_COMMUNITY | 3 / 2 | Global/DRIFT communities and passages per community |
| FOLLOWUP_LIMIT | 3 | Maximum DRIFT follow-ups (allowed 1–10) |
| VECTOR_CONTEXT / VECTOR_INSPECT | 5 / 12 | Included vector chunks / inspected ranked chunks |

Extraction and vector indexes use different chunk boundaries. Configure sizes before ingestion. Keep the same embedding model when reopening a live corpus: changing the provider or embedding dimensions requires rebuilding that corpus. A live corpus requires restored model credentials for queries after restart. The explicit offline showcase never uses those credentials.

The ingestion queue has two workers and eight pending jobs. Overload returns 503 and retains the failed corpus in history. For GDS-free Neo4j use `COMMUNITY_DETECTOR=inprocess`; a GDS error otherwise fails ingestion visibly rather than silently switching algorithms.

Pinned dependencies: Spring Boot 3.5.6, LangChain4j OpenAI 1.20.0, Neo4j driver 5.28.4, PDFBox 3.0.5, Cytoscape.js 3.33.1 and Playwright 1.56.1. Container stack: Neo4j 5.26.12 with GDS 2.13.2; Maven 3.9.11 with Temurin 25, and Temurin 25 JRE. The community implementation follows the [official weighted Leiden projection and stream APIs](https://neo4j.com/docs/graph-data-science/current/algorithms/leiden/); the application uses the [Spring Boot 3.5 Java compatibility line](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
