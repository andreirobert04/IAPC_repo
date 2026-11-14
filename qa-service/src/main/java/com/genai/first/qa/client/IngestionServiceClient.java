package com.genai.first.qa.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Calls ingestion-service by its Eureka name (no hardcoded host/port).
 * LoadBalancerClient asks Eureka for the registered instances and picks one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionServiceClient {

    private static final String SERVICE_ID = "ingestion-service";

    private final LoadBalancerClient loadBalancerClient;
    private final RestClient restClient = RestClient.create();

    public List<String> getSources() {
        ServiceInstance instance = loadBalancerClient.choose(SERVICE_ID);
        if (instance == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No instance of " + SERVICE_ID + " is registered in Eureka");
        }
        log.info("Resolved {} via Eureka -> {}", SERVICE_ID, instance.getUri());
        return restClient.get()
                .uri(instance.getUri() + "/api/ingestion/sources")
                .retrieve()
                .body(new ParameterizedTypeReference<List<String>>() {});
    }
}
