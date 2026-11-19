package com.genai.first.ingeststatic.store;

import java.util.List;
import java.util.Optional;

/**
 * Storage of document chunks (Story 1, Task 2). Implemented with Qdrant; tests use an in-memory version.
 */
public interface ChunkStore {

    /**
     * Current version, checksum and chunk count of a document, or empty if it is not stored yet.
     */
    Optional<StoredDocument> find(String docId);

    /**
     * Replaces all chunks of a document with the given ones in a single request:
     * the new chunks are written first, then every other chunk of the same doc_id is deleted.
     */
    void replace(String docId, List<EmbeddedChunk> chunks);
}
