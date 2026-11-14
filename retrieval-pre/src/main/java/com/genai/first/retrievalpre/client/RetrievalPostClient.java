package com.genai.first.retrievalpre.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Calls retrieval-post by its Eureka service name (no hardcoded host/port).
 * LoadBalancerClient asks Eureka for the registered instances and picks one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalPostClient {

    private static final String SERVICE_ID = "retrieval-post";

    private final LoadBalancerClient loadBalancerClient;
    private final RestClient restClient = RestClient.create();

    public String ask(String query, String filter) {
        ServiceInstance instance = loadBalancerClient.choose(SERVICE_ID);
        if (instance == null) {
            throw new IllegalStateException("No instance of " + SERVICE_ID + " is registered in Eureka");
        }
        log.info("Resolved {} via Eureka -> {}", SERVICE_ID, instance.getUri());

        var uri = UriComponentsBuilder.fromUri(instance.getUri())
                .path("/api/internal-qa/ask");
        if (filter != null && !filter.isBlank()) {
            uri.queryParam("filter", filter);
        }

        return restClient.post()
                .uri(uri.build().toUri())
                .contentType(MediaType.TEXT_PLAIN)
                .body(query)
                .retrieve()
                .body(String.class);
    }
}
