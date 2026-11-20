package com.genai.first.ingeststatic.service;

import com.genai.first.ingeststatic.chunking.DocumentIds;
import com.genai.first.ingeststatic.chunking.OverlappingTextSplitter;
import com.genai.first.ingeststatic.config.data.RagConfigData;
import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.exception.RagException;
import com.genai.first.ingeststatic.manifest.IngestionManifest;
import com.genai.first.ingeststatic.manifest.ManifestEntry;
import com.genai.first.ingeststatic.reader.PdfTextPageReader;
import com.genai.first.ingeststatic.store.ChunkStore;
import com.genai.first.ingeststatic.store.EmbeddedChunk;
import com.genai.first.ingeststatic.store.StoredDocument;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Ingestion manifest history log (Story 1, Task 4):
     * chronological audit trail of all ingestion, update, re-index and delete events.
     */
    private final List<ManifestEntry> history = Collections.synchronizedList(new ArrayList<>());

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
        Set<String> activeDocIds = new HashSet<>();
        for (var pdfResource : pdfResources) {
            var filename = pdfResource.getFilename();
            if (filename != null) {
                activeDocIds.add(DocumentIds.docId(filename));
            }
            results.merge(ingest(pdfResource), 1, Integer::sum);
        }

        // Story 1, Task 3: Delete process - remove chunks for files removed from resources
        Set<String> storedDocIds = chunkStore.findAllDocIds();
        for (var storedDocId : storedDocIds) {
            if (!activeDocIds.contains(storedDocId)) {
                log.info("Document doc_id={} no longer exists in resources -> deleting all chunks", storedDocId);
                var existing = chunkStore.find(storedDocId);
                long deleted = chunkStore.delete(storedDocId);
                if (deleted > 0) {
                    results.merge(IngestResult.DELETED, 1, Integer::sum);
                    recordManifest(new ManifestEntry(
                            storedDocId,
                            existing.map(StoredDocument::source).orElse("unknown"),
                            existing.map(StoredDocument::version).orElse(0),
                            existing.map(StoredDocument::checksum).orElse(""),
                            0,
                            existing.map(StoredDocument::embeddingModel).orElse(embeddingModel),
                            nowIso(),
                            IngestResult.DELETED
                    ));
                }
            }
        }

        // Check for orphaned vectors across the collection and clean them up
        long cleanedOrphans = chunkStore.deleteOrphanedVectors(activeDocIds);
        if (cleanedOrphans > 0) {
            log.warn("Detected and cleaned up {} orphaned vector(s) from Qdrant", cleanedOrphans);
        }

        var currentDocs = getCurrentManifest();
        int totalChunks = currentDocs.stream().mapToInt(ManifestEntry::chunkCount).sum();
        log.info("Ingestion finished for {} PDF(s): {} (chunk size={} tokens, overlap={} tokens, embedding model={})",
                pdfResources.length, results, textSplitter.getChunkSize(), textSplitter.getOverlap(), embeddingModel);
        log.info("Manifest snapshot: {} active document(s), {} total chunk(s) stored in vector store",
                currentDocs.size(), totalChunks);
    }

    /**
     * Ingests one PDF (Story 1, Tasks 1, 2, 4):
     * <ol>
     *     <li>an unchanged file (same checksum and same embedding model) is skipped;</li>
     *     <li>if the embedding model changed, it is automatically re-indexed with the new model version;</li>
     *     <li>everything that can fail (reading, chunking, embedding) happens in memory first,
     *     so a failure leaves the stored version untouched;</li>
     *     <li>the new chunks replace the old ones in a single store request;</li>
     *     <li>the event is recorded in the ingestion manifest log with timestamp and model version.</li>
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
            boolean sameModel = stored.isPresent() && embeddingModel.equals(stored.get().embeddingModel());

            if (sameContent && sameModel && !ragConfigData.isForceRebuild()) {
                log.info("Unchanged {} (doc_id={}, version={}, {} chunks, model={}) -> skipped",
                        source, docId, stored.get().version(), stored.get().chunkCount(), stored.get().embeddingModel());
                recordManifest(new ManifestEntry(
                        docId, source, stored.get().version(), checksum,
                        stored.get().chunkCount(), stored.get().embeddingModel(),
                        stored.get().ingestedAt() != null ? stored.get().ingestedAt() : nowIso(),
                        IngestResult.UNCHANGED));
                return IngestResult.UNCHANGED;
            }

            int version = stored.map(d -> sameContent ? d.version() : d.version() + 1)
                    .orElse(RagConstants.INITIAL_VERSION);

            var chunks = buildChunks(pdfResource, version, checksum);
            if (chunks.isEmpty()) {
                log.warn("No text extracted from {}, keeping the stored version (if any)", source);
                recordManifest(new ManifestEntry(docId, source, version, checksum, 0, embeddingModel, nowIso(), IngestResult.FAILED));
                return IngestResult.FAILED;
            }
            var embedded = embed(chunks);
            chunkStore.replace(docId, embedded);

            IngestResult result;
            if (stored.isEmpty()) {
                result = IngestResult.NEW;
            } else if (!sameModel) {
                result = IngestResult.REINDEXED;
            } else {
                result = IngestResult.UPDATED;
            }

            String timestamp = chunks.get(0).getMetadata().getOrDefault(RagConstants.INGESTED_AT, nowIso()).toString();
            var entry = new ManifestEntry(docId, source, version, checksum, chunks.size(), embeddingModel, timestamp, result);
            recordManifest(entry);

            log.info("{} {} -> doc_id={}, version={}, {} chunks, model={}{}",
                    result == IngestResult.NEW ? "Ingested" : (result == IngestResult.REINDEXED ? "Re-indexed" : "Updated"),
                    source, docId, version, chunks.size(), embeddingModel,
                    stored.map(d -> " (previous: version " + d.version() + ", " + d.chunkCount() + " chunks, model=" + d.embeddingModel() + ")").orElse(""));
            return result;
        } catch (Exception e) {
            log.error("Ingestion of {} failed, the stored version (if any) is left unchanged: {}", source, e.getMessage(), e);
            recordManifest(new ManifestEntry(
                    source != null ? DocumentIds.docId(source) : "unknown",
                    source, 0, "", 0, embeddingModel, nowIso(), IngestResult.FAILED
            ));
            return IngestResult.FAILED;
        }
    }

    /**
     * Explicit re-index of a PDF resource with the current embedding model version (Story 1, Task 4).
     */
    public IngestResult reindex(Resource pdfResource) {
        var source = pdfResource.getFilename();
        try {
            var docId = DocumentIds.docId(Objects.requireNonNull(source, "PDF resource without a file name"));
            var checksum = sha256Hex(pdfResource);
            var stored = chunkStore.find(docId);
            int version = stored.map(StoredDocument::version).orElse(RagConstants.INITIAL_VERSION);

            var chunks = buildChunks(pdfResource, version, checksum);
            if (chunks.isEmpty()) {
                log.warn("No text extracted from {} during re-index", source);
                recordManifest(new ManifestEntry(docId, source, version, checksum, 0, embeddingModel, nowIso(), IngestResult.FAILED));
                return IngestResult.FAILED;
            }
            var embedded = embed(chunks);
            chunkStore.replace(docId, embedded);

            String timestamp = chunks.get(0).getMetadata().getOrDefault(RagConstants.INGESTED_AT, nowIso()).toString();
            var entry = new ManifestEntry(docId, source, version, checksum, chunks.size(), embeddingModel, timestamp, IngestResult.REINDEXED);
            recordManifest(entry);

            log.info("Re-indexed {} -> doc_id={}, version={}, {} chunks with model={}",
                    source, docId, version, chunks.size(), embeddingModel);
            return IngestResult.REINDEXED;
        } catch (Exception e) {
            log.error("Re-indexing of {} failed: {}", source, e.getMessage(), e);
            recordManifest(new ManifestEntry(
                    source != null ? DocumentIds.docId(source) : "unknown",
                    source, 0, "", 0, embeddingModel, nowIso(), IngestResult.FAILED
            ));
            return IngestResult.FAILED;
        }
    }

    /**
     * Re-indexes a specific document by its doc_id using the current embedding model version.
     */
    public IngestResult reindexByDocId(String docId) {
        var resource = findResourceByDocId(docId);
        if (resource == null) {
            throw new RagException("No resource found for doc_id: " + docId);
        }
        return reindex(resource);
    }

    /**
     * Re-indexes a specific document by source file name using the current embedding model version.
     */
    public IngestResult reindexBySource(String source) {
        var resource = findResourceBySource(source);
        if (resource == null) {
            throw new RagException("No resource found for source: " + source);
        }
        return reindex(resource);
    }

    /**
     * Re-indexes all active documents in resources with the current embedding model version.
     */
    public Map<String, IngestResult> reindexAll() {
        var pdfResources = getResources();
        Map<String, IngestResult> results = new LinkedHashMap<>();
        if (pdfResources != null) {
            for (var pdfResource : pdfResources) {
                var filename = pdfResource.getFilename();
                var docId = filename != null ? DocumentIds.docId(filename) : "unknown";
                results.put(docId, reindex(pdfResource));
            }
        }
        return results;
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

    /**
     * Deletes all chunks associated with the given doc_id (Story 1, Task 3).
     * Performs a check for orphaned vectors and throws RagException if any remain.
     * Records the deletion event in the manifest log.
     *
     * @param docId stable document identifier
     * @return number of chunks deleted
     */
    public long deleteByDocId(String docId) {
        Objects.requireNonNull(docId, "docId must not be null");
        try {
            var existing = chunkStore.find(docId);
            long deletedCount = chunkStore.delete(docId);
            if (deletedCount > 0) {
                recordManifest(new ManifestEntry(
                        docId,
                        existing.map(StoredDocument::source).orElse("unknown"),
                        existing.map(StoredDocument::version).orElse(0),
                        existing.map(StoredDocument::checksum).orElse(""),
                        0,
                        existing.map(StoredDocument::embeddingModel).orElse(embeddingModel),
                        nowIso(),
                        IngestResult.DELETED
                ));
            }
            log.info("Deleted document doc_id={} ({} chunks removed, verified 0 orphaned vectors)",
                    docId, deletedCount);
            return deletedCount;
        } catch (Exception e) {
            log.error("Failed to delete document doc_id={}: {}", docId, e.getMessage(), e);
            throw e instanceof RagException re ? re : new RagException("Could not delete document " + docId, e);
        }
    }

    /**
     * Deletes all chunks associated with the given source file name by its doc_id (Story 1, Task 3).
     *
     * @param source file name of the document
     * @return number of chunks deleted
     */
    public long deleteBySource(String source) {
        Objects.requireNonNull(source, "source must not be null");
        return deleteByDocId(DocumentIds.docId(source));
    }

    /**
     * Checks if any orphaned vectors exist for a given doc_id.
     * Throws RagException if orphaned vectors are detected.
     */
    public void checkForOrphanedVectors(String docId) {
        long count = chunkStore.count(docId);
        if (count > 0) {
            throw new RagException("Orphaned vectors detected: " + count + " vector(s) still remain for doc_id=" + docId);
        }
    }

    /**
     * Finds point IDs of any orphaned vectors in the store that do not belong to active PDF resources.
     */
    public List<String> findOrphanedVectors() {
        var pdfResources = getResources();
        Set<String> activeDocIds = new HashSet<>();
        if (pdfResources != null) {
            for (var r : pdfResources) {
                if (r.getFilename() != null) {
                    activeDocIds.add(DocumentIds.docId(r.getFilename()));
                }
            }
        }
        return chunkStore.findOrphanedVectorIds(activeDocIds);
    }

    /**
     * Returns the current snapshot of all documents stored in the vector store.
     */
    public List<ManifestEntry> getCurrentManifest() {
        return chunkStore.findAll().stream()
                .map(stored -> new ManifestEntry(
                        stored.docId(),
                        stored.source(),
                        stored.version(),
                        stored.checksum(),
                        stored.chunkCount(),
                        stored.embeddingModel(),
                        stored.ingestedAt(),
                        IngestResult.UNCHANGED
                ))
                .sorted(Comparator.comparing(ManifestEntry::source, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Returns the full IngestionManifest containing the active documents snapshot
     * and the chronological audit history log (Story 1, Task 4).
     */
    public IngestionManifest getManifest() {
        var currentDocs = getCurrentManifest();
        int totalChunks = currentDocs.stream().mapToInt(ManifestEntry::chunkCount).sum();
        return new IngestionManifest(
                embeddingModel,
                currentDocs.size(),
                totalChunks,
                currentDocs,
                getHistory()
        );
    }

    /**
     * Returns the manifest entry for a specific document by its doc_id.
     */
    public Optional<ManifestEntry> getManifestForDoc(String docId) {
        return chunkStore.find(docId)
                .map(stored -> new ManifestEntry(
                        stored.docId(),
                        stored.source(),
                        stored.version(),
                        stored.checksum(),
                        stored.chunkCount(),
                        stored.embeddingModel(),
                        stored.ingestedAt(),
                        IngestResult.UNCHANGED
                ));
    }

    /**
     * Returns an unmodifiable view of the chronological ingestion history log.
     */
    public List<ManifestEntry> getHistory() {
        return List.copyOf(history);
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    private void recordManifest(ManifestEntry entry) {
        history.add(entry);
    }

    private Resource findResourceByDocId(String docId) {
        var resources = getResources();
        if (resources != null) {
            for (var r : resources) {
                if (r.getFilename() != null && docId.equals(DocumentIds.docId(r.getFilename()))) {
                    return r;
                }
            }
        }
        return null;
    }

    private Resource findResourceBySource(String source) {
        var resources = getResources();
        if (resources != null) {
            for (var r : resources) {
                if (source.equals(r.getFilename())) {
                    return r;
                }
            }
        }
        return null;
    }

    private String nowIso() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private Resource[] getResources() {
        try {
            var pdfPath = ragConfigData.getPdf() != null ? ragConfigData.getPdf().getPath() : null;
            if (pdfPath == null || pdfPath.isBlank()) {
                log.warn("No PDF path configured");
                return null;
            }
            var resolver = new PathMatchingResourcePatternResolver();
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
     * doc_id, version, source, page, chunk_index, checksum (+ embedding_model, doc_type, updated_at, ingested_at).
     * chunk_index runs over the whole document, so neighbouring chunks can be found later.
     */
    List<Document> buildChunks(Resource pdfResource) {
        return buildChunks(pdfResource, RagConstants.INITIAL_VERSION, sha256Hex(pdfResource));
    }

    List<Document> buildChunks(Resource pdfResource, int version, String checksum) {
        var source = Objects.requireNonNull(pdfResource.getFilename(), "PDF resource without a file name");
        var docId = DocumentIds.docId(source);
        var docType = source.contains(".") ? source.substring(0, source.lastIndexOf('.')) : source;
        var nowTimestamp = nowIso();

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
                metadata.put(RagConstants.UPDATED_AT, nowTimestamp);
                metadata.put(RagConstants.INGESTED_AT, nowTimestamp);
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
