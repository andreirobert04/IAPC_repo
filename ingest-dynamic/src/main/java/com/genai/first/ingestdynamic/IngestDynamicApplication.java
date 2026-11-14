package com.genai.first.ingestdynamic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Story 2 - dynamic ingestion. Skeleton only: it starts, registers in Eureka and is
 * reachable through the gateway (/ingest-dynamic/**). The Story 2 tasks are implemented here.
 */
@SpringBootApplication
public class IngestDynamicApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestDynamicApplication.class, args);
    }
}
