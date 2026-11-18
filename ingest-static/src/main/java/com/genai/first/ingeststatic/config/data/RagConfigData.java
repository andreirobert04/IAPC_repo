package com.genai.first.ingeststatic.config.data;

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
        /** max tokens per chunk */
        private int size = 400;
        /** tokens repeated from the end of the previous chunk */
        private int overlap = 50;
        /** chunks shorter than this many characters are not embedded */
        private int minChunkLength = 20;
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
