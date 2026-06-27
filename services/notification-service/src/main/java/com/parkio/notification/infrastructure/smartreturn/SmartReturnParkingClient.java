package com.parkio.notification.infrastructure.smartreturn;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SmartReturnParkingClient {

    private final RestClient restClient;

    public SmartReturnParkingClient(
            RestClient.Builder builder,
            @Value("${parkio.smart-return.parking-service.base-url:http://localhost:8083}") String baseUrl,
            @Value("${parkio.gateway.internal-secret}") String gatewaySecret) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("X-Gateway-Auth", gatewaySecret)
                .build();
    }

    public List<NearbySpot> searchNearby(UUID userId, double latitude, double longitude, int radiusMeters, int limit) {
        NearbySpot[] body = restClient.get()
                .uri(uri -> uri.path("/api/v1/parking/spots/nearby")
                        .queryParam("lat", latitude)
                        .queryParam("lng", longitude)
                        .queryParam("radius", radiusMeters)
                        .queryParam("limit", limit)
                        .build())
                .header("X-User-Id", userId.toString())
                .retrieve()
                .body(NearbySpot[].class);
        return body == null ? List.of() : Arrays.asList(body);
    }

    public record NearbySpot(
            UUID id,
            String addressText,
            String status,
            Instant expiresAt) {
    }
}
