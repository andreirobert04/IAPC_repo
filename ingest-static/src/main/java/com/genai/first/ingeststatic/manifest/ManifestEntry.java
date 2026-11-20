package com.genai.first.ingeststatic.manifest;

import com.genai.first.ingeststatic.service.IngestResult;

/**
 * Manifest record of an ingested document (Story 1, Task 4):
 * log of what was ingested (doc_id, source, version, checksum, chunk_count),
 * when (ingested_at ISO-8601 timestamp),
 * and with which embedding model version (embedding_model).
 */
public record ManifestEntry(
        String docId,
        String source,
        int version,
        String checksum,
        int chunkCount,
        String embeddingModel,
        String ingestedAt,
        IngestResult status
) {
}
