package com.genai.first.ingeststatic.store;

import org.springframework.ai.document.Document;

/**
 * A chunk together with its embedding, computed before anything is written to the store.
 */
public record EmbeddedChunk(Document document, float[] embedding) {
}
