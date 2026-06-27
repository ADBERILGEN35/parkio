package com.parkio.notification.infrastructure.smartreturn;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SmartReturnUserClient {

    private final RestClient restClient;

    public SmartReturnUserClient(
            RestClient.Builder builder,
            @Value("${parkio.smart-return.user-service.base-url:http://localhost:8082}") String baseUrl,
            @Value("${parkio.gateway.internal-secret}") String gatewaySecret) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("X-Gateway-Auth", gatewaySecret)
                .build();
    }

    public List<PromptCandidate> claimDuePrompts(LocalDate promptDate, int limit) {
        PromptCandidate[] body = restClient.post()
                .uri(uri -> uri.path("/internal/users/smart-return/due-prompts")
                        .queryParam("promptDate", promptDate)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(PromptCandidate[].class);
        return body == null ? List.of() : Arrays.asList(body);
    }

    public List<ReturnCheckCandidate> claimDueReturnChecks(Instant now, int limit) {
        ReturnCheckCandidate[] body = restClient.post()
                .uri(uri -> uri.path("/internal/users/smart-return/due-return-checks")
                        .queryParam("now", now)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(ReturnCheckCandidate[].class);
        return body == null ? List.of() : Arrays.asList(body);
    }

    public void markNotificationSent(UUID userId, Instant sentAt) {
        restClient.post()
                .uri(uri -> uri.path("/internal/users/smart-return/{userId}/notification-sent")
                        .queryParam("sentAt", sentAt)
                        .build(userId))
                .retrieve()
                .toBodilessEntity();
    }

    public void completeReturnCheck(UUID userId, boolean notificationSent, Instant completedAt) {
        restClient.post()
                .uri(uri -> uri.path("/internal/users/smart-return/{userId}/return-check-completed")
                        .queryParam("notificationSent", notificationSent)
                        .queryParam("completedAt", completedAt)
                        .build(userId))
                .retrieve()
                .toBodilessEntity();
    }

    public record PromptCandidate(UUID userId) {
    }

    public record ReturnCheckCandidate(
            UUID userId,
            double homeLatitude,
            double homeLongitude,
            String homeLabel,
            int radiusMeters,
            Instant expectedReturnAt,
            boolean claimRetried) {
    }
}
