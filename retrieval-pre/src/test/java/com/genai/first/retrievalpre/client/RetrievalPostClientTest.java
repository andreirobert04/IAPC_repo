package com.genai.first.retrievalpre.client;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalPostClientTest {

    @Test
    void failsClearlyWhenRetrievalPostIsNotRegisteredInEureka() {
        var loadBalancerClient = mock(LoadBalancerClient.class);
        when(loadBalancerClient.choose("retrieval-post")).thenReturn(null);
        var client = new RetrievalPostClient(loadBalancerClient);

        assertThatThrownBy(() -> client.ask("question", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retrieval-post");
    }
}
