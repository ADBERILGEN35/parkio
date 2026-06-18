package com.parkio.parking.infrastructure.client;

import com.parkio.parking.application.port.MediaReadinessPort;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.web.CorrelationIdFilter;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Synchronous client for media-service's internal media-status endpoint
 * ({@code GET /internal/media/{mediaId}/status?ownerUserId=...}), used at
 * spot-creation time to ensure the referenced media is owned by the spot creator and
 * has passed media-service's safety checks (notably the malware scan) before a spot
 * may reference it. Kept separate from {@link MediaServiceClient} so each
 * cross-service capability is an independently mockable bean.
 *
 * <p>Sends the shared {@code X-Gateway-Auth} secret and the bound
 * {@code X-Correlation-Id}. Fail-closed: a non-{@code READY} status or a 404 maps to
 * {@code MEDIA_NOT_READY} (422); any transport failure maps to
 * {@code MEDIA_ACCESS_UNAVAILABLE} (503). Media never defaults to "ready".
 */
@Component
public class MediaReadinessClient implements MediaReadinessPort {

    private static final Logger log = LoggerFactory.getLogger(MediaReadinessClient.class);

    static final String GATEWAY_AUTH_HEADER = "X-Gateway-Auth";
    static final String READY_STATUS = "READY";

    private final RestClient restClient;

    public MediaReadinessClient(RestClient.Builder restClientBuilder,
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
    public void ensureMediaReady(UUID mediaId, UUID ownerUserId) {
        MediaStatusResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/media/{mediaId}/status")
                            .queryParam("ownerUserId", ownerUserId)
                            .build(mediaId))
                    .headers(headers -> {
                        String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (traceId != null && !traceId.isBlank()) {
                            headers.set(CorrelationIdFilter.HEADER, traceId);
                        }
                    })
                    .retrieve()
                    .body(MediaStatusResponse.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                // Media missing/deleted/not owned by this user — a spot may not reference it.
                throw notReady();
            }
            log.warn("media-service status call failed with status {}", ex.getStatusCode().value());
            throw unavailable();
        } catch (RestClientException ex) {
            log.warn("media-service status call failed: {}", ex.getClass().getSimpleName());
            throw unavailable();
        }
        if (response == null || !READY_STATUS.equals(response.status())) {
            throw notReady();
        }
    }

    private static ParkingException unavailable() {
        return new ParkingException(ParkingErrorCode.MEDIA_ACCESS_UNAVAILABLE,
                "Spot photo is temporarily unavailable.");
    }

    private static ParkingException notReady() {
        return new ParkingException(ParkingErrorCode.MEDIA_NOT_READY,
                "The selected photo is not ready yet. Please wait for it to finish processing and try again.");
    }

    /** Local copy of media-service's internal status contract — never shared across services. */
    record MediaStatusResponse(UUID mediaId, String status, UUID ownerUserId) {
    }
}
