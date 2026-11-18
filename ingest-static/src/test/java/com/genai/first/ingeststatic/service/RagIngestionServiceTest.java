package com.genai.first.ingeststatic.service;

import com.genai.first.ingeststatic.chunking.OverlappingTextSplitter;
import com.genai.first.ingeststatic.config.data.RagConfigData;
import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.exception.RagException;
import com.genai.first.ingeststatic.store.ChunkStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RagIngestionServiceTest {

    private static final String SAMPLE_PDF = "pdf/access_control_matrix.pdf";

    private RagIngestionService service() {
        var config = new RagConfigData();
        config.getPdf().setMode(RagConstants.TEXT);
        config.getPdf().setPagesPerDocument(1);
        return new RagIngestionService(mock(VectorStore.class), config,
                new OverlappingTextSplitter(400, 50, 20), mock(EmbeddingModel.class), mock(ChunkStore.class),
                "test-embedding-model");
    }

    @Test
    void everyChunkCarriesTheFullMetadataSchema() {
        var chunks = service().buildChunks(new ClassPathResource(SAMPLE_PDF));

        assertThat(chunks).isNotEmpty();
        for (Document chunk : chunks) {
            assertThat(chunk.getMetadata()).containsKeys(
                    RagConstants.DOC_ID, RagConstants.VERSION, RagConstants.SOURCE, RagConstants.PAGE,
                    RagConstants.CHUNK_INDEX, RagConstants.CHECKSUM, RagConstants.EMBEDDING_MODEL);
            assertThat(chunk.getMetadata().get(RagConstants.SOURCE)).isEqualTo("access_control_matrix.pdf");
            assertThat(chunk.getMetadata().get(RagConstants.EMBEDDING_MODEL)).isEqualTo("test-embedding-model");
            assertThat(chunk.getText()).isNotBlank();
        }
    }

    @Test
    void chunkIndexesRunFromZeroOverTheWholeDocument() {
        var chunks = service().buildChunks(new ClassPathResource(SAMPLE_PDF));

        var indexes = chunks.stream().map(c -> (Integer) c.getMetadata().get(RagConstants.CHUNK_INDEX)).toList();
        assertThat(indexes).containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void ingestingTheSameFileTwiceGivesTheSameChunkIds() {
        var first = service().buildChunks(new ClassPathResource(SAMPLE_PDF));
        var second = service().buildChunks(new ClassPathResource(SAMPLE_PDF));

        assertThat(second).extracting(Document::getId).containsExactlyElementsOf(
                first.stream().map(Document::getId).toList());
    }

    @Test
    void tableRowsAreExtractedIntact() {
        var text = String.join("\n", service().buildChunks(new ClassPathResource(SAMPLE_PDF))
                .stream().map(Document::getText).toList());

        // the whole row on one line (cells separated by spaces/tabs, never by a line break)
        assertThat(text).containsPattern("QA tester[ \\t]+Read[ \\t]+Staging only[ \\t]+Anonymized only[ \\t]+Run jobs[ \\t]+No access");
    }

    @Test
    void missingFileFailsWithAClearError() {
        assertThatThrownBy(() -> service().buildChunks(new ClassPathResource("pdf/does_not_exist.pdf")))
                .isInstanceOf(RagException.class);
    }
}
