package com.genai.first.ingeststatic.config;

import com.genai.first.ingeststatic.chunking.OverlappingTextSplitter;
import com.genai.first.ingeststatic.config.data.RagConfigData;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestionConfiguration {

    /**
     * Chunk size and overlap (in tokens) come from app.rag.chunk.* (see application.yaml / README).
     */
    @Bean
    public OverlappingTextSplitter overlappingTextSplitter(RagConfigData ragConfigData) {
        var chunk = ragConfigData.getChunk();
        return new OverlappingTextSplitter(chunk.getSize(), chunk.getOverlap(), chunk.getMinChunkLength());
    }
}
