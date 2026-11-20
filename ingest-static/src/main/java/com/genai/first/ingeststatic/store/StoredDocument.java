package com.genai.first.ingeststatic.store;

/**
 * What the vector store currently holds for one document.
 */
public record StoredDocument(
        String docId,
        String source,
        int version,
        String checksum,
        int chunkCount,
        String embeddingModel,
        String ingestedAt
) {
    public StoredDocument(String docId, int version, String checksum, int chunkCount) {
        this(docId, null, version, checksum, chunkCount, null, null);
    }
}
