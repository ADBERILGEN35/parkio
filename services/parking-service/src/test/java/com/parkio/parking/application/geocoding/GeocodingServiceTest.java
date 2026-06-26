package com.parkio.parking.application.geocoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.GeocodingCache;
import com.parkio.parking.application.port.GeocodingProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Orchestration tests for {@link GeocodingService}: validation bounds, cache-aside
 * behavior, and graceful degradation. Provider and cache are mocked, so these are
 * fast and need no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class GeocodingServiceTest {

    @Mock
    private GeocodingProvider provider;

    @Mock
    private GeocodingCache cache;

    private GeocodingService service() {
        return new GeocodingService(provider, cache);
    }

    private static GeocodeResult sample() {
        return new GeocodeResult("1", "Konak Pier, İzmir", "Konak Pier", "Konak, İzmir", 38.42, 27.14);
    }

    @Test
    void rejectsQueryShorterThanThreeChars() {
        assertThatThrownBy(() -> service().search("ab", 5))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(provider, cache);
    }

    @Test
    void rejectsQueryLongerThan256Chars() {
        String tooLong = "a".repeat(257);
        assertThatThrownBy(() -> service().search(tooLong, 5))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(provider, cache);
    }

    @Test
    void rejectsLimitOutOfRange() {
        assertThatThrownBy(() -> service().search("Konak Pier", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().search("Konak Pier", 11))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(provider, cache);
    }

    @Test
    void servesFromCacheWithoutCallingProvider() {
        when(cache.get(anyString())).thenReturn(Optional.of(List.of(sample())));

        List<GeocodeResult> results = service().search("Konak Pier", 5);

        assertThat(results).containsExactly(sample());
        verifyNoInteractions(provider);
        verify(cache, never()).put(anyString(), anyList());
    }

    @Test
    void onCacheMissCallsProviderAndStoresResult() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(provider.search(eq("Konak Pier"), eq(5))).thenReturn(List.of(sample()));

        List<GeocodeResult> results = service().search("  Konak Pier  ", 5);

        assertThat(results).containsExactly(sample());
        // Query is trimmed before reaching the provider.
        verify(provider).search("Konak Pier", 5);
        verify(cache).put(anyString(), eq(List.of(sample())));
    }

    @Test
    void defaultsLimitWhenAbsent() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(provider.search(anyString(), anyInt())).thenReturn(List.of());

        service().search("Konak Pier", null);

        verify(provider).search("Konak Pier", GeocodingService.DEFAULT_LIMIT);
    }

    @Test
    void cachesEmptyResults() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(provider.search(anyString(), anyInt())).thenReturn(List.of());

        List<GeocodeResult> results = service().search("nowhere place", 5);

        assertThat(results).isEmpty();
        verify(cache).put(anyString(), eq(List.of()));
    }

    @Test
    void degradesToEmptyWithoutCachingWhenProviderUnavailable() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(provider.search(anyString(), anyInt()))
                .thenThrow(new GeocodingUnavailableException("down", new RuntimeException()));

        List<GeocodeResult> results = service().search("Konak Pier", 5);

        assertThat(results).isEmpty();
        // A provider outage must never poison the (negative) cache.
        verify(cache, never()).put(anyString(), anyList());
        verify(provider, times(1)).search("Konak Pier", 5);
    }
}
