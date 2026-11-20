package com.genai.first.ingeststatic.manifest;

import java.util.List;

/**
 * Full ingestion manifest payload (Story 1, Task 4),
 * containing active documents currently stored and the complete chronological log history.
 */
public record IngestionManifest(
        String activeEmbeddingModel,
        int totalDocuments,
        int totalChunks,
        List<ManifestEntry> documents,
        List<ManifestEntry> history
) {
}
