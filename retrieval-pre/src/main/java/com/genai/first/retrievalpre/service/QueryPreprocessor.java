package com.genai.first.retrievalpre.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Pre-retrieval steps applied to the user's question before it is sent to retrieval-post.
 * Story 3 tasks plug in here: safe metadata filtering (Task 1), query normalization (Task 2),
 * acronym expansion (Task 3) and multi-query expansion (Task 4).
 * For now it only trims the query and logs the original, so the full flow works end to end.
 */
@Slf4j
@Service
public class QueryPreprocessor {

    public String prepare(String originalQuery) {
        log.info("Original query: {}", originalQuery);
        var prepared = originalQuery == null ? "" : originalQuery.strip();
        log.info("Prepared query: {}", prepared);
        return prepared;
    }
}
