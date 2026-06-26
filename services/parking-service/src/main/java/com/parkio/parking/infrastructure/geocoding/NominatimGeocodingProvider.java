package com.parkio.parking.infrastructure.geocoding;

import com.parkio.parking.application.geocoding.GeocodeResult;
import com.parkio.parking.application.geocoding.GeocodingUnavailableException;
import com.parkio.parking.application.port.GeocodingProvider;
import com.parkio.parking.infrastructure.config.GeocodingProperties;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Nominatim (OpenStreetMap) adapter for {@link GeocodingProvider}. The previous
 * browser-direct call now lives here — server-side — so the provider's usage
 * policy can be honored (descriptive {@code User-Agent}, an outbound rate limit)
 * and the key/host is never exposed to the client.
 *
 * <p>Resilience (Resilience4j, instance {@code geocoding}):
 * <ul>
 *   <li><b>timeout</b> — short connect/read timeouts on the HTTP client (same
 *       pattern as {@code MediaServiceClient}), so a slow provider fails fast;</li>
 *   <li><b>rate limiter</b> — caps <em>outbound</em> calls (≤1/s by default) to
 *       respect Nominatim's policy regardless of inbound load;</li>
 *   <li><b>bulkhead</b> — caps concurrent provider calls so a stall can never
 *       exhaust parking-service request threads;</li>
 *   <li><b>circuit breaker</b> — opens on sustained failures and short-circuits.</li>
 * </ul>
 * Any of these tripping routes through {@link #searchFallback}, which raises a
 * {@link GeocodingUnavailableException} for the application service to degrade on.
 *
 * <p>The result mapping mirrors the prior frontend {@code toResult} exactly, so the
 * SPA's {@code GeocodeResult} contract is preserved byte-for-byte.
 */
@Component
public class NominatimGeocodingProvider implements GeocodingProvider {

    private static final Logger log = LoggerFactory.getLogger(NominatimGeocodingProvider.class);

    static final String RESILIENCE_INSTANCE = "geocoding";

    private final RestClient restClient;
    private final GeocodingProperties.Provider config;

    public NominatimGeocodingProvider(RestClient.Builder restClientBuilder, GeocodingProperties properties) {
        this.config = properties.getProvider();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(config.getConnectTimeout());
        requestFactory.setReadTimeout(config.getReadTimeout());
        this.restClient = restClientBuilder
                .baseUrl(config.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, config.getUserAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "searchFallback")
    @RateLimiter(name = RESILIENCE_INSTANCE)
    @Bulkhead(name = RESILIENCE_INSTANCE)
    public List<GeocodeResult> search(String query, int limit) {
        String uri = UriComponentsBuilder.fromPath("/search")
                .queryParam("q", query)
                .queryParam("format", "jsonv2")
                .queryParam("addressdetails", "1")
                .queryParam("countrycodes", config.getCountryCodes())
                .queryParam("accept-language", config.getLanguage())
                .queryParam("limit", limit)
                .build()
                .toUriString();

        NominatimItem[] items = restClient.get().uri(uri).retrieve().body(NominatimItem[].class);
        if (items == null) {
            return List.of();
        }
        List<GeocodeResult> results = new ArrayList<>(items.length);
        for (NominatimItem item : items) {
            GeocodeResult result = toResult(item);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    /**
     * Resilience fallback: invoked when the call fails or a policy trips (circuit
     * open / bulkhead full / rate limit exceeded / transport error). Translates any
     * cause into {@link GeocodingUnavailableException} so the service degrades.
     */
    @SuppressWarnings("unused") // referenced by name from @CircuitBreaker(fallbackMethod = ...)
    private List<GeocodeResult> searchFallback(String query, int limit, Throwable cause) {
        log.warn("geocoding provider unavailable: {}", cause.getClass().getSimpleName());
        throw new GeocodingUnavailableException("Geocoding provider is unavailable.", cause);
    }

    /** Port of the frontend {@code toResult}: derive primary/secondary labels and id. */
    private static GeocodeResult toResult(NominatimItem item) {
        if (item == null) {
            return null;
        }
        double lat = parseCoordinate(item.lat());
        double lng = parseCoordinate(item.lon());
        if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
            return null;
        }
        NominatimAddress address = item.address() == null ? new NominatimAddress() : item.address();

        String city = firstNonBlank(address.city(), address.town(), address.village(),
                address.province(), address.state());
        String district = firstNonBlank(address.city_district(), address.district(),
                address.county(), address.suburb(), address.quarter());

        // De-duplicate (district == city) and drop blanks, preserving order.
        Set<String> secondaryParts = new LinkedHashSet<>();
        if (district != null) {
            secondaryParts.add(district);
        }
        if (city != null) {
            secondaryParts.add(city);
        }
        String secondary = String.join(", ", secondaryParts);

        String displayName = trimToEmpty(item.display_name());
        String firstSegment = displayName.contains(",") ? displayName.substring(0, displayName.indexOf(',')).trim()
                : displayName;
        String primary = firstNonBlank(trimToNull(item.name()), address.road(), address.pedestrian(),
                trimToNull(firstSegment), trimToNull(displayName));
        if (primary == null) {
            primary = displayName;
        }

        String id = item.place_id() != null && !item.place_id().isBlank()
                ? item.place_id()
                : lat + "," + lng;

        return new GeocodeResult(
                id,
                displayName.isBlank() ? primary : displayName,
                primary.isBlank() ? displayName : primary,
                secondary,
                lat,
                lng);
    }

    private static double parseCoordinate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
