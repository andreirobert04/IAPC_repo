# IAPC – RAG microservices

Spring Boot 3.3 · Java 21 · Spring AI · Spring Cloud (Eureka + Gateway) · Ollama · Qdrant

## Architecture

```
client ──► api-gateway :8080 ──(routes by Eureka name)──► retrieval-pre / ingest-static / ingest-dynamic / retrieval-post
                │
                └── eureka-server :8761  (every service registers here)

question flow:  api-gateway ─► retrieval-pre ─(Eureka)─► retrieval-post ─► Qdrant + Ollama ─► answer with citations
ingestion:      ingest-static ─► ./resources/*.pdf ─► Ollama embeddings ─► Qdrant (collection rag_documents)
```

| Module | Port (local) | Story | What it does |
|---|---|---|---|
| `eureka-server` | 8761 | – | Service registry |
| `api-gateway` | 8080 | – | Spring Cloud Gateway, the only public entry point. Routes `/<service-name>/**` to the service registered in Eureka |
| `ingest-static` | 8083 | Story 1 | Reads the PDFs from `./resources`, chunks, embeds with Ollama, stores in Qdrant. The only service that creates the Qdrant collection |
| `ingest-dynamic` | 8084 | Story 2 | **Skeleton** – registers in Eureka; internet ingestion, versioning, watcher/webhook and security go here |
| `retrieval-pre` | 8085 | Story 3 | Entry point for questions. Prepares the query (currently only trims + logs the original) and calls `retrieval-post` through Eureka. Filtering, normalization, acronym and multi-query expansion go here |
| `retrieval-post` | 8086 | Story 4 | Vector search in Qdrant + grounded answer from Ollama with citations. Reranker, neighbor stitching and evaluation go here |

## Run locally (IntelliJ)

1. Start Qdrant: `docker compose up -d qdrant` (or the old `docker compose -f qdrant.yml up -d` – not both, they use the same ports)
2. Start Ollama and pull the models: `ollama pull qwen2.5:1.5b` and `ollama pull nomic-embed-text`
3. Run, in this order: `EurekaServerApplication` → `IngestStaticApplication` → `RetrievalPostApplication` → `RetrievalPreApplication` → `ApiGatewayApplication` (`IngestDynamicApplication` is optional)
4. Check http://localhost:8761 – all services should be **UP**

The working directory must be the repo root (IntelliJ default), so `ingest-static` finds `./resources/*.pdf`.

## Run everything with Docker

```bash
cp .env.example .env      # optional, all values have defaults
docker compose up --build -d
```

- Startup order: `qdrant` + `eureka-server` (healthy) → `ingest-static` (creates the collection, first start embeds all PDFs) → `ingest-dynamic`, `retrieval-pre`, `retrieval-post`; `api-gateway` starts after Eureka.
- Only the gateway (8080) is public. Eureka (8761) and Qdrant (6333/6334) are bound to localhost. The services are reachable only through the gateway.
- Ollama runs on the host (for GPU speed); containers reach it at `host.docker.internal:11434`.
- PDFs: put them in `./resources` – the folder is mounted read-only into both ingestion services.

## Try it (through the gateway)

```bash
# ask a question
curl -X POST http://localhost:8080/retrieval-pre/api/ask \
  -H "Content-Type: text/plain" \
  -d "What are the steps in the incident management policy?"

# list the ingested documents
curl http://localhost:8080/ingest-static/api/ingestion/sources

# routes the gateway built from Eureka
curl http://localhost:8080/actuator/gateway/routes
```

## Configuration

Each service reads its settings from `application.yaml`; every value can be overridden by an environment variable (see `.env.example`):

| Variable | Default | Used by |
|---|---|---|
| `EUREKA_URL` | `http://localhost:8761/eureka/` | all |
| `QDRANT_HOST` / `QDRANT_GRPC_PORT` / `QDRANT_COLLECTION` | `localhost` / `6334` / `rag_documents` | ingest-static, retrieval-post |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | ingest-static, retrieval-post |
| `CHAT_MODEL` / `EMBEDDING_MODEL` | `qwen2.5:1.5b` / `nomic-embed-text:latest` | retrieval-post / ingest-static, retrieval-post |
| `APP_PDF_PATH` | `file:resources/*.pdf` | ingest-static |
| `APP_FORCE_REBUILD` | `false` (unchanged files are skipped) | ingest-static |
| `APP_CHUNK_SIZE` / `APP_CHUNK_OVERLAP` | `400` / `50` (tokens) | ingest-static |
| `APP_MIN_CHUNK_LENGTH` | `20` (characters) | ingest-static |
| `APP_PDF_MODE` | `text` (`text` \| `page` \| `paragraph`) | ingest-static |
| `APP_RETRIEVAL_TOP_K` / `APP_SIMILARITY_THRESHOLD` | `20` / `0.0` | retrieval-post |

