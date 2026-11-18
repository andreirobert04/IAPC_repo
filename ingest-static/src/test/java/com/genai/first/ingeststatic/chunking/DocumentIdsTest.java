package com.genai.first.ingeststatic.chunking;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentIdsTest {

    @Test
    void sameSourceAlwaysGivesTheSameDocId() {
        assertThat(DocumentIds.docId("scope_roles.pdf")).isEqualTo(DocumentIds.docId("scope_roles.pdf"));
    }

    @Test
    void differentSourcesGiveDifferentDocIds() {
        assertThat(DocumentIds.docId("scope_roles.pdf")).isNotEqualTo(DocumentIds.docId("secure_development.pdf"));
    }

    @Test
    void chunkIdIsADeterministicUuid() {
        var docId = DocumentIds.docId("scope_roles.pdf");
        var id = DocumentIds.chunkId(docId, 1, 3);

        assertThat(id).isEqualTo(DocumentIds.chunkId(docId, 1, 3));
        assertThat(UUID.fromString(id)).isNotNull(); // Qdrant point ids must be UUIDs
    }

    @Test
    void chunkIdChangesWithVersionAndIndex() {
        var docId = DocumentIds.docId("scope_roles.pdf");
        assertThat(DocumentIds.chunkId(docId, 1, 0))
                .isNotEqualTo(DocumentIds.chunkId(docId, 2, 0))
                .isNotEqualTo(DocumentIds.chunkId(docId, 1, 1));
    }

    @Test
    void blankSourceIsRejected() {
        assertThatThrownBy(() -> DocumentIds.docId(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
