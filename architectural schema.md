### Architectural Schema: GenAI Spring Boot Demo Project

This project is a Spring Boot application built with **Spring AI** that provides OpenAI-powered generative AI chat and Retrieval-Augmented Generation (RAG) capabilities with multiple vector store strategies (in-memory/file-based and PostgreSQL with `pgvector`), alongside document ingestion and full observability stacks.

---

### High-Level System Architecture Diagram

```mermaid
graph TB
    subgraph ClientLayer ["Client / API Consumers"]
        Client[HTTP Clients / Frontend]
    end

    subgraph APIControllers ["REST Controllers"]
        ChatCtrl["OpenAIChatController<br/><code>/api/openai/chat/summarize</code>"]
        HRCtrl["EmployeeHandbookController<br/><code>/api/employee-handbook/ask</code>"]
        QACtrl["InternalQAController<br/><code>/api/internal-qa/ask</code>"]
    end

    subgraph SpringAIClients ["Spring AI ChatClient Layer"]
        GeneralClient["openAIGeneralChatClient<br/>(Standard ChatClient)"]
        RAGClient["openAIRAGChatClient<br/>(QuestionAnswerAdvisor + SimpleLoggerAdvisor)"]
        AdvRAGClient["openAIAdvancedRAGChatClient<br/>(RetrievalAugmentationAdvisor)"]
    end

    subgraph RAGAdvisors ["Advisors & Augmentation"]
        QAAdv["QuestionAnswerAdvisor"]
        LogAdv["SimpleLoggerAdvisor"]
        AugAdv["RetrievalAugmentationAdvisor<br/>- VectorStoreDocumentRetriever<br/>- ContextualQueryAugmenter"]
    end

    subgraph IngestionPipeline ["Ingestion & Document Processing"]
        IngestService["RagIngestionService<br/><code>@PostConstruct initializePgVectorStore()</code>"]
        PDFReader["PDF Readers / Extractors<br/>(Paragraph / Page mode)"]
        Splitter["TokenTextSplitter"]
        MDReader["Markdown Reader / Ingestor<br/>(SimpleRagConfiguration)"]
    end

    subgraph VectorStores ["Vector Stores & Storage"]
        SimpleStore["SimpleVectorStore<br/>(In-Memory / JSON File Store)"]
        PgStore["PgVectorStore<br/>(Table: <code>vector_store_rag</code>)"]
        JSONFile["vectorstore.json"]
        PostgresDB[(PostgreSQL + pgvector<br/>Port: 5433)]
    end

    subgraph ExternalAI ["External AI Provider (OpenAI)"]
        OpenAIChat["OpenAI Chat Model<br/>(gpt-4o-mini / gpt-4o)"]
        OpenAIEmbed["OpenAI Embedding Model<br/>(text-embedding-3-small)"]
    end

    subgraph ObservabilityStack ["Observability & Telemetry (Docker)"]
        Prometheus["Prometheus (Metrics)"]
        Loki["Loki (Logs)"]
        Promtail["Promtail (Log Shipping)"]
        Tempo["Tempo / Jaeger (Tracing)"]
        Grafana["Grafana Dashboards"]
    end

    %% Client Routing
    Client -->|POST /api/openai/chat/summarize| ChatCtrl
    Client -->|POST /api/employee-handbook/ask| HRCtrl
    Client -->|POST /api/internal-qa/ask| QACtrl

    %% Controller to ChatClient
    ChatCtrl --> GeneralClient
    HRCtrl --> RAGClient
    QACtrl --> AdvRAGClient

    %% ChatClients to Advisors
    RAGClient --> QAAdv
    RAGClient --> LogAdv
    AdvRAGClient --> AugAdv

    %% Advisors to Vector Stores
    QAAdv --> SimpleStore
    AugAdv --> PgStore

    %% Ingestion Flow
    IngestService --> PDFReader
    PDFReader --> Splitter
    Splitter --> PgStore
    MDReader --> SimpleStore
    SimpleStore -.->|Persist/Load| JSONFile
    PgStore --> PostgresDB

    %% External Model Connections
    GeneralClient --> OpenAIChat
    RAGClient --> OpenAIChat
    AdvRAGClient --> OpenAIChat
    SimpleStore --> OpenAIEmbed
    PgStore --> OpenAIEmbed

    %% Observability Connections
    ChatCtrl -.-> Promtail
    HRCtrl -.-> Promtail
    QACtrl -.-> Promtail
    Promtail --> Loki
    Loki --> Grafana
    Prometheus --> Grafana
    Tempo --> Grafana
```

