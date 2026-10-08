# GraphRAG Explorer

A locally runnable workspace for building a knowledge graph from text/PDF documents, asking grounded questions, replaying actual retrieval, and comparing Local, Global, DRIFT and Vector search.

## Start with Docker

Requires Docker Compose and about 3 GB of available memory. Java and Maven are provided by the image.

```sh
cp .env.example .env
docker compose up --build -d
```

Open **http://localhost:8080**. Choose **New corpus → Offline showcase** for deterministic processing without any model calls. **Use demo dataset** uses your configured models when credentials exist, otherwise deterministic processing. Upload your own `.txt` or searchable `.pdf` documents to create another corpus.

```sh
docker compose logs -f app
docker compose stop
docker compose up -d
```

Corpus data and the active selection persist in the named Neo4j volume. Stopping during ingestion marks that corpus FAILED at the next startup; existing READY corpora remain usable. `docker compose down` preserves the volume. Do not use `down -v` if you want to retain corpora.

## Start without Docker

Requires JDK 25 and Maven 3.9+. The reusable modules compile for Java 21; only the application targets Java 25.

```sh
mvn clean verify
STORE=file COMMUNITY_DETECTOR=inprocess java -jar web/target/web-1.0.0.jar
```

Open **http://localhost:8080**. This explicit single-process fallback stores atomic JSON snapshots in `.data/` and uses weighted label propagation instead of GDS Leiden. It supports the same UI and source persistence. Docker uses Neo4j and weighted Leiden.

To run the application against a Neo4j instance started with Compose:

```sh
docker compose up -d neo4j
NEO4J_URI=bolt://localhost:7687 NEO4J_PASSWORD=explorer-local-password java -jar web/target/web-1.0.0.jar
```

Use the password you configured in `.env` when you change the example value. Java startup reads exported environment variables; it does not automatically load `.env`.

## Models and configuration

The example file contains no real credentials. Set `MODEL_API_KEY`, `MODEL_BASE_URL`, `CHAT_MODEL` and `EMBEDDING_MODEL` in `.env` for Compose. For direct Java startup, export these variables in the shell. Models must support OpenAI-compatible chat JSON-object output and embeddings; LangChain4j handles the requests. Never put credentials into browser JavaScript.

Without a key, processing and retrieval are deterministic: sentence co-mention extraction, keyword graph matching, hashed term vectors, and source-sentence answers. An explicitly offline corpus continues using those models even if live credentials exist. There is no prerecorded answer mode.

[All settings](docs/configuration.md), [architecture and library API](docs/architecture.md), [HTTP API](docs/api.md), [validation and limitations](docs/validation.md).

## Tests

```sh
mvn clean verify
npm ci
npx playwright install chromium
```

Start the application in a separate terminal:

```sh
STORE=file DATA_DIR=/tmp/graphrag-browser-test PORT=8090 java -jar web/target/web-1.0.0.jar
```

Then run the browser suite:

```sh
APP_URL=http://localhost:8090 npm run test:browser
```

Browser tests create additional corpora; use a dedicated data directory/database for testing. Java tests run the live model adapter against a local mock provider and do not require a paid API key.

For actual Neo4j/GDS contract and integration tests:

```sh
docker compose up -d neo4j
NEO4J_TEST_URI=bolt://localhost:7687 NEO4J_TEST_PASSWORD=explorer-local-password mvn -pl adapter-neo4j -am verify
```

Database tests are skipped unless `NEO4J_TEST_URI` is set. Use a dedicated database instance: tests create uniquely named corpora and update its active selection.

## Git checkpoints

The five sequential task branches are fallback snapshots. The final application is on `main`, also preserved on `task/05-delivery`.

```sh
git branch --list
# Inspect any earlier checkpoint without changing your working tree:
git show task/03-web-api:docs/progress.md
# Switch to a checkpoint only with a clean working tree:
git switch task/04-workspace
```

Early branches intentionally contain only their completed stage. See [progress](docs/progress.md) for each checkpoint and its validation.

The repeatable app/database restart check uses only Python's standard library:

```sh
python3 tests/integration/persistence.py before
# Restart the app and its database while retaining database storage.
docker compose stop app neo4j
docker compose up -d
python3 tests/integration/persistence.py after
```

Pass `--url http://localhost:PORT` for a different port. This check creates a dedicated demo corpus and updates the active selection.
