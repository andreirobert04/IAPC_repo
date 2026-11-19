package com.genai.first.ingeststatic.store;

import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.exception.RagException;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Qdrant implementation of {@link ChunkStore}.
 * Points are written in the same format as Spring AI's QdrantVectorStore (text in "doc_content",
 * metadata as top-level payload fields), so retrieval-post reads them as usual.
 */
@Slf4j
@Component
public class QdrantChunkStore implements ChunkStore {

    private static final String CONTENT_FIELD = "doc_content";
    private static final int SCROLL_LIMIT = 10_000;

    private final QdrantClient qdrantClient;
    private final String collectionName;

    public QdrantChunkStore(QdrantClient qdrantClient,
                            @Value("${spring.ai.vectorstore.qdrant.collection-name}") String collectionName) {
        this.qdrantClient = qdrantClient;
        this.collectionName = collectionName;
    }

    @Override
    public Optional<StoredDocument> find(String docId) {
        var request = Points.ScrollPoints.newBuilder()
                .setCollectionName(collectionName)
                .setFilter(Points.Filter.newBuilder()
                        .addMust(ConditionFactory.matchKeyword(RagConstants.DOC_ID, docId))
                        .build())
                .setLimit(SCROLL_LIMIT)
                .setWithPayload(WithPayloadSelectorFactory.include(List.of(RagConstants.VERSION, RagConstants.CHECKSUM)))
                .build();
        List<Points.RetrievedPoint> points;
        try {
            points = qdrantClient.scrollAsync(request).get().getResultList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException("Interrupted while reading document " + docId + " from Qdrant", e);
        } catch (Exception e) {
            throw new RagException("Could not read document " + docId + " from Qdrant", e);
        }
        if (points.isEmpty()) {
            return Optional.empty();
        }

        int latestVersion = points.stream().mapToInt(this::version).max().orElse(RagConstants.INITIAL_VERSION);
        var latest = points.stream().filter(p -> version(p) == latestVersion).toList();
        var checksum = latest.get(0).getPayloadMap().getOrDefault(RagConstants.CHECKSUM,
                ValueFactory.value("")).getStringValue();
        return Optional.of(new StoredDocument(docId, latestVersion, checksum, latest.size()));
    }

    @Override
    public void replace(String docId, List<EmbeddedChunk> chunks) {
        List<Points.PointStruct> points = new ArrayList<>();
        List<io.qdrant.client.grpc.Points.PointId> newIds = new ArrayList<>();
        for (var chunk : chunks) {
            var id = PointIdFactory.id(UUID.fromString(chunk.document().getId()));
            newIds.add(id);
            points.add(Points.PointStruct.newBuilder()
                    .setId(id)
                    .setVectors(VectorsFactory.vectors(chunk.embedding()))
                    .putAllPayload(toPayload(chunk))
                    .build());
        }

        var upsert = Points.PointsUpdateOperation.newBuilder()
                .setUpsert(Points.PointsUpdateOperation.PointStructList.newBuilder().addAllPoints(points).build())
                .build();
        // every chunk of this document that is not part of the new set (old version, extra chunks)
        var oldChunks = Points.Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword(RagConstants.DOC_ID, docId))
                .addMustNot(ConditionFactory.hasId(newIds))
                .build();
        var delete = Points.PointsUpdateOperation.newBuilder()
                .setDeletePoints(Points.PointsUpdateOperation.DeletePoints.newBuilder()
                        .setPoints(Points.PointsSelector.newBuilder().setFilter(oldChunks).build())
                        .build())
                .build();

        try {
            // one request, applied in order: new chunks first, then the old ones are removed
            qdrantClient.batchUpdateAsync(collectionName, List.of(upsert, delete)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException("Interrupted while replacing document " + docId, e);
        } catch (Exception e) {
            throw new RagException("Could not replace document " + docId + " in Qdrant", e);
        }
    }

    @Override
    public long delete(String docId) {
        var existing = find(docId);
        if (existing.isEmpty()) {
            log.info("No chunks found in Qdrant for doc_id={}", docId);
            return 0;
        }
        int expectedDeletedCount = existing.get().chunkCount();

        var filter = Points.Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword(RagConstants.DOC_ID, docId))
                .build();
        var delete = Points.PointsUpdateOperation.newBuilder()
                .setDeletePoints(Points.PointsUpdateOperation.DeletePoints.newBuilder()
                        .setPoints(Points.PointsSelector.newBuilder().setFilter(filter).build())
                        .build())
                .build();

        try {
            qdrantClient.batchUpdateAsync(collectionName, List.of(delete)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException("Interrupted while deleting document " + docId, e);
        } catch (Exception e) {
            throw new RagException("Could not delete document " + docId + " from Qdrant", e);
        }

        // Check for orphaned vectors: verify no chunks remain for this docId
        long orphanedCount = count(docId);
        if (orphanedCount > 0) {
            throw new RagException("Orphaned vectors detected: " + orphanedCount +
                    " vector(s) still remain for doc_id=" + docId + " after deletion");
        }
        log.info("Successfully deleted {} chunk(s) for doc_id={}, 0 orphaned vectors remaining",
                expectedDeletedCount, docId);
        return expectedDeletedCount;
    }

