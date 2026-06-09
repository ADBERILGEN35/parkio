package com.parkio.parking.infrastructure.client;

import com.parkio.parking.application.port.MediaAccessPort;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.web.CorrelationIdFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

/**
 * Synchronous client for media-service's internal access-URL endpoint
 * ({@code POST /internal/media/{mediaId}/access-url}). Sends the shared
 * {@code X-Gateway-Auth} secret (media-service rejects calls without it) and the
 * current {@code X-Correlation-Id} when one is bound to the request, so traces
 * span both services. No other client headers are forwarded.
 *
 * <p>Timeouts are short and configurable; any transport failure or unexpected
 * media-service response is mapped to {@code MEDIA_ACCESS_UNAVAILABLE} (503) so
 * the caller fails safely without leaking transport internals. A 404 from
 * media-service (media deleted) is surfaced as {@code SPOT_NOT_FOUND}.
 */
@Component
public class MediaServiceClient implements MediaAccessPort {

    private static final Logger log = LoggerFactory.getLogger(MediaServiceClient.class);

    static final String GATEWAY_AUTH_HEADER = "X-Gateway-Auth";
    static final String PURPOSE = "SPOT_PHOTO_VIEW";

    private final RestClient restClient;

    public MediaServiceClient(RestClient.Builder restClientBuilder,
                              @Value("${parkio.media.client.base-url}") String baseUrl,
                              @Value("${parkio.gateway.internal-secret}") String internalSecret,
                              @Value("${parkio.media.client.connect-timeout:2s}") Duration connectTimeout,
                              @Value("${parkio.media.client.read-timeout:5s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(GATEWAY_AUTH_HEADER, internalSecret)
                .build();
    }

    @Override
    public MediaAccessGrant requestAccessUrl(UUID mediaId, UUID requesterUserId) {
        try {
            AccessUrlResponse response = restClient.post()
                    .uri("/internal/media/{mediaId}/access-url", mediaId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (traceId != null && !traceId.isBlank()) {
                            headers.set(CorrelationIdFilter.HEADER, traceId);
                        }
                    })
                    .body(Map.of("requesterUserId", requesterUserId.toString(), "purpose", PURPOSE))
                    .retrieve()
                    .body(AccessUrlResponse.class);
            if (response == null || response.accessUrl() == null || response.expiresAt() == null) {
                throw unavailable();
            }
            return new MediaAccessGrant(response.mediaId(), response.accessUrl(), response.expiresAt());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                // The referenced media no longer exists (e.g. deleted by its owner).
                throw new ParkingException(ParkingErrorCode.SPOT_NOT_FOUND, "Spot photo is no longer available.");
            }
            log.warn("media-service access-url call failed with status {}", ex.getStatusCode().value());
            throw unavailable();
        } catch (RestClientException ex) {
            log.warn("media-service access-url call failed: {}", ex.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private static ParkingException unavailable() {
        return new ParkingException(ParkingErrorCode.MEDIA_ACCESS_UNAVAILABLE,
                "Spot photo is temporarily unavailable.");
    }

    /** Local copy of media-service's response contract — never shared across services. */
    record AccessUrlResponse(UUID mediaId, String accessUrl, Instant expiresAt) {
    }
}
