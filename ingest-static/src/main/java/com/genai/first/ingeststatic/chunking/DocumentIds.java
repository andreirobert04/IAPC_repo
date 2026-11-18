package com.genai.first.ingeststatic.chunking;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stable identifiers (Story 1, Task 1).
 * <ul>
 *     <li>doc_id depends only on the source name, so it stays the same when the file content changes
 *     (the update process replaces the chunks of the same doc_id).</li>
 *     <li>chunk id depends on doc_id + version + chunk_index, so ingesting the same file again
 *     overwrites the same points in Qdrant (upsert) instead of creating duplicates.</li>
 * </ul>
 */
public final class DocumentIds {

    private DocumentIds() {
    }

    public static String docId(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        return uuid("doc:" + source);
    }

    public static String chunkId(String docId, int version, int chunkIndex) {
        return uuid("chunk:" + docId + ":" + version + ":" + chunkIndex);
    }

    private static String uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
