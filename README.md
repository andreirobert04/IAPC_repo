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
| `APP_FORCE_REBUILD` | `true` locally, `false` in Docker | ingest-static |
| `APP_CHUNK_SIZE` | `100` | ingest-static |
| `APP_RETRIEVAL_TOP_K` / `APP_SIMILARITY_THRESHOLD` | `20` / `0.0` | retrieval-post |

No secrets are needed for now; `.env` is git-ignored.

## Known limitations / next tasks

- `ingest-static` with `force-rebuild: true` re-adds all documents on every start (duplicates) – Story 1, Task 2 (checksum + idempotent re-ingest) fixes this.
- `ingest-dynamic` is only a skeleton (Story 2).
- `retrieval-pre` only trims the query (Story 3 tasks).
- Nginx in front of the gateway and JWT authentication (from the project description) are not added yet.
