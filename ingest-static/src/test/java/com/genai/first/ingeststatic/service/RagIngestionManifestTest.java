package com.genai.first.ingeststatic.service;

import com.genai.first.ingeststatic.chunking.DocumentIds;
import com.genai.first.ingeststatic.chunking.OverlappingTextSplitter;
import com.genai.first.ingeststatic.config.data.RagConfigData;
import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.exception.RagException;
import com.genai.first.ingeststatic.manifest.ManifestEntry;
import com.genai.first.ingeststatic.store.InMemoryChunkStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 1, Task 4 - Ingestion manifest and re-index support:
 * log of what was ingested, when, and with which embedding model version.
 */
class RagIngestionManifestTest {

    private static final ClassPathResource V1 = new ClassPathResource("pdf/v1/backup_policy.pdf");
    private static final ClassPathResource V2 = new ClassPathResource("pdf/v2/backup_policy.pdf");
    private static final String DOC_ID = DocumentIds.docId("backup_policy.pdf");
    private static final String MODEL_V1 = "nomic-embed-text:v1";
    private static final String MODEL_V2 = "nomic-embed-text:v2";

    private final InMemoryChunkStore store = new InMemoryChunkStore();
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final RagConfigData config = new RagConfigData();

    @BeforeEach
    void setUp() {
        config.getPdf().setMode(RagConstants.TEXT);
        config.getPdf().setPagesPerDocument(1);
        config.getPdf().setPath("classpath:pdf/**/*.pdf");
        when(embeddingModel.embed(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).stream().map(text -> new float[]{0.1f, 0.2f, 0.3f}).toList());
    }

    private RagIngestionService createService(String model) {
        return new RagIngestionService(mock(VectorStore.class), config,
                new OverlappingTextSplitter(400, 50, 20), embeddingModel, store, model);
    }

    @Test
    void manifestRecordsNewIngestionWithModelAndTimestamp() {
        var service = createService(MODEL_V1);

        assertThat(service.ingest(V1)).isEqualTo(IngestResult.NEW);

        var manifest = service.getManifest();
        assertThat(manifest.activeEmbeddingModel()).isEqualTo(MODEL_V1);
        assertThat(manifest.totalDocuments()).isEqualTo(1);
        assertThat(manifest.totalChunks()).isEqualTo(service.buildChunks(V1).size());

        var docEntry = manifest.documents().get(0);
        assertThat(docEntry.docId()).isEqualTo(DOC_ID);
        assertThat(docEntry.source()).isEqualTo("backup_policy.pdf");
        assertThat(docEntry.version()).isEqualTo(1);
        assertThat(docEntry.embeddingModel()).isEqualTo(MODEL_V1);
        assertThat(docEntry.chunkCount()).isEqualTo(manifest.totalChunks());
        assertThat(docEntry.ingestedAt()).isNotBlank();

        assertThat(manifest.history()).hasSize(1);
        assertThat(manifest.history().get(0).status()).isEqualTo(IngestResult.NEW);
    }

    @Test
    void manifestRecordsUnchangedFileAsSkipped() {
        var service = createService(MODEL_V1);
        service.ingest(V1);

        assertThat(service.ingest(V1)).isEqualTo(IngestResult.UNCHANGED);

        verify(embeddingModel, times(1)).embed(anyList()); // second run did not embed
        var history = service.getHistory();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).status()).isEqualTo(IngestResult.NEW);
        assertThat(history.get(1).status()).isEqualTo(IngestResult.UNCHANGED);
        assertThat(history.get(1).embeddingModel()).isEqualTo(MODEL_V1);
    }

    @Test
    void manifestRecordsUpdatedFileWhenChecksumChanges() {
        var service = createService(MODEL_V1);
        service.ingest(V1);

        assertThat(service.ingest(V2)).isEqualTo(IngestResult.UPDATED);

        var docEntry = service.getManifestForDoc(DOC_ID).orElseThrow();
        assertThat(docEntry.version()).isEqualTo(2);
        assertThat(docEntry.chunkCount()).isEqualTo(service.buildChunks(V2).size());

        var history = service.getHistory();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).status()).isEqualTo(IngestResult.NEW);
        assertThat(history.get(1).status()).isEqualTo(IngestResult.UPDATED);
    }

    @Test
    void embeddingModelChangeTriggersAutomaticReindexing() {
        var serviceV1 = createService(MODEL_V1);
        serviceV1.ingest(V1);
        assertThat(store.find(DOC_ID).orElseThrow().embeddingModel()).isEqualTo(MODEL_V1);

        // Service restart with upgraded embedding model version
        var serviceV2 = createService(MODEL_V2);

        // Same PDF content (checksum unchanged), but embedding model changed:
        assertThat(serviceV2.ingest(V1)).isEqualTo(IngestResult.REINDEXED);

        verify(embeddingModel, times(2)).embed(anyList()); // re-embedded
        var stored = store.find(DOC_ID).orElseThrow();
        assertThat(stored.version()).isEqualTo(1); // version stays the same
        assertThat(stored.embeddingModel()).isEqualTo(MODEL_V2);

        var history = serviceV2.getHistory();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).status()).isEqualTo(IngestResult.REINDEXED);
        assertThat(history.get(0).embeddingModel()).isEqualTo(MODEL_V2);
    }

    @Test
    void explicitReindexByResourceForcesReembedding() {
        var service = createService(MODEL_V1);
        service.ingest(V1);

        assertThat(service.reindex(V1)).isEqualTo(IngestResult.REINDEXED);

        verify(embeddingModel, times(2)).embed(anyList());
        var history = service.getHistory();
        assertThat(history).hasSize(2);
        assertThat(history.get(1).status()).isEqualTo(IngestResult.REINDEXED);
    }

    @Test
    void manifestRecordsDeletedDocument() {
        var service = createService(MODEL_V1);
        service.ingest(V1);
        assertThat(service.getManifest().totalDocuments()).isEqualTo(1);

        service.deleteByDocId(DOC_ID);

        assertThat(service.getManifest().totalDocuments()).isZero();
        var history = service.getHistory();
        assertThat(history).hasSize(2);
        var deleteEntry = history.get(1);
        assertThat(deleteEntry.status()).isEqualTo(IngestResult.DELETED);
        assertThat(deleteEntry.docId()).isEqualTo(DOC_ID);
        assertThat(deleteEntry.chunkCount()).isZero();
    }

    @Test
    void getManifestForDocReturnsCorrectEntry() {
        var service = createService(MODEL_V1);
        service.ingest(V1);

        var entry = service.getManifestForDoc(DOC_ID);
        assertThat(entry).isPresent();
        assertThat(entry.get().source()).isEqualTo("backup_policy.pdf");
        assertThat(entry.get().embeddingModel()).isEqualTo(MODEL_V1);

        assertThat(service.getManifestForDoc("non-existent-doc-id")).isEmpty();
    }

    @Test
    void reindexNonExistentResourceThrowsException() {
        var service = createService(MODEL_V1);

        assertThatThrownBy(() -> service.reindexByDocId("missing-id"))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("No resource found");

        assertThatThrownBy(() -> service.reindexBySource("missing.pdf"))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("No resource found");
    }
}
