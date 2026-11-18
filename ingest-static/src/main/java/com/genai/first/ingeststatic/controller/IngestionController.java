package com.genai.first.ingeststatic.controller;

import com.genai.first.ingeststatic.config.data.RagConfigData;
import com.genai.first.ingeststatic.exception.RagException;
import com.genai.first.ingeststatic.service.ChunkInspectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Read-only endpoints of the ingestion service, called by other services through Eureka.
 */
@RestController
@RequestMapping("/api/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final RagConfigData ragConfigData;
    private final ChunkInspectionService chunkInspectionService;

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
}
