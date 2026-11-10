package com.genai.first.demo.rag.config.data;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.rag")
public class RagConfigData {
    private boolean forceRebuild;
    private int topK;
    private double similarityThreshold;
    private ChunkProperties chunk = new ChunkProperties();
    private PdfProperties pdf = new PdfProperties();

    @Data
    public static class ChunkProperties {
        private int size;
        private int minChunkSize;
        private int minChunkToEmbed;
        private int maxNumChunks;
        private boolean keepSeparator;
    }

    @Data
    public static class PdfProperties {
        private String mode;
        private String path;
        private int pagesPerDocument;
        private boolean leftAlignment;
        private int numberOfTopTextLinesToDelete;
        private int numberOfBottomTextLinesToDelete;
    }
}