No secrets are needed for now; `.env` is git-ignored.

## Static ingestion (Story 1)

### Task 1 – PDF ingestion

Every PDF in `./resources` is read page by page, split into chunks and stored in Qdrant.

**Reading** (`app.rag.pdf.mode`)
- `text` (default): content-stream order with PDFBox. Multi-column pages keep each column together and table rows stay on one line.
- `page`: Spring AI `PagePdfDocumentReader` (keeps the visual layout, so columns on the same line get mixed).
- `paragraph`: Spring AI `ParagraphPdfDocumentReader` (only for PDFs with bookmarks).

**Chunking** (`OverlappingTextSplitter`, structure-aware)
- The text is cut into lines and sentences, so a chunk never ends inside a word. A line without punctuation is split on word boundaries.
- At most `APP_CHUNK_SIZE` tokens per chunk; each chunk starts with the last `APP_CHUNK_OVERLAP` tokens of the previous one.
- Chunks shorter than `APP_MIN_CHUNK_LENGTH` characters are dropped.
- Changing the chunk size, the overlap or `EMBEDDING_MODEL` requires re-ingesting (delete the collection and restart `ingest-static`).

**Metadata schema of every chunk**

| Field | Meaning |
|---|---|
| `doc_id` | stable UUID derived from the file name (same for every version of the file) |
| `version` | document version (`1` for now; increased by the update process) |
| `source` | file name |
| `page` | page number (1-based) |
| `chunk_index` | position of the chunk in the whole document, from 0 |
| `checksum` | SHA-256 of the file |
| `embedding_model` | model used for the embedding |
| `ingested_at` | ISO-8601 timestamp of when the chunk was ingested |

**No duplicates:** the Qdrant point id is a UUID derived from `doc_id + version + chunk_index`, so ingesting the same file again overwrites the same points.

**Sample set:** `resources/` includes `access_control_matrix.pdf` (table) and `security_newsletter_two_columns.pdf` (two columns).

**Manual check** – random chunks with their metadata:
```bash
curl "http://localhost:8083/api/ingestion/chunks/sample?n=20"
curl "http://localhost:8083/api/ingestion/chunks/sample?n=5&source=security_newsletter_two_columns.pdf"
```

**Tests:** `mvn -pl ingest-static test` (splitter, ids, metadata schema, table extraction, error cases).

### Task 2 – Update process

On every start `ingest-static` compares each PDF with what Qdrant already holds for its `doc_id`:

| Situation | Result | What happens |
|---|---|---|
| not stored yet | `NEW` | ingested as `version 1` |
| same checksum | `UNCHANGED` | skipped (no embedding call) |
| different checksum | `UPDATED` | ingested as `version + 1`, old chunks removed |
| any error | `FAILED` | logged; the stored version is left as it was, the other files continue |

The start-up log ends with a summary, e.g. `Ingestion finished for 9 PDF(s): {UPDATED=1, UNCHANGED=8}`.

**How the replacement stays safe** (`QdrantChunkStore.replace`)
1. Reading, chunking and embedding run first, in memory. If any of them fails, Qdrant is not touched.
2. Then a single Qdrant batch request runs two operations in order: upsert the new chunks, then delete every other chunk of the same `doc_id` (old version or extra chunks). The new content is always written before the old one is deleted, so a query never gets an empty result; old and new chunks coexist only for the duration of that one request.

Qdrant has no multi-operation transactions, so this is "atomic" in the sense of one request issued after all failure-prone work is done.

`APP_FORCE_REBUILD=true` re-ingests every file even if unchanged (e.g. after changing `EMBEDDING_MODEL` or the chunk size); the version stays the same.

