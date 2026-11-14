package com.genai.first.retrievalpost.config.data;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Retrieval settings used by the QA side (the chunk/pdf settings live in ingestion-service).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.rag")
public class RagConfigData {
    private int topK;
    private double similarityThreshold;
}
