package com.parkio.parking.application.geocoding;

import com.parkio.parking.application.port.GeocodingCache;
import com.parkio.parking.application.port.GeocodingProvider;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Forward-geocoding application service: the single place that validates input,
 * serves from cache, calls the provider on a miss, and degrades gracefully.
 *
 * <p>Flow: validate → cache lookup → (miss) provider call → cache store → return.
 * A {@link GeocodingUnavailableException} from the provider degrades to an empty
 * result set and is deliberately <em>not</em> cached (so an outage never poisons
 * the negative cache). The parking nearby-search path is unaffected: it takes
 * coordinates directly and never touches this service.
 */
@Service
public class GeocodingService {

    /** Mirrors the frontend's typeahead minimum and provider sanity bounds. */
    static final int MIN_QUERY_LENGTH = 3;
    static final int MAX_QUERY_LENGTH = 256;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 10;
    static final int DEFAULT_LIMIT = 5;

    /** Cache-key namespace; bump the version to invalidate when provider config changes. */
    private static final String CACHE_KEY_PREFIX = "geo:v1";

    private final GeocodingProvider provider;
    private final GeocodingCache cache;

    public GeocodingService(GeocodingProvider provider, GeocodingCache cache) {
        this.provider = provider;
        this.cache = cache;
    }

    /**
     * Resolve free text into candidate places.
     *
     * @param rawQuery user text; trimmed and length-checked (3–256)
     * @param rawLimit optional cap; defaults to {@value #DEFAULT_LIMIT}, bounded 1–10
     * @return ordered candidates (possibly empty)
     * @throws IllegalArgumentException when the query length or limit is out of range
     *         (mapped to HTTP 400 by the presentation layer)
     */
    public List<GeocodeResult> search(String rawQuery, Integer rawLimit) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < MIN_QUERY_LENGTH || query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "Query must be between " + MIN_QUERY_LENGTH + " and " + MAX_QUERY_LENGTH + " characters.");
        }
        int limit = rawLimit == null ? DEFAULT_LIMIT : rawLimit;
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "Limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT + ".");
        }

        String key = cacheKey(query, limit);
        Optional<List<GeocodeResult>> cached = cache.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<GeocodeResult> results;
        try {
            results = provider.search(query, limit);
        } catch (GeocodingUnavailableException ex) {
            // Degrade to "no suggestions"; do not cache a failure.
            return List.of();
        }

        cache.put(key, results);
        return results;
    }

    /** Stable key from the normalized query + limit (case/whitespace-insensitive). */
    private static String cacheKey(String query, int limit) {
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return CACHE_KEY_PREFIX + ":" + limit + ":" + normalized;
    }
}
