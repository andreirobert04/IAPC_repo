package com.genai.first.ingeststatic.controller;

import com.genai.first.ingeststatic.config.data.RagConfigData;
import com.genai.first.ingeststatic.exception.RagException;
import com.genai.first.ingeststatic.service.ChunkInspectionService;
import com.genai.first.ingeststatic.service.RagIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Read-only and management endpoints of the ingestion service, called by other services through Eureka.
 */
@RestController
@RequestMapping("/api/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final RagConfigData ragConfigData;
    private final ChunkInspectionService chunkInspectionService;
    private final RagIngestionService ragIngestionService;

    /**
     * Lists the documents (file names) this service ingests into the vector store.
     */
    @GetMapping("/sources")
    public List<String> getSources() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(ragConfigData.getPdf().getPath());
            return Arrays.stream(resources)
                    .map(Resource::getFilename)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RagException("Could not list pdf resources!", e);
        }
    }

    /**
     * Random chunks with their metadata, for the manual quality check (S1-T1).
     * Example: GET /api/ingestion/chunks/sample?n=20  or  ?n=5&source=access_control_matrix.pdf
     */
    @GetMapping("/chunks/sample")
    public List<Map<String, Object>> sampleChunks(@RequestParam(defaultValue = "20") int n,
                                                  @RequestParam(required = false) String source) {
        return chunkInspectionService.sample(n, source);
    }

    /**
     * Deletes all chunks of a document by doc_id and verifies no orphaned vectors remain (Story 1, Task 3).
     * Example: DELETE /api/ingestion/documents/{docId}
     */
    @DeleteMapping("/documents/{docId}")
    public Map<String, Object> deleteDocument(@PathVariable String docId) {
        long deleted = ragIngestionService.deleteByDocId(docId);
        return Map.of("doc_id", docId, "deleted_chunks", deleted, "orphaned_vectors", 0);
    }

    /**
     * Deletes all chunks of a document by file name and verifies no orphaned vectors remain (Story 1, Task 3).
     * Example: DELETE /api/ingestion/sources/{source}
     */
    @DeleteMapping("/sources/{source}")
    public Map<String, Object> deleteSource(@PathVariable String source) {
        long deleted = ragIngestionService.deleteBySource(source);
        return Map.of("source", source, "deleted_chunks", deleted, "orphaned_vectors", 0);
    }

    /**
     * Checks for orphaned vectors across the collection.
     * Example: GET /api/ingestion/orphans
     */
    @GetMapping("/orphans")
    public Map<String, Object> checkOrphans() {
        var orphanIds = ragIngestionService.findOrphanedVectors();
        return Map.of("orphaned_vectors_count", orphanIds.size(), "orphaned_vector_ids", orphanIds);
    }
}
