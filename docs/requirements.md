Build a complete, locally runnable application called “GraphRAG Explorer” that teaches users how graph-based retrieval-augmented generation works through interactive visualization.

The application must let users upload documents, watch a knowledge graph grow, explore entities and communities, ask grounded questions, replay the retrieval process, and compare graph retrieval with conventional vector search.

Implement the actual backend, persistence, model integrations, and frontend. Deliver a working application with tests and setup instructions.

PRODUCT EXPERIENCE

Provide this end-to-end workflow:

1. Upload one or more text or PDF documents, or choose a bundled demonstration dataset.
2. Watch document processing and graph construction progress live.
3. Explore the resulting entities, relationships, and communities.
4. Ask questions using Local, Global, or DRIFT search.
5. Read answers with clickable source citations.
6. Replay the evidence retrieval behind each answer.
7. Compare answers and evidence across Local, Global, DRIFT, and Vector search.
8. Switch between previously processed document collections without rebuilding them.

Use “corpus” to mean a collection of uploaded documents. Explain unfamiliar terms through contextual help.

TECHNOLOGY AND ARCHITECTURE

Use:
- Java 25 for the application, with a reusable core compatible with Java 21.
- Maven with multiple modules.
- Spring Boot and Thymeleaf for the web application.
- Plain JavaScript and CSS for the frontend, without a frontend build pipeline.
- Cytoscape.js for interactive graph rendering.
- Neo4j for graph data, corpus metadata, and vector storage.
- Neo4j Graph Data Science for Leiden community detection.
- LangChain4j for model integrations.
- PDFBox for PDF text extraction.
- Docker Compose for local startup.

Organize the project into:
- Framework-independent core: domain objects, ports, retrieval algorithms, ingestion, and answer generation.
- Neo4j adapter.
- Language-model and embedding adapter.
- Document parsing adapter.
- Web application.
- Shared adapter contract-test utilities.

The core must not depend on Spring, Neo4j drivers, or LangChain4j. Define interfaces for document parsing, language models, embeddings, graph reads, graph writes, and vector storage. Use dependency injection in the application layer.

Keep model names, credentials, database connection details, retrieval limits, and ingestion settings configurable. Pin compatible dependency versions.

DOCUMENT INGESTION

Support multiple .txt and .pdf files per corpus.

Validate missing files, unsupported types, unreadable documents, empty extracted text, and configurable upload limits. Provide actionable errors.

Process ingestion asynchronously:
- Create corpus metadata with a unique ID and BUILDING status.
- Extract text while preserving document provenance.
- Split text into ordered passages, initially around 6,000 characters with 600 characters of overlap.
- Extract entities and relationships separately from each passage using structured model output.
- Persist incremental results so graph growth appears during processing.
- Resolve duplicate entities before persisting final identities.
- Detect communities and generate community titles and summaries.
- Embed entity descriptions and community summaries for semantic retrieval.
- Independently build a vector baseline using approximately 500-character chunks.
- Mark the corpus READY after required processing succeeds, or FAILED with the failed stage and reason.

Normalize entity names using Unicode normalization, case folding, whitespace cleanup, and surrounding punctuation removal. Merge duplicate descriptions and source references, resolve type conflicts deterministically, and update relationship endpoints consistently.

Every entity and relationship must retain references to the passages supporting it. Relationships must include a type, description, and weight.

Stream progress using Server-Sent Events. Include passage counts, entity and relationship additions, community formation, indexing progress, completion, and errors. Use heartbeats and support reconnection without duplicating graph elements.

Do not report an expected stream closure after completion as a disconnection error.

COMMUNITIES AND PERSISTENCE

Detect weighted graph communities using Leiden. Provide an in-process community detector for tests or deployments without GDS.

Show meaningful communities with at least three members. Generate titles and summaries from member descriptions and internal relationships.

Persist:
- Corpus metadata, status, creation time, and mode.
- Documents and source passages.
- Entities and relationships.
- Communities and memberships.
- Entity, community, and chunk embeddings.
- Vector chunks and their provenance.

Scope every database read and write to a corpus ID. Use stable identities and idempotent writes.

Preserve corpora across application restarts. Mark interrupted BUILDING corpora FAILED on startup with an explanation.

Provide corpus history and activation. Restore the previously selected corpus when possible. Starting a new corpus must preserve existing ones.

DATA CONTRACTS

