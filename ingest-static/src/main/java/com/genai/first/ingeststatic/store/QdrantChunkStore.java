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
import java.util.Optional;
import java.util.UUID;

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
