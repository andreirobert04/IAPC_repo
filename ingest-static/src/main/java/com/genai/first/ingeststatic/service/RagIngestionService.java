package com.genai.first.ingeststatic.service;

import com.genai.first.ingeststatic.chunking.DocumentIds;
import com.genai.first.ingeststatic.chunking.OverlappingTextSplitter;
import com.genai.first.ingeststatic.config.data.RagConfigData;
import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.exception.RagException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
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
    private final OverlappingTextSplitter textSplitter;
    private final String embeddingModel;

    public RagIngestionService(VectorStore vectorStore,
                               RagConfigData ragConfigData,
                               OverlappingTextSplitter textSplitter,
                               @Value("${spring.ai.ollama.embedding.options.model:unknown}") String embeddingModel) {
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
        this.textSplitter = textSplitter;
        this.embeddingModel = embeddingModel;
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
        int totalChunks = 0;
        for (var pdfResource : pdfResources) {
            var chunks = buildChunks(pdfResource);
            if (chunks.isEmpty()) {
                log.warn("No text extracted from {}, nothing to ingest", pdfResource.getFilename());
                continue;
            }
            // chunk ids are deterministic, so Qdrant upserts them: re-running the same file creates no duplicates
            vectorStore.add(chunks);
            totalChunks += chunks.size();
            var first = chunks.get(0).getMetadata();
            log.info("Ingested {} -> doc_id={}, version={}, {} chunks",
                    first.get(RagConstants.SOURCE), first.get(RagConstants.DOC_ID),
                    first.get(RagConstants.VERSION), chunks.size());
        }
        log.info("Ingested {} chunks from {} PDF(s) into Qdrant (chunk size={} tokens, overlap={} tokens, embedding model={})",
                totalChunks, pdfResources.length, textSplitter.getChunkSize(), textSplitter.getOverlap(), embeddingModel);
    }

    /**
     * Reads one PDF and turns it into chunks carrying the full metadata schema:
     * doc_id, version, source, page, chunk_index, checksum (+ embedding_model, doc_type, updated_at).
     * chunk_index runs over the whole document, so neighbouring chunks can be found later.
     */
    List<Document> buildChunks(Resource pdfResource) {
        var source = Objects.requireNonNull(pdfResource.getFilename(), "PDF resource without a file name");
        var docId = DocumentIds.docId(source);
        var version = RagConstants.INITIAL_VERSION;
        var checksum = sha256Hex(pdfResource);
        var docType = source.contains(".") ? source.substring(0, source.lastIndexOf('.')) : source;
        var updatedAt = ZonedDateTime.now().toLocalDate().toString();

        List<Document> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (var part : getDocumentParts(pdfResource)) {
            var page = part.getMetadata().getOrDefault(RagConstants.PDF_PAGE_NUMBER, 0);
            for (var text : textSplitter.split(part.getText())) {
                Map<String, Object> metadata = new HashMap<>(part.getMetadata());
                metadata.put(RagConstants.DOC_ID, docId);
                metadata.put(RagConstants.VERSION, version);
                metadata.put(RagConstants.SOURCE, source);
                metadata.put(RagConstants.PAGE, page);
                metadata.put(RagConstants.CHUNK_INDEX, chunkIndex);
                metadata.put(RagConstants.CHECKSUM, checksum);
                metadata.put(RagConstants.EMBEDDING_MODEL, embeddingModel);
                metadata.put(RagConstants.DOC_TYPE, docType);
                metadata.put(RagConstants.UPDATED_AT, updatedAt);
                chunks.add(new Document(DocumentIds.chunkId(docId, version, chunkIndex), text, metadata));
                chunkIndex++;
            }
        }
        return chunks;
    }

    private List<Document> getDocumentParts(Resource pdfResource) {
        String mode = ragConfigData.getPdf().getMode();
        return switch (mode != null ? mode.toLowerCase() : "") {
            case RagConstants.PARAGRAPH -> new ParagraphPdfDocumentReader(pdfResource, pdfDocumentReaderConfig).read();
            default -> new PagePdfDocumentReader(pdfResource, pdfDocumentReaderConfig).read();
        };
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
