package com.parkio.parking.infrastructure.client;

import com.parkio.parking.application.port.FavouriteFacilityLookupPort;
import com.parkio.parking.application.recommendation.ranking.RankingMetrics;
import com.parkio.parking.infrastructure.web.CorrelationIdFilter;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Fail-open client for user-service favourite parking status
 * ({@code GET /api/v1/places/favourites/parking/status}).
 *
 * <p>Sends {@code X-Gateway-Auth} and {@code X-User-Id}. Bounded timeouts; no
 * retries. Failures yield an empty set so ranking continues with favourite=0.
 */
@Component
public class UserFavouritesClient implements FavouriteFacilityLookupPort {

    private static final Logger log = LoggerFactory.getLogger(UserFavouritesClient.class);

    static final String GATEWAY_AUTH_HEADER = "X-Gateway-Auth";
    static final String USER_ID_HEADER = "X-User-Id";
    /** Hard cap on IDs per request to avoid URL / query amplification. */
    static final int MAX_TARGET_IDS = 100;

    private final RestClient restClient;
    private final RankingMetrics metrics;

    public UserFavouritesClient(
            RestClient.Builder restClientBuilder,
            RankingMetrics metrics,
            @Value("${parkio.user.client.base-url:http://localhost:8082}") String baseUrl,
            @Value("${parkio.gateway.internal-secret}") String internalSecret,
            @Value("${parkio.user.client.connect-timeout:1s}") Duration connectTimeout,
            @Value("${parkio.user.client.read-timeout:2s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(GATEWAY_AUTH_HEADER, internalSecret)
                .build();
        this.metrics = metrics;
    }

    @Override
    public Set<UUID> favouritedMunicipalFacilityIds(UUID authUserId, Collection<UUID> facilityIds) {
        if (authUserId == null || facilityIds == null || facilityIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> unique = facilityIds.stream()
                .filter(id -> id != null)
                .distinct()
                .limit(MAX_TARGET_IDS)
                .toList();
        if (unique.isEmpty()) {
            return Set.of();
        }
        long started = System.nanoTime();
        try {
            FavouriteParkingStatusResponse response = restClient
                    .get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/api/v1/places/favourites/parking/status");
                        for (UUID id : unique) {
                            uriBuilder.queryParam("targetIds", id.toString());
                        }
                        return uriBuilder.build();
                    })
                    .headers(headers -> {
                        headers.set(USER_ID_HEADER, authUserId.toString());
                        String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (traceId != null && !traceId.isBlank()) {
                            headers.set(CorrelationIdFilter.HEADER, traceId);
                        }
                    })
                    .retrieve()
                    .body(FavouriteParkingStatusResponse.class);
            metrics.recordFavouriteLookup(true, System.nanoTime() - started);
            if (response == null || response.favouritedTargetIds() == null) {
                return Set.of();
            }
            return Set.copyOf(new LinkedHashSet<>(response.favouritedTargetIds()));
        } catch (RestClientResponseException ex) {
            metrics.recordFavouriteLookup(false, System.nanoTime() - started);
            log.warn(
                    "user-service favourite status failed status={} type={}",
                    ex.getStatusCode().value(),
                    ex.getClass().getSimpleName());
            return Set.of();
        } catch (RestClientException ex) {
            metrics.recordFavouriteLookup(false, System.nanoTime() - started);
            log.warn(
                    "user-service favourite status failed type={}",
                    ex.getClass().getSimpleName());
            return Set.of();
        } catch (RuntimeException ex) {
            metrics.recordFavouriteLookup(false, System.nanoTime() - started);
            log.warn(
                    "user-service favourite status failed type={}",
                    ex.getClass().getSimpleName());
            return Set.of();
        }
    }

    record FavouriteParkingStatusResponse(List<UUID> favouritedTargetIds) {}
}
