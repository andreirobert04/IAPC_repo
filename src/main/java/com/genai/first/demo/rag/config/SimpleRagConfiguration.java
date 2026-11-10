package com.genai.first.demo.rag.config;

import com.genai.first.demo.rag.config.data.RagConfigData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SimpleRagConfiguration {

    private static final String DEFAULT_FILES_PATH = "classpath:rag/mini-employee-handbook/files/*.md";
    private static final String DEFAULT_VECTOR_STORE_PATH = "src/main/resources/rag/mini-employee-handbook/vector-store/vectorstore.json";

    private final RagConfigData ragConfigData;
    private final ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();

    @Bean
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel,
                                               TokenTextSplitter tokenTextSplitter) {
        var simpleVectorStore = SimpleVectorStore.builder(embeddingModel).build();
        var vectorStoreFile = new File(DEFAULT_VECTOR_STORE_PATH);

        if (vectorStoreFile.exists() && !ragConfigData.isForceRebuild()) {
            log.info("Vector store file exists, loading from: {}", vectorStoreFile.getAbsolutePath());
            try {
                simpleVectorStore.load(vectorStoreFile);
                return simpleVectorStore;
            } catch (Exception e) {
                log.warn("Could not load vector store from file: {}", e.getMessage());
            }
        }

        try {
            var documents = loadDocuments();
            if (!documents.isEmpty()) {
                log.info("Building simple vector store and saving to: {}", vectorStoreFile.getAbsolutePath());
                ensureParentDirectoryExists(vectorStoreFile);
                var chunks = tokenTextSplitter.apply(documents);
                simpleVectorStore.add(chunks);
                simpleVectorStore.save(vectorStoreFile);
            } else {
                log.info("No documents found at {}, skipping SimpleVectorStore file generation.", DEFAULT_FILES_PATH);
            }
        } catch (Exception e) {
            log.warn("Skipping simple vector store file ingest (directory {} not present).", DEFAULT_FILES_PATH);
        }

        return simpleVectorStore;
    }

    private List<Document> loadDocuments() {
        List<Document> documents = new ArrayList<>();
        try {
            Resource[] resources = resourcePatternResolver.getResources(DEFAULT_FILES_PATH);
            if (resources == null || resources.length == 0) {
                return documents;
            }

            for (var resource : resources) {
                if (resource.exists()) {
                    var reader = new TextReader(resource);
                    var category = extractCategory(resource);
                    reader.getCustomMetadata().put("category", category);
                    reader.getCustomMetadata().put("access_level", "public");
                    reader.getCustomMetadata().put("version", "2026.01");
                    documents.addAll(reader.read());
                }
            }
        } catch (Exception e) {
            log.debug("No markdown files found: {}", e.getMessage());
        }

        return documents;
    }

    private String extractCategory(Resource resource) {
        String filename = resource.getFilename();
        if (filename != null && filename.endsWith(".md")) {
            return filename.substring(0, filename.length() - 3);
        }
        return filename != null ? filename : "unknown";
    }

    private void ensureParentDirectoryExists(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            if (parent.mkdirs()) {
                log.debug("Created parent directories for vector store: {}", parent.getAbsolutePath());
            }
        }
    }
}
