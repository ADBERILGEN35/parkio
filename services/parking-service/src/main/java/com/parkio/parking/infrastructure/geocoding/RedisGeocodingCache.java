package com.parkio.parking.infrastructure.geocoding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.geocoding.GeocodeResult;
import com.parkio.parking.application.port.GeocodingCache;
import com.parkio.parking.infrastructure.config.GeocodingProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link GeocodingCache}, storing each result list as JSON.
 *
 * <p><b>Best-effort:</b> every Redis operation is guarded — a cache outage degrades
 * to a miss (read) or a no-op (write) and is logged at debug, never propagated. So
 * geocoding keeps working when Redis is down, just without caching, and the
 * parking-service health is never coupled to the cache.
 *
 * <p>TTL is chosen by result emptiness: a long positive TTL for real hits (places
 * are stable) and a short negative TTL for empty results (so a too-soon or typo'd
 * query is retried quickly rather than pinned as "nothing here" for a day).
 */
@Component
public class RedisGeocodingCache implements GeocodingCache {

    private static final Logger log = LoggerFactory.getLogger(RedisGeocodingCache.class);
    private static final TypeReference<List<GeocodeResult>> LIST_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration positiveTtl;
    private final Duration negativeTtl;

    public RedisGeocodingCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                               GeocodingProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.positiveTtl = properties.getCache().getPositiveTtl();
        this.negativeTtl = properties.getCache().getNegativeTtl();
    }

    @Override
    public Optional<List<GeocodeResult>> get(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, LIST_TYPE));
        } catch (Exception ex) {
            log.debug("geocoding cache read failed ({}); treating as miss", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, List<GeocodeResult> results) {
        try {
            Duration ttl = results.isEmpty() ? negativeTtl : positiveTtl;
            redis.opsForValue().set(key, objectMapper.writeValueAsString(results), ttl);
        } catch (Exception ex) {
            log.debug("geocoding cache write failed ({}); ignoring", ex.getClass().getSimpleName());
        }
    }
}
