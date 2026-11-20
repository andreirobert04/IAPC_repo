package com.genai.first.ingeststatic.config.data;

public final class RagConstants {
    private RagConstants() {}

    // app.rag.pdf.mode values
    public static final String TEXT = "text";
    public static final String PAGE_LAYOUT = "page";
    public static final String PARAGRAPH = "paragraph";

    // metadata schema of every chunk (Story 1, Task 1)
    public static final String DOC_ID = "doc_id";
    public static final String VERSION = "version";
    public static final String SOURCE = "source";
    public static final String PAGE = "page";
    public static final String CHUNK_INDEX = "chunk_index";
    public static final String CHECKSUM = "checksum";
    public static final String EMBEDDING_MODEL = "embedding_model";

    public static final String DOC_TYPE = "doc_type";
    public static final String UPDATED_AT = "updated_at";
    public static final String INGESTED_AT = "ingested_at";
    public static final String UNKNOWN = "UNKNOWN";

    // metadata key set by Spring AI's PDF readers
    public static final String PDF_PAGE_NUMBER = "page_number";

    public static final int INITIAL_VERSION = 1;
}
