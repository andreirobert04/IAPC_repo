package com.genai.first.ingestion.config;

import com.genai.first.ingestion.config.data.RagConfigData;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TokenTextSplitter bean, moved unchanged from the former AdvancedRagConfiguration
 * (it is only needed by the ingestion side).
 */
@Configuration
public class IngestionConfiguration {

    @Bean
    public TokenTextSplitter tokenTextSplitter(RagConfigData ragConfigData) {
        RagConfigData.ChunkProperties chunkProperties = ragConfigData.getChunk();
        if (chunkProperties != null && chunkProperties.getSize() > 0) {
            return new TokenTextSplitter(
                    chunkProperties.getSize(),
                    chunkProperties.getMinChunkSize(),
                    chunkProperties.getMinChunkToEmbed(),
                    chunkProperties.getMaxNumChunks(),
                    chunkProperties.isKeepSeparator()
            );
        }
        return new TokenTextSplitter();
    }
}
