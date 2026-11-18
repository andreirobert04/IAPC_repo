package com.genai.first.ingeststatic.service;

import com.genai.first.ingeststatic.exception.RagException;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads stored chunks back from Qdrant for the manual quality check (Story 1, Task 1:
 * "a manual check of 20 random chunks shows no broken or garbled text").
 */
@Slf4j
@Service
public class ChunkInspectionService {

    private static final int SCROLL_LIMIT = 10_000;
    private static final String CONTENT_FIELD = "doc_content"; // where Spring AI stores the chunk text

    private final QdrantClient qdrantClient;
    private final String collectionName;

    public ChunkInspectionService(QdrantClient qdrantClient,
                                  @Value("${spring.ai.vectorstore.qdrant.collection-name}") String collectionName) {
        this.qdrantClient = qdrantClient;
        this.collectionName = collectionName;
    }

    /**
     * @param n      how many random chunks to return
     * @param source optional file name to sample from a single document
     */
    public List<Map<String, Object>> sample(int n, String source) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be > 0");
        }
        var request = Points.ScrollPoints.newBuilder()
                .setCollectionName(collectionName)
                .setLimit(SCROLL_LIMIT)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));
        if (source != null && !source.isBlank()) {
            request.setFilter(Points.Filter.newBuilder()
                    .addMust(ConditionFactory.matchKeyword("source", source))
                    .build());
        }

        List<Points.RetrievedPoint> points;
        try {
            points = new ArrayList<>(qdrantClient.scrollAsync(request.build()).get().getResultList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException("Interrupted while reading chunks from Qdrant", e);
        } catch (Exception e) {
            throw new RagException("Could not read chunks from Qdrant", e);
        }

        Collections.shuffle(points);
        var sample = points.stream()
                .limit(n)
                .map(this::toMap)
                .sorted(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("source")))
                        .thenComparing(m -> ((Number) m.getOrDefault("chunk_index", 0L)).longValue()))
                .toList();
        log.info("Chunk sample: {} of {} chunks{}", sample.size(), points.size(),
                source == null || source.isBlank() ? "" : " from " + source);
        return sample;
    }

    private Map<String, Object> toMap(Points.RetrievedPoint point) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", point.getId().getUuid());
        var payload = point.getPayloadMap();
        for (var key : List.of("source", "doc_id", "version", "page", "chunk_index", "checksum", "embedding_model")) {
            if (payload.containsKey(key)) {
                result.put(key, toJava(payload.get(key)));
            }
        }
        result.put("text", payload.containsKey(CONTENT_FIELD) ? toJava(payload.get(CONTENT_FIELD)) : null);
        return result;
    }

    private Object toJava(JsonWithInt.Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            default -> value.toString();
        };
    }
}
