package com.genai.first.ingeststatic.store;

import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.exception.RagException;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory ChunkStore with the same replace semantics as QdrantChunkStore (for tests).
 */
public class InMemoryChunkStore implements ChunkStore {

    private final Map<String, Document> points = new LinkedHashMap<>();
    private boolean failNextReplace;

    public void failNextReplace() {
        this.failNextReplace = true;
    }

    @Override
    public Optional<StoredDocument> find(String docId) {
        var docs = documentsOf(docId);
        if (docs.isEmpty()) {
            return Optional.empty();
        }
        int latest = docs.stream().mapToInt(this::version).max().orElseThrow();
        var latestDocs = docs.stream().filter(d -> version(d) == latest).toList();
        return Optional.of(new StoredDocument(docId, latest,
                String.valueOf(latestDocs.get(0).getMetadata().get(RagConstants.CHECKSUM)), latestDocs.size()));
    }

    @Override
    public void replace(String docId, List<EmbeddedChunk> chunks) {
        if (failNextReplace) {
            failNextReplace = false;
            throw new RagException("simulated store failure");
        }
        var newIds = chunks.stream().map(c -> c.document().getId()).collect(Collectors.toSet());
        chunks.forEach(c -> points.put(c.document().getId(), c.document()));
        points.values().removeIf(d -> docId.equals(d.getMetadata().get(RagConstants.DOC_ID)) && !newIds.contains(d.getId()));
    }

    public long count(String docId) {
        return documentsOf(docId).size();
    }

    public Set<Integer> versions(String docId) {
        return documentsOf(docId).stream().map(this::version).collect(Collectors.toSet());
    }

    private List<Document> documentsOf(String docId) {
        return points.values().stream().filter(d -> docId.equals(d.getMetadata().get(RagConstants.DOC_ID))).toList();
    }

    private int version(Document d) {
        return ((Number) d.getMetadata().get(RagConstants.VERSION)).intValue();
    }
}