**Tests:** `RagIngestionUpdateTest` uses two versions of `backup_policy.pdf` (3 pages → 1 page): new file, unchanged file skipped, changed file replaced with the expected chunk count, failure while embedding / while storing keeps the previous version, force rebuild.

### Task 3 – Delete process

When a PDF is removed from `./resources` or explicitly deleted, all its chunks are removed from Qdrant by `doc_id`, with an immediate post-deletion check to ensure no orphaned vectors remain.

| Situation | Result | What happens |
|---|---|---|
| removed from `./resources` | `DELETED` | on startup reconciliation, all chunks of that `doc_id` are deleted; checked for 0 remaining orphans |
| explicit deletion by `doc_id` / source | – | deletes all chunks matching the document and verifies 0 remaining orphans |
| orphaned vectors in collection | – | collection-wide scan finds vectors without active `doc_id` and purges them |

**How the delete process stays safe** (`QdrantChunkStore.delete` and orphan checks)
1. Deletion runs a filtered delete in Qdrant matching `doc_id == docId`.
2. **Orphaned vectors check:** immediately queries/scrolls Qdrant for any points still carrying that `doc_id`. If any lingering chunks remain (`count > 0`), a `RagException` is thrown to alert to an incomplete deletion.
3. **Reconciliation on startup:** `initializeVectorStore` compares stored `doc_id`s in Qdrant with active PDFs in `./resources`. Any document no longer present on disk is deleted (`DELETED` in summary), and any orphaned vectors without valid documents are cleaned up.

**Endpoints:**
```bash
# delete by stable doc_id
curl -X DELETE http://localhost:8080/ingest-static/api/ingestion/documents/<doc_id>

# delete by source file name
curl -X DELETE http://localhost:8080/ingest-static/api/ingestion/sources/backup_policy.pdf

# inspect orphaned vectors across the collection
curl http://localhost:8080/ingest-static/api/ingestion/orphans
```

**Tests:** `RagIngestionDeleteTest` (delete by `doc_id`, delete by source, non-existent doc handling, failure handling, simulated orphan detection and exception, startup reconciliation for removed files, orphaned vectors purge).

### Task 4 – Ingestion manifest and re-index support

Every ingestion, update, re-index, and delete event is tracked in an ingestion manifest log recording **what** was ingested, **when** (ISO-8601 timestamp), and with **which embedding model version**.

| Event | Result | What happens |
|---|---|---|
| initial ingestion | `NEW` | ingested as version 1; recorded in manifest with timestamp and embedding model |
| same checksum & same model | `UNCHANGED` | skipped without calling embedding model; recorded as `UNCHANGED` in history |
| model version changed | `REINDEXED` | detected embedding model mismatch; re-embeds all chunks with new model version without changing document version |
| explicit re-index request | `REINDEXED` | forces re-chunking and re-embedding with current model via API |
| checksum changed | `UPDATED` | new version replaces old chunks; recorded in manifest with incremented version |
| file removed / deleted | `DELETED` | chunks purged; recorded as `DELETED` in manifest history |

**Endpoints:**
```bash
# get full manifest (active documents + chronological audit log)
curl http://localhost:8080/ingest-static/api/ingestion/manifest

# get manifest for a specific document
curl http://localhost:8080/ingest-static/api/ingestion/manifest/<doc_id>

# re-index all documents with current embedding model
curl -X POST http://localhost:8080/ingest-static/api/ingestion/reindex

# re-index a specific document by source name
curl -X POST "http://localhost:8080/ingest-static/api/ingestion/reindex?source=access_control_matrix.pdf"

# re-index a specific document by doc_id
curl -X POST "http://localhost:8080/ingest-static/api/ingestion/reindex?docId=<doc_id>"
```

**Tests:** `RagIngestionManifestTest` (manifest logging on NEW/UNCHANGED/UPDATED/REINDEXED/DELETED, automatic re-index on model version mismatch, explicit re-index by doc_id/source/all, manifest snapshot vs event history, doc-specific manifest lookup).

## Known limitations / next tasks

- `ingest-dynamic` is only a skeleton (Story 2).
- `retrieval-pre` only trims the query (Story 3 tasks).
- Nginx in front of the gateway and JWT authentication (from the project description) are not added yet.
