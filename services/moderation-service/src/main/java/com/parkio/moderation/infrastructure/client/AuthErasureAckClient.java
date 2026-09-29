package com.parkio.moderation.infrastructure.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AuthErasureAckClient {

    static final String GATEWAY_AUTH_HEADER = "X-Gateway-Auth";
    static final String SERVICE_NAME = "moderation";

    private final RestClient restClient;

    public AuthErasureAckClient(
            RestClient.Builder restClientBuilder,
            @Value("${parkio.auth.client.base-url:http://localhost:8081}") String baseUrl,
            @Value("${parkio.gateway.internal-secret}") String internalSecret) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(GATEWAY_AUTH_HEADER, internalSecret)
                .build();
    }

    public void acknowledge(UUID eventId, UUID erasureRequestId, UUID authUserId, String status) {
        restClient.post()
                .uri("/internal/erasure/acks")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "eventId", eventId.toString(),
                        "erasureRequestId", erasureRequestId.toString(),
                        "authUserId", authUserId.toString(),
                        "serviceName", SERVICE_NAME,
                        "status", status,
                        "occurredAt", Instant.now().toString()))
                .retrieve()
                .toBodilessEntity();
    }
}
