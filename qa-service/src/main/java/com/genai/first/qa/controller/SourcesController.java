package com.genai.first.qa.controller;

import com.genai.first.qa.client.IngestionServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the list of indexed documents; the data comes from ingestion-service via Eureka.
 */
@RestController
@RequestMapping("/api/internal-qa")
@RequiredArgsConstructor
public class SourcesController {

    private final IngestionServiceClient ingestionServiceClient;

    @GetMapping("/sources")
    public List<String> getSources() {
        return ingestionServiceClient.getSources();
    }
}
