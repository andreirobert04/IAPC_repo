package com.genai.first.retrievalpre.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPreprocessorTest {

    private final QueryPreprocessor queryPreprocessor = new QueryPreprocessor();

    @Test
    void trimsTheQuery() {
        assertThat(queryPreprocessor.prepare("  What is the incident policy?  "))
                .isEqualTo("What is the incident policy?");
    }

    @Test
    void nullQueryBecomesEmpty() {
        assertThat(queryPreprocessor.prepare(null)).isEmpty();
    }
}