    @Override
    public long count(String docId) {
        var request = Points.ScrollPoints.newBuilder()
                .setCollectionName(collectionName)
                .setFilter(Points.Filter.newBuilder()
                        .addMust(ConditionFactory.matchKeyword(RagConstants.DOC_ID, docId))
                        .build())
                .setLimit(SCROLL_LIMIT)
                .setWithPayload(WithPayloadSelectorFactory.enable(false))
                .build();
        try {
            return qdrantClient.scrollAsync(request).get().getResultList().size();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException("Interrupted while counting chunks for doc_id=" + docId, e);
        } catch (Exception e) {
            throw new RagException("Could not count chunks for doc_id=" + docId + " from Qdrant", e);
        }
    }

    @Override
    public Set<String> findAllDocIds() {
        var request = Points.ScrollPoints.newBuilder()
                .setCollectionName(collectionName)
                .setLimit(SCROLL_LIMIT)
                .setWithPayload(WithPayloadSelectorFactory.include(List.of(RagConstants.DOC_ID)))
                .build();
        List<Points.RetrievedPoint> points;
        try {
            points = qdrantClient.scrollAsync(request).get().getResultList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException("Interrupted while reading doc_ids from Qdrant", e);
        } catch (Exception e) {
            throw new RagException("Could not read doc_ids from Qdrant", e);
        }
        return points.stream()
                .map(p -> p.getPayloadMap().get(RagConstants.DOC_ID))
                .filter(Objects::nonNull)
                .map(JsonWithInt.Value::getStringValue)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
    }

    @Override
    public List<String> findOrphanedVectorIds(Set<String> activeDocIds) {
        var request = Points.ScrollPoints.newBuilder()
                .setCollectionName(collectionName)
                .setLimit(SCROLL_LIMIT)
                .setWithPayload(WithPayloadSelectorFactory.include(List.of(RagConstants.DOC_ID)))
                .build();
        List<Points.RetrievedPoint> points;
        try {
            points = qdrantClient.scrollAsync(request).get().getResultList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException("Interrupted while searching for orphaned vectors in Qdrant", e);
        } catch (Exception e) {
            throw new RagException("Could not search for orphaned vectors in Qdrant", e);
        }
        List<String> orphanedIds = new ArrayList<>();
        for (var point : points) {
            var val = point.getPayloadMap().get(RagConstants.DOC_ID);
            String docId = val != null ? val.getStringValue() : null;
            if (docId == null || docId.isBlank() || !activeDocIds.contains(docId)) {
                orphanedIds.add(point.getId().getUuid());
            }
        }
        return orphanedIds;
    }

    @Override
    public long deleteOrphanedVectors(Set<String> activeDocIds) {
        List<String> orphanedIds = findOrphanedVectorIds(activeDocIds);
        if (orphanedIds.isEmpty()) {
            return 0;
        }
        List<Points.PointId> pointIds = orphanedIds.stream()
                .map(id -> PointIdFactory.id(UUID.fromString(id)))
                .toList();
        var filter = Points.Filter.newBuilder()
                .addMust(ConditionFactory.hasId(pointIds))
                .build();
        var delete = Points.PointsUpdateOperation.newBuilder()
                .setDeletePoints(Points.PointsUpdateOperation.DeletePoints.newBuilder()
                        .setPoints(Points.PointsSelector.newBuilder().setFilter(filter).build())
                        .build())
                .build();
        try {
            qdrantClient.batchUpdateAsync(collectionName, List.of(delete)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException("Interrupted while deleting orphaned vectors", e);
        } catch (Exception e) {
            throw new RagException("Could not delete orphaned vectors from Qdrant", e);
        }
        var remaining = findOrphanedVectorIds(activeDocIds);
        if (!remaining.isEmpty()) {
            throw new RagException("Orphaned vectors still detected after deletion: " + remaining.size());
        }
        log.info("Deleted {} orphaned vector(s) from Qdrant", orphanedIds.size());
        return orphanedIds.size();
    }

    private int version(Points.RetrievedPoint point) {
        var value = point.getPayloadMap().get(RagConstants.VERSION);
        if (value == null) {
            return RagConstants.INITIAL_VERSION;
        }
        return switch (value.getKindCase()) {
            case INTEGER_VALUE -> (int) value.getIntegerValue();
            case DOUBLE_VALUE -> (int) value.getDoubleValue();
            case STRING_VALUE -> Integer.parseInt(value.getStringValue());
            default -> RagConstants.INITIAL_VERSION;
        };
    }

    private Map<String, JsonWithInt.Value> toPayload(EmbeddedChunk chunk) {
        Map<String, JsonWithInt.Value> payload = new HashMap<>();
        payload.put(CONTENT_FIELD, ValueFactory.value(chunk.document().getText()));
        chunk.document().getMetadata().forEach((key, value) -> payload.put(key, toValue(value)));
        return payload;
    }

    private JsonWithInt.Value toValue(Object value) {
        if (value == null) {
            return ValueFactory.nullValue();
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ValueFactory.value(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return ValueFactory.value(number.doubleValue());
        }
        if (value instanceof Boolean bool) {
            return ValueFactory.value(bool);
        }
        return ValueFactory.value(String.valueOf(value));
    }
}