---

### Component Breakdown and Data Flows

#### 1. Direct LLM Chat Flow (`OpenAIChatController`)
- **Endpoint:** `POST /api/openai/chat/summarize`
- **Component:** `openAIGeneralChatClient`
- **Execution Path:**
  1. Receives input user prompt text.
  2. Directly delegates the prompt to OpenAI Chat Model via Spring AI `ChatClient`.
  3. Returns raw completions/summaries without augmentation.

#### 2. Simple RAG: Employee Handbook (`EmployeeHandbookController`)
- **Endpoint:** `POST /api/employee-handbook/ask`
- **Components:** `openAIRAGChatClient`, `QuestionAnswerAdvisor`, `SimpleLoggerAdvisor`, `SimpleVectorStore`.
- **Knowledge Base:** Local Markdown documents (`src/main/resources/rag/mini-employee-handbook/files/*.md`).
- **Execution Path:**
  1. On application startup, `SimpleRagConfiguration` checks for existing `vectorstore.json`. If absent or not initialized, it loads markdown files, chunks them with `TokenTextSplitter`, embeds them via OpenAI Embedding Model (`text-embedding-3-small`), and caches vectors to disk (`vectorstore.json`).
  2. When a user asks an HR question, `QuestionAnswerAdvisor` retrieves top-k matching document chunks from `SimpleVectorStore` based on similarity threshold.
  3. Augments the system prompt (`HR Assistant`) with the retrieved context and sends it to OpenAI Chat Model.
  4. `SimpleLoggerAdvisor` logs request and response interactions.

#### 3. Advanced RAG: Internal QA with PostgreSQL / PgVector (`InternalQAController`)
- **Endpoint:** `POST /api/internal-qa/ask` (supports optional `filter` query parameter).
- **Components:** `openAIAdvancedRAGChatClient`, `RetrievalAugmentationAdvisor`, `VectorStoreDocumentRetriever`, `ContextualQueryAugmenter`, `PgVectorStore`.
- **Knowledge Base:** Internal PDF files (`src/main/resources/rag/qa-over-internal-docs/*.pdf`).
- **Execution Path:**
  1. **Document Ingestion (`RagIngestionService`):**
     - Triggered automatically on startup via `@PostConstruct initializePgVectorStore()`.
     - Scans PDF internal documents.
     - Splits documents into chunks using `TokenTextSplitter`.
     - Attaches metadata (source, page index, chunk index).
     - Embeds and stores chunks in PostgreSQL table (`vector_store_rag`) via `PgVectorStore`.
  2. **Query Processing:**
     - `RetrievalAugmentationAdvisor` retrieves relevant document chunks via `VectorStoreDocumentRetriever` from `PgVectorStore` (with optional runtime metadata filtering).
     - `ContextualQueryAugmenter` formats and injects retrieved context with exact source citations `[source:filename.pdf, page_number:X]`.
     - Sends augmented prompt to OpenAI Chat Model and returns formatted answers with exact citations.

---

### Infrastructure & Supporting Services

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Docker Infrastructure                         │
├───────────────────────────────────┬────────────────────────────────────┤
│           Data Storage            │       Observability & Metrics      │
├───────────────────────────────────┼────────────────────────────────────┤
│ • PostgreSQL 16 + pgvector        │ • Prometheus (metrics scraping)    │
│   - Port: 5433                    │ • Loki + Promtail (log aggregation)│
│   - Database: postgres            │ • Tempo + Jaeger (traces)          │
│   - Table: vector_store_rag       │ • Grafana (dashboards & alerts)    │
└───────────────────────────────────┴────────────────────────────────────┘
```

---

### Technology Stack Summary

- **Framework:** Spring Boot 3.x, Spring AI 1.x
- **Language:** Java 21+
- **AI Integrations:** OpenAI Chat (`gpt-4o-mini`), OpenAI Embeddings (`text-embedding-3-small`)
- **Vector Stores:** `SimpleVectorStore` (JSON-backed in-memory), `PgVectorStore` (PostgreSQL vector extension)
- **Document Extractors:** Apache PDFBox / Spring AI PDF Readers, Spring AI Markdown Reader, `TokenTextSplitter`
- **Database:** PostgreSQL with `pgvector`
- **Observability:** Prometheus, Grafana, Loki, Promtail, Tempo, Jaeger, Logback-Spring