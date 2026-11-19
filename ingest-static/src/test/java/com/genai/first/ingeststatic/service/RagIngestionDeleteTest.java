package com.genai.first.ingeststatic.service;

import com.genai.first.ingeststatic.chunking.DocumentIds;
import com.genai.first.ingeststatic.chunking.OverlappingTextSplitter;
import com.genai.first.ingeststatic.config.data.RagConfigData;
import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.exception.RagException;
import com.genai.first.ingeststatic.store.EmbeddedChunk;
import com.genai.first.ingeststatic.store.InMemoryChunkStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story 1, Task 3 - delete process:
 * removes all chunks by doc_id and verifies that no orphaned vectors remain.
 */
class RagIngestionDeleteTest {

    private static final ClassPathResource V1 = new ClassPathResource("pdf/v1/backup_policy.pdf");
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
    void deleteByDocIdRemovesAllChunksAndLeavesZeroOrphans() {
        var service = service();
        service.ingest(V1);
        int chunkCount = (int) store.count(DOC_ID);
        assertThat(chunkCount).isGreaterThan(0);

        long deleted = service.deleteByDocId(DOC_ID);

        assertThat(deleted).isEqualTo(chunkCount);
        assertThat(store.count(DOC_ID)).isZero();
        assertThat(store.find(DOC_ID)).isEmpty();
    }

    @Test
    void deleteBySourceResolvesDocIdAndDeletesAllChunks() {
        var service = service();
        service.ingest(V1);
        assertThat(store.count(DOC_ID)).isGreaterThan(0);

        long deleted = service.deleteBySource("backup_policy.pdf");

        assertThat(deleted).isGreaterThan(0);
        assertThat(store.count(DOC_ID)).isZero();
        assertThat(store.find(DOC_ID)).isEmpty();
    }

    @Test
    void deleteNonExistentDocIdReturnsZeroWithoutError() {
        var service = service();

        long deleted = service.deleteByDocId("non-existent-doc-id");

        assertThat(deleted).isZero();
    }

    @Test
    void orphanedVectorsDetectionThrowsExceptionIfChunksRemain() {
        var service = service();
        service.ingest(V1);
        store.simulateOrphanOnDelete();

        assertThatThrownBy(() -> service.deleteByDocId(DOC_ID))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("Orphaned vectors detected");
    }

    @Test
    void storeFailureDuringDeleteThrowsRagException() {
        var service = service();
        service.ingest(V1);
        store.failNextDelete();

        assertThatThrownBy(() -> service.deleteByDocId(DOC_ID))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("simulated delete failure");
    }

    @Test
    void checkForOrphanedVectorsPassesWhenZeroAndThrowsWhenOrphansExist() {
        var service = service();
        service.ingest(V1);

        assertThatThrownBy(() -> service.checkForOrphanedVectors(DOC_ID))
                .isInstanceOf(RagException.class)
                .hasMessageContaining("Orphaned vectors detected");

        service.deleteByDocId(DOC_ID);

        // Does not throw when 0 chunks remain
        service.checkForOrphanedVectors(DOC_ID);
    }

    @Test
    void deletedFileFromResourcesIsCleanedUpDuringSync() {
        var service = service();
        // Simulate an old file that is stored in Qdrant but no longer in ./resources
        String removedDocId = DocumentIds.docId("old_deleted_policy.pdf");
        Document dummyDoc = new Document("chunk-1", "dummy content", Map.of(
                RagConstants.DOC_ID, removedDocId,
                RagConstants.VERSION, 1,
                RagConstants.SOURCE, "old_deleted_policy.pdf"
        ));
        store.replace(removedDocId, List.of(new EmbeddedChunk(dummyDoc, new float[]{0.1f, 0.2f})));
        assertThat(store.count(removedDocId)).isEqualTo(1);

        // When removed file's doc_id is deleted:
        long deleted = service.deleteByDocId(removedDocId);

        assertThat(deleted).isEqualTo(1);
        assertThat(store.count(removedDocId)).isZero();
    }

    @Test
    void deleteOrphanedVectorsPurgesVectorsWithoutActiveDocId() {
        String activeDocId = DocumentIds.docId("active.pdf");
        String orphanedDocId = DocumentIds.docId("removed.pdf");

        Document activeChunk = new Document("chunk-active", "active text", Map.of(
                RagConstants.DOC_ID, activeDocId,
                RagConstants.VERSION, 1
        ));
        Document orphanChunk = new Document("chunk-orphan", "orphan text", Map.of(
                RagConstants.DOC_ID, orphanedDocId,
                RagConstants.VERSION, 1
        ));

        store.replace(activeDocId, List.of(new EmbeddedChunk(activeChunk, new float[]{0.1f})));
        store.replace(orphanedDocId, List.of(new EmbeddedChunk(orphanChunk, new float[]{0.2f})));

        assertThat(store.findOrphanedVectorIds(Set.of(activeDocId))).containsExactly("chunk-orphan");

        long cleaned = store.deleteOrphanedVectors(Set.of(activeDocId));
        assertThat(cleaned).isEqualTo(1);
        assertThat(store.findOrphanedVectorIds(Set.of(activeDocId))).isEmpty();
        assertThat(store.count(activeDocId)).isEqualTo(1);
        assertThat(store.count(orphanedDocId)).isZero();
    }
}
