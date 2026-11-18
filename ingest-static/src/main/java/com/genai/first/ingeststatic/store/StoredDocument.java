package com.genai.first.ingeststatic.store;

/**
 * What the vector store currently holds for one document.
 */
public record StoredDocument(String docId, int version, String checksum, int chunkCount) {
}
