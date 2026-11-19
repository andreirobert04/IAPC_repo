package com.genai.first.ingeststatic.service;

import com.genai.first.ingeststatic.chunking.DocumentIds;
import com.genai.first.ingeststatic.chunking.OverlappingTextSplitter;
import com.genai.first.ingeststatic.config.data.RagConfigData;
import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.store.InMemoryChunkStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 1, Task 2 - update process. Same file name (same doc_id), two different contents:
 * v1 has 3 pages, v2 has 1 page (so the update must also remove the extra chunks).
 */
class RagIngestionUpdateTest {

    private static final ClassPathResource V1 = new ClassPathResource("pdf/v1/backup_policy.pdf");
    private static final ClassPathResource V2 = new ClassPathResource("pdf/v2/backup_policy.pdf");
    private static final String DOC_ID = DocumentIds.docId("backup_policy.pdf");

    private final InMemoryChunkStore store = new InMemoryChunkStore();
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final RagConfigData config = new RagConfigData();

    @BeforeEach
    void setUp() {
        config.getPdf().setMode(RagConstants.TEXT);
        config.getPdf().setPagesPerDocument(1);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).stream().map(text -> new float[]{0.1f, 0.2f, 0.3f}).toList());
    }

    private RagIngestionService service() {
        return new RagIngestionService(mock(VectorStore.class), config,
                new OverlappingTextSplitter(400, 50, 20), embeddingModel, store, "test-embedding-model");
    }

    @Test
    void newFileIsStoredAsVersion1() {
        var service = service();

        assertThat(service.ingest(V1)).isEqualTo(IngestResult.NEW);

        var stored = store.find(DOC_ID).orElseThrow();
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.chunkCount()).isEqualTo(service.buildChunks(V1).size());
    }

    @Test
    void unchangedFileIsSkipped() {
        var service = service();
        service.ingest(V1);

        assertThat(service.ingest(V1)).isEqualTo(IngestResult.UNCHANGED);

        verify(embeddingModel, times(1)).embed(anyList()); // the second run did not embed anything
        assertThat(store.find(DOC_ID).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void changedFileReplacesTheOldVersionAndTheChunkCountMatches() {
        var service = service();
        service.ingest(V1);
        int v1Chunks = (int) store.count(DOC_ID);
        int expectedV2Chunks = service.buildChunks(V2).size();
        assertThat(expectedV2Chunks).as("precondition: v2 is shorter than v1").isLessThan(v1Chunks);

        assertThat(service.ingest(V2)).isEqualTo(IngestResult.UPDATED);

        var stored = store.find(DOC_ID).orElseThrow();
        assertThat(stored.version()).isEqualTo(2);
        assertThat(store.count(DOC_ID)).isEqualTo(expectedV2Chunks); // no chunk of v1 left
        assertThat(store.versions(DOC_ID)).isEqualTo(Set.of(2));
    }

    @Test
    void failureWhileEmbeddingKeepsThePreviousVersionIntact() {
        var service = service();
        service.ingest(V1);
        var before = store.find(DOC_ID).orElseThrow();
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("Ollama is down"));

        assertThat(service.ingest(V2)).isEqualTo(IngestResult.FAILED);

        assertThat(store.find(DOC_ID)).contains(before);
        assertThat(store.versions(DOC_ID)).isEqualTo(Set.of(1));
    }

    @Test
    void failureWhileStoringKeepsThePreviousVersionIntact() {
        var service = service();
        service.ingest(V1);
        var before = store.find(DOC_ID).orElseThrow();
        store.failNextReplace();

        assertThat(service.ingest(V2)).isEqualTo(IngestResult.FAILED);

        assertThat(store.find(DOC_ID)).contains(before);
    }

    @Test
    void forceRebuildReingestsAnUnchangedFileWithoutChangingTheVersion() {
        var service = service();
        service.ingest(V1);
        int chunks = (int) store.count(DOC_ID);
        config.setForceRebuild(true);

        assertThat(service.ingest(V1)).isEqualTo(IngestResult.UPDATED);

        verify(embeddingModel, times(2)).embed(anyList());
        assertThat(store.find(DOC_ID).orElseThrow().version()).isEqualTo(1);
        assertThat(store.count(DOC_ID)).isEqualTo(chunks);
    }

    @Test
    void missingFileFailsAndStoresNothing() {
        assertThat(service().ingest(new ClassPathResource("pdf/missing.pdf"))).isEqualTo(IngestResult.FAILED);
        assertThat(store.find(DocumentIds.docId("missing.pdf"))).isEmpty();
    }
}