Define typed domain objects for:
- Corpus and document.
- Source passage with ID, document name, ordinal, text, and optional source location.
- Entity with ID, name, type, description, and supporting passage IDs.
- Relationship with source, target, type, description, weight, and supporting passage IDs.
- Community with ID, title, summary, and members.
- Vector chunk with document provenance and embedding.
- Citation with source ID, document name, and excerpt.
- Retrieval step and retrieval trace.
- Answer with mode, question, text, citations, trace ID, no-answer status, and timing.
- Comparison results and evidence diagnostics.

Allow optional attributes and source locators such as file paths and line ranges so the core can later support structured knowledge graphs.

SEARCH MODES

Implement four distinct retrieval methods with configurable limits.

Local:
- Match the question to approximately three seed entities using semantic similarity when available.
- Fall back to deterministic keyword matching in offline mode.
- Expand their one-hop neighborhoods.
- Rank relationships by weight and collect relevant supporting passages.
- Initially limit retrieval to approximately ten relationships and five passages.
- Generate one answer from the assembled context.

Global:
- Match the question to approximately three community summaries.
- Retrieve relevant supporting passages from their members, initially up to two per community.
- Generate an answer suitable for broad themes and cross-document questions.

DRIFT:
- Select relevant communities.
- Generate bounded follow-up questions from the original question and community context.
- Run Local-style retrieval for each follow-up.
- Deduplicate the accumulated evidence.
- Generate a final answer from the combined context.
- Preserve the parent-child structure of follow-ups in the retrieval trace.

Vector:
- Embed the question and rank corpus chunks by cosine similarity.
- Use the top five chunks for answer generation.
- Return the top twelve ranked chunks for inspection, including scores and whether each chunk entered the answer context.
- Use the same answer-grounding rules as the graph methods.

Handle no matching evidence and missing communities explicitly. Never fabricate graph results to fill an empty state.

ANSWER GROUNDING

Generate answers only from retrieved context.

Number context items and require inline source markers such as [1]. Treat entities, relationships, and summaries as background; user-facing citations must resolve to actual source passages or vector chunks.

Validate citation references server-side:
- Reject references outside the supplied context.
- Renumber valid citations consistently.
- Ensure each citation opens the correct source.
- Ensure cited evidence appears in the corresponding retrieval trace.

If context cannot answer the question, return a clear no-answer result.

Display citations in the answer and an expandable Sources list containing filenames and excerpts.

RETRIEVAL REPLAY

Capture the actual ordered retrieval operations during execution. Replay must visualize this recorded evidence flow, not an invented animation or private model reasoning.

Support step kinds including:
- Entity selected.
- Relationship traversed.
- Community selected.
- Source passage retrieved.
- Follow-up question created.
- Vector query embedded.
- Vector chunk selected.
- Answer synthesis.

Each step should carry stable identifiers, a readable label, and useful metadata such as score, depth, source location, or branch.

Provide:
- Play and pause.
- Step backward and forward.
- A clickable step timeline.
- Current-step counter.
- Plain-language explanation of the current operation.
- A synchronized trace list.
- Close and reopen behavior.

Highlight the active nodes, edges, communities, and source passages. Dim unrelated graph elements while preserving context.

For DRIFT, show a branching tree connecting the original question, community choices, follow-up questions, and retrieved evidence.

For Vector search, replay selections within the ranked chunk list.

Keep replay stable after viewport changes and panel toggles. Clear stale replay state when switching corpora.

Traces may be stored in application memory. Clearly explain that application restarts remove traces while persisted corpora remain available.

COMPARISON

Provide two comparison experiences.

Pairwise:
- Run the selected graph method and Vector search fresh on the same question and corpus.
- Display answers, citations, latency, context size, document coverage, and evidence overlap side by side.
- Show vector scores and the top-five cutoff.
- Provide replay for each method.
- Include a short explanation grounded in measured differences.
- Do not automatically declare graph retrieval superior.

Four-way:
- Run Local, Global, DRIFT, and Vector sequentially on the same question.
- Display one column per method.
- Include answers, citations, retrieval-step counts, and elapsed time split into retrieval, embedding, and model generation.
- Avoid double-counting timing categories.
- Preserve successful results if one method fails.
- Provide rerun and JSON download actions.

Add an evidence matrix showing whether each passage was:
- Not retrieved.
- Retrieved but below the vector cutoff.
- Included in context.
- Cited.

Allow users to mark expected evidence. Use its observed retrieval and citation state to help diagnose possible retrieval, ranking, or generation failures. Present these as diagnostics, not proof that an answer is correct.

