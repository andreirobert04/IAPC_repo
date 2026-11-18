package com.genai.first.ingeststatic.service;

import com.genai.first.ingeststatic.chunking.DocumentIds;
import com.genai.first.ingeststatic.chunking.OverlappingTextSplitter;
import com.genai.first.ingeststatic.config.data.RagConfigData;
import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.exception.RagException;
import com.genai.first.ingeststatic.reader.PdfTextPageReader;
import com.genai.first.ingeststatic.store.ChunkStore;
import com.genai.first.ingeststatic.store.EmbeddedChunk;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
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
    private final PdfTextPageReader pdfTextPageReader = new PdfTextPageReader();
    private final EmbeddingModel embeddingClient;
    private final ChunkStore chunkStore;
    private final String embeddingModel;

    public RagIngestionService(VectorStore vectorStore,
                               RagConfigData ragConfigData,
                               OverlappingTextSplitter textSplitter,
                               EmbeddingModel embeddingClient,
                               ChunkStore chunkStore,
                               @Value("${spring.ai.ollama.embedding.options.model:unknown}") String embeddingModel) {
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.chunkStore = chunkStore;
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
        var pdfResources = getResources();
        if (pdfResources == null) {
            return;
        }
        if (ragConfigData.isForceRebuild()) {
            log.info("force-rebuild=true -> every PDF is re-ingested, even if unchanged");
        }
        Map<IngestResult, Integer> results = new java.util.EnumMap<>(IngestResult.class);
        for (var pdfResource : pdfResources) {
            results.merge(ingest(pdfResource), 1, Integer::sum);
        }
        log.info("Ingestion finished for {} PDF(s): {} (chunk size={} tokens, overlap={} tokens, embedding model={})",
                pdfResources.length, results, textSplitter.getChunkSize(), textSplitter.getOverlap(), embeddingModel);
    }

    /**
     * Ingests one PDF (Story 1, Tasks 1 + 2):
     * <ol>
     *     <li>an unchanged file (same checksum as the stored version) is skipped;</li>
     *     <li>everything that can fail (reading, chunking, embedding) happens in memory first,
     *     so a failure leaves the stored version untouched;</li>
     *     <li>the new chunks then replace the old ones in a single store request
     *     (new chunks written first, old ones deleted after), so a query never gets an empty result.</li>
     * </ol>
     * Errors are logged and reported as FAILED, so one bad file does not stop the others.
     */
    public IngestResult ingest(Resource pdfResource) {
        var source = pdfResource.getFilename();
        try {
            var docId = DocumentIds.docId(Objects.requireNonNull(source, "PDF resource without a file name"));
            var checksum = sha256Hex(pdfResource);
            var stored = chunkStore.find(docId);

            boolean sameContent = stored.isPresent() && checksum.equals(stored.get().checksum());
            if (sameContent && !ragConfigData.isForceRebuild()) {
                log.info("Unchanged {} (doc_id={}, version={}, {} chunks) -> skipped",
                        source, docId, stored.get().version(), stored.get().chunkCount());
                return IngestResult.UNCHANGED;
            }
            int version = stored.map(d -> sameContent ? d.version() : d.version() + 1)
                    .orElse(RagConstants.INITIAL_VERSION);

            var chunks = buildChunks(pdfResource, version, checksum);
            if (chunks.isEmpty()) {
                log.warn("No text extracted from {}, keeping the stored version (if any)", source);
                return IngestResult.FAILED;
            }
            var embedded = embed(chunks);
            chunkStore.replace(docId, embedded);

            var result = stored.isEmpty() ? IngestResult.NEW : IngestResult.UPDATED;
            log.info("{} {} -> doc_id={}, version={}, {} chunks{}", result == IngestResult.NEW ? "Ingested" : "Updated",
                    source, docId, version, chunks.size(),
                    stored.map(d -> " (previous: version " + d.version() + ", " + d.chunkCount() + " chunks)").orElse(""));
            return result;
        } catch (Exception e) {
            log.error("Ingestion of {} failed, the stored version (if any) is left unchanged: {}", source, e.getMessage(), e);
            return IngestResult.FAILED;
        }
    }

    private List<EmbeddedChunk> embed(List<Document> chunks) {
        var embeddings = embeddingClient.embed(chunks.stream().map(Document::getText).toList());
        if (embeddings.size() != chunks.size()) {
            throw new RagException("Embedding model returned " + embeddings.size() + " vectors for " + chunks.size() + " chunks");
        }
        List<EmbeddedChunk> embedded = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            embedded.add(new EmbeddedChunk(chunks.get(i), embeddings.get(i)));
        }
        return embedded;
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

    /**
     * Reads one PDF and turns it into chunks carrying the full metadata schema:
     * doc_id, version, source, page, chunk_index, checksum (+ embedding_model, doc_type, updated_at).
     * chunk_index runs over the whole document, so neighbouring chunks can be found later.
     */
    List<Document> buildChunks(Resource pdfResource) {
        return buildChunks(pdfResource, RagConstants.INITIAL_VERSION, sha256Hex(pdfResource));
    }

    List<Document> buildChunks(Resource pdfResource, int version, String checksum) {
        var source = Objects.requireNonNull(pdfResource.getFilename(), "PDF resource without a file name");
        var docId = DocumentIds.docId(source);
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
        return switch (mode != null ? mode.toLowerCase() : RagConstants.TEXT) {
            // keeps the visual layout (multi-column pages get mixed)
            case RagConstants.PAGE_LAYOUT -> new PagePdfDocumentReader(pdfResource, pdfDocumentReaderConfig).read();
            // needs a PDF with a table of contents (bookmarks)
            case RagConstants.PARAGRAPH -> new ParagraphPdfDocumentReader(pdfResource, pdfDocumentReaderConfig).read();
            // default: content-stream order, safe for multi-column pages and tables
            default -> pdfTextPageReader.read(pdfResource);
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
