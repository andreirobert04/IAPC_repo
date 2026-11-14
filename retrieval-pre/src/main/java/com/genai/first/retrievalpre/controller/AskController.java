package com.genai.first.retrievalpre.controller;

import com.genai.first.retrievalpre.client.RetrievalPostClient;
import com.genai.first.retrievalpre.service.QueryPreprocessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public entry point for questions (through the gateway: POST /retrieval-pre/api/ask).
 * Flow: retrieval-pre (prepare query) -> retrieval-post (retrieve + answer with citations).
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AskController {

    private final QueryPreprocessor queryPreprocessor;
    private final RetrievalPostClient retrievalPostClient;

    @PostMapping("/ask")
    public String ask(@RequestBody String message,
                      @RequestParam(value = "filter", required = false) String filter) {
        var query = queryPreprocessor.prepare(message);
        return retrievalPostClient.ask(query, filter);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> retrievalPostUnavailable(IllegalStateException e) {
        log.error("retrieval-post is not available: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("retrieval-post is not available (not registered in Eureka yet?)");
    }
}
