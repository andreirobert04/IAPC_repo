package com.genai.first.demo.rag.service;

import com.genai.first.demo.rag.config.data.RagConfigData;
import com.genai.first.demo.rag.config.data.RagConstants;
import com.genai.first.demo.rag.exception.RagException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class RagIngestionService {

    private final VectorStore vectorStore;
    private final RagConfigData ragConfigData;
    private final PdfDocumentReaderConfig pdfDocumentReaderConfig;
    private final TokenTextSplitter tokenTextSplitter;

    public RagIngestionService(VectorStore vectorStore,
                               RagConfigData ragConfigData,
                               TokenTextSplitter tokenTextSplitter) {
        this.vectorStore = vectorStore;
        this.ragConfigData = ragConfigData;
        RagConfigData.PdfProperties pdfProperties = ragConfigData.getPdf();
        this.pdfDocumentReaderConfig = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(pdfProperties.getPagesPerDocument())
                .withPageExtractedTextFormatter(
                        ExtractedTextFormatter.builder()
                                .withLeftAlignment(pdfProperties.isLeftAlignment())
                                .withNumberOfTopTextLinesToDelete(pdfProperties.getNumberOfTopTextLinesToDelete())
                                .withNumberOfBottomTextLinesToDelete(pdfProperties.getNumberOfBottomTextLinesToDelete())
                                .build()
                )
                .build();
        this.tokenTextSplitter = tokenTextSplitter;
    }

    @PostConstruct
    public void initializeVectorStore() throws RagException {
        if (skipIngest()) {
            return;
        }
        var pdfResources = getResources();
        if (pdfResources == null) {
            return;
        }
        ingestDocumentChunksToVectorStore(pdfResources);
    }

    public void deleteBySource(String source) {
        try {
            var b = new FilterExpressionBuilder();
            vectorStore.delete(b.eq(RagConstants.SOURCE, source).build());
            log.info("Deleted source: {} from vector store", source);
        } catch (Exception e) {
            throw new RagException("Error deleting source: " + source + " from vector store!", e);
        }
    }

    private boolean skipIngest() {
        if (ragConfigData.isForceRebuild()) {
            log.info("force-rebuild=true -> Rebuilding documents in vector store");
            return false;
        }
        try {
            var existing = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("a")
                            .topK(1)
                            .similarityThreshold(0.0)
                            .build()
            );
            if (existing != null && !existing.isEmpty()) {
                log.info("Vector store already populated. Skipping ingest. Set app.rag.force-rebuild=true to rebuild");
                return true;
            }
        } catch (Exception e) {
            log.debug("Could not verify existing documents count, proceeding with ingest: {}", e.getMessage());
        }
        return false;
    }

    private Resource[] getResources() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            var pdfPath = ragConfigData.getPdf().getPath();
            var pdfResources = resolver.getResources(pdfPath);
            if (pdfResources.length == 0) {
                log.warn("No PDFs found at {}", pdfPath);
                return null;
            }
            return pdfResources;
        } catch (IOException e) {
            throw new RagException("Could not get pdf resources!", e);
        }
    }

    private void ingestDocumentChunksToVectorStore(Resource[] pdfResources) {
        var documents = getDocuments(pdfResources);
        var chunks = getChunks(documents);
        addChunkIndex(chunks);
        vectorStore.add(chunks);
        log.info("Ingested {} chunks into Qdrant VectorStore", chunks.size());
    }

    private List<Document> getDocuments(Resource[] pdfResources) {
        List<Document> documents = new ArrayList<>();
        for (var pdfResource : pdfResources) {
            var parts = getDocumentParts(pdfResource);
            addMetadata(pdfResource, parts);
            documents.addAll(parts);
        }
        return documents;
    }

    private List<Document> getDocumentParts(Resource pdfResource) {
        String mode = ragConfigData.getPdf().getMode();
        return switch (mode != null ? mode.toLowerCase() : "") {
            case RagConstants.PARAGRAPH -> new ParagraphPdfDocumentReader(pdfResource, pdfDocumentReaderConfig).read();
            default -> new PagePdfDocumentReader(pdfResource, pdfDocumentReaderConfig).read();
        };
    }

    private void addMetadata(Resource pdfResource, List<Document> parts) {
        for (var part : parts) {
            part.getMetadata().putIfAbsent(RagConstants.SOURCE, pdfResource.getFilename());
            part.getMetadata().putIfAbsent(RagConstants.DOC_TYPE,
                    Objects.requireNonNull(pdfResource.getFilename()).substring(0, pdfResource.getFilename().indexOf(".")));
            part.getMetadata().putIfAbsent(RagConstants.UPDATED_AT, ZonedDateTime.now().toLocalDate().toString());
            var checksum = sha256Hex(pdfResource);
            part.getMetadata().put(RagConstants.CHECKSUM, checksum);
        }
    }

    private List<Document> getChunks(List<Document> documents) {
        return tokenTextSplitter.apply(documents);
    }

    private void addChunkIndex(List<Document> chunks) {
        Map<String, Integer> counters = new HashMap<>();
        for (var chunk : chunks) {
            var source = String.valueOf(chunk.getMetadata().getOrDefault(RagConstants.SOURCE, RagConstants.UNKNOWN));
            var index = counters.merge(source, 1, Integer::sum) - 1;
            chunk.getMetadata().put(RagConstants.CHUNK_INDEX, index);
        }
    }

    /**
     * Computes the SHA-256 hex digest of the given resource via DigestInputStream.
     */
    private String sha256Hex(Resource resource) {
        try {
            var messageDigest = newMessageDigest("SHA-256");

            try (InputStream inputStream = resource.getInputStream();
                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, messageDigest)) {
                digestInputStream.transferTo(OutputStream.nullOutputStream());
            }

            return HexFormat.of().formatHex(messageDigest.digest());
        } catch (IOException e) {
            throw new RagException("Error creating sha256Hex for resource " + resource.getFilename(), e);
        }
    }

    private MessageDigest newMessageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RagException("Missing JCA provider for " + algorithm, e);
        }
    }
}
