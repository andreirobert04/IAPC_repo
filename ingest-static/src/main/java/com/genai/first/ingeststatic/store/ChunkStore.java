package com.genai.first.ingeststatic.store;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Storage of document chunks (Story 1, Tasks 2 + 3). Implemented with Qdrant; tests use an in-memory version.
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

    /**
     * Deletes all chunks of a document by doc_id (Story 1, Task 3).
     * Checks and verifies that no orphaned vectors remain after deletion.
     *
     * @param docId stable document identifier
     * @return number of chunks deleted
     */
    long delete(String docId);

    /**
     * Counts how many chunks are stored in Qdrant for the given docId.
     */
    long count(String docId);

    /**
     * Returns all distinct doc_ids currently stored in the vector store.
     */
    Set<String> findAllDocIds();

    /**
     * Finds point IDs of orphaned vectors (vectors without doc_id or whose doc_id is not in activeDocIds).
     */
    List<String> findOrphanedVectorIds(Set<String> activeDocIds);

    /**
     * Deletes all orphaned vectors and verifies that none remain.
     *
     * @param activeDocIds set of valid/active document identifiers
     * @return number of orphaned vectors deleted
     */
    long deleteOrphanedVectors(Set<String> activeDocIds);
}