INTERFACE AND VISUAL DESIGN

Create a desktop-first, full-height analysis workspace.

Use a restrained light theme:
- Pale gray page background.
- White panels and an off-white graph canvas.
- Dark charcoal text and subtle borders.
- Blue for Local search.
- Teal for Global search.
- Magenta for DRIFT.
- Orange for the active replay step.
- Pastel community regions and distinct, consistent entity-type colors.

Use system sans-serif typography, compact controls, small rounded corners, and monospaced numerical labels where useful.

Layout:
- Header with product name, active corpus, history, and new-corpus action.
- Left chat panel with independently scrolling conversation history.
- Mode selector and composer pinned at the bottom.
- Large central graph workspace.
- Right-side detail, trace, or help panels.
- Replay controls along the bottom.
- Knowledge Graph and Compare tabs.

Initial state:
- Brief explanation of the application.
- Multi-file upload.
- Built-in demonstration dataset.
- Explicit offline demonstration option.

Graph interactions:
- Pan, zoom, fit-to-view, and node dragging.
- Search entities by name with keyboard-accessible suggestions.
- Center and highlight a selected entity.
- Inspect entity descriptions, types, neighbors, relationships, and sources.
- Inspect community titles, summaries, and membership.
- Toggle entity-type coloring.
- Toggle community visualization independently of community detection.
- Display community regions and a compact legend with an expandable full list.

Prevent overlapping toolbars, clipped controls, and chat messages pushing the composer offscreen. Adapt panels for smaller viewports.

Use accessible labels, visible focus states, keyboard controls, and text indicators alongside color.

CONTEXTUAL HELP

Place small help controls beside upload, corpus history, search modes, graph settings, communities, answers, replay, and comparison.

Open explanations in an in-app side panel. Explain what the feature means, when to use it, and what the current data demonstrates.

DEMO AND OFFLINE BEHAVIOR

Bundle a small narrative dataset with recurring people, places, organizations, and connected events, such as a Sherlock Holmes-themed sample.

Support:
- Live processing using configured models.
- Deterministic offline processing when no credentials are configured.
- An explicit offline showcase that makes no model API calls even when credentials exist.

Clearly label deterministic and prerecorded behavior. An explicit prerecorded showcase must not accept arbitrary questions as if they were answered live.

Keep demonstration content rich enough to show multiple communities, relationship traversal, and differences between retrieval methods.

API

Implement and document:
- POST /api/corpora
- GET /api/corpora
- POST /api/corpora/demo
- POST /api/corpora/demo-offline
- POST /api/corpora/{id}/activate
- GET /api/corpora/{id}/progress
- GET /api/corpora/{id}/graph
- GET /api/corpora/{id}/text-units/{passageId}
- GET /api/corpora/{id}/chunks/{chunkId}
- POST /api/corpora/{id}/query
- POST /api/corpora/{id}/compare
- POST /api/corpora/{id}/compare-all
- GET /api/traces/{traceId}

Query requests include question and mode.

Validate inputs and corpus readiness. Return structured errors with appropriate HTTP status codes. Keep credentials server-side and safely render uploaded text and model responses.

CORE REUSABILITY

Also expose programmatic operations for:
- Importing an existing graph without LLM extraction.
- Retrieving context and traces without generating an answer.
- Selecting keyword, semantic, identifier-aware, or hybrid seed matching.
- Removing or replacing a source and recomputing affected derived data.

Keep these as library capabilities; they do not require additional user-interface screens.

DELIVERY AND ACCEPTANCE

Provide:
- Complete source code and Maven configuration.
- Dockerfile and Docker Compose setup with persistent database storage.
- An example environment file without secrets.
- Exact startup, configuration, and test commands.
- Bundled demo data.
- Architecture and API documentation.
- Unit tests, adapter contract tests, database integration tests, and browser tests for critical workflows.

Verify:
- Uploaded documents produce persisted graph data and source provenance.
- Graph growth appears during ingestion.
- Corpus switching and restart recovery work.
- All four retrieval methods execute their distinct algorithms.
- Citations resolve to evidence actually retrieved.
- Replay follows recorded operations.
- Comparisons use measured results.
- Offline showcase makes no model API calls.
- Failures remain visible and recoverable.
- Chat, graph controls, panels, and replay remain usable together.

Implement the system in stages, validating each stage, and continue until the full application works. Document any remaining limitations explicitly.