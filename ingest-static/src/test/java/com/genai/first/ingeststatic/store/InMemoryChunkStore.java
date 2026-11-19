package com.genai.first.ingeststatic.store;

import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.exception.RagException;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory ChunkStore with the same replace and delete semantics as QdrantChunkStore (for tests).
 */
public class InMemoryChunkStore implements ChunkStore {

    private final Map<String, Document> points = new LinkedHashMap<>();
    private boolean failNextReplace;
    private boolean failNextDelete;
    private boolean simulateOrphanOnDelete;

    public void failNextReplace() {
        this.failNextReplace = true;
    }

    public void failNextDelete() {
        this.failNextDelete = true;
    }

    public void simulateOrphanOnDelete() {
        this.simulateOrphanOnDelete = true;
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

    @Override
    public long delete(String docId) {
        if (failNextDelete) {
            failNextDelete = false;
            throw new RagException("simulated delete failure");
        }
        var docs = documentsOf(docId);
        if (docs.isEmpty()) {
            return 0;
        }
        long count = docs.size();
        if (simulateOrphanOnDelete) {
            simulateOrphanOnDelete = false;
            // Leave one chunk behind to simulate an orphaned vector
            var toRemove = docs.subList(0, docs.size() - 1);
            toRemove.forEach(d -> points.remove(d.getId()));
            throw new RagException("Orphaned vectors detected: 1 vector(s) still remain for doc_id=" + docId + " after deletion");
        }
        points.values().removeIf(d -> docId.equals(d.getMetadata().get(RagConstants.DOC_ID)));

        long remaining = count(docId);
        if (remaining > 0) {
            throw new RagException("Orphaned vectors detected: " + remaining + " vector(s) still remain for doc_id=" + docId + " after deletion");
        }
        return count;
    }

    @Override
    public long count(String docId) {
        return documentsOf(docId).size();
    }

    @Override
    public Set<String> findAllDocIds() {
        return points.values().stream()
                .map(d -> (String) d.getMetadata().get(RagConstants.DOC_ID))
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    @Override
    public List<String> findOrphanedVectorIds(Set<String> activeDocIds) {
        return points.values().stream()
                .filter(d -> {
                    var docId = (String) d.getMetadata().get(RagConstants.DOC_ID);
                    return docId == null || docId.isBlank() || !activeDocIds.contains(docId);
                })
                .map(Document::getId)
                .toList();
    }

    @Override
    public long deleteOrphanedVectors(Set<String> activeDocIds) {
        var orphanIds = findOrphanedVectorIds(activeDocIds);
        orphanIds.forEach(points::remove);
        return orphanIds.size();
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
