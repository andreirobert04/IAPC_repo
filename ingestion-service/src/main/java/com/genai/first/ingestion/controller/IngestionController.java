package com.genai.first.ingestion.controller;

import com.genai.first.ingestion.config.data.RagConfigData;
import com.genai.first.ingestion.exception.RagException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Read-only endpoints of the ingestion service, called by other services through Eureka.
 */
@RestController
@RequestMapping("/api/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final RagConfigData ragConfigData;

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
}
