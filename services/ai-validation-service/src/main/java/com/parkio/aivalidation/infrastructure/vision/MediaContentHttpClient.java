package com.parkio.aivalidation.infrastructure.vision;

import com.parkio.aivalidation.infrastructure.config.VisionProperties;
import com.parkio.aivalidation.infrastructure.web.CorrelationIdFilter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Synchronous client for media-service's internal content endpoint
 * ({@code GET /internal/media/{mediaId}/content}). Applies Content-Length and
 * bounded-stream fetch caps, then builds a vision-only downscaled rendition.
 */
public class MediaContentHttpClient implements MediaContentFetcher {

    static final String GATEWAY_AUTH_HEADER = "X-Gateway-Auth";

    private static final Logger log = LoggerFactory.getLogger(MediaContentHttpClient.class);

    private final RestClient restClient;
    private final VisionProperties properties;
    private final VisionMetrics metrics;

    public MediaContentHttpClient(RestClient.Builder restClientBuilder,
                                  VisionProperties properties,
                                  String internalSecret,
                                  VisionMetrics metrics) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getMediaClient().getConnectTimeout());
        requestFactory.setReadTimeout(properties.getMediaClient().getReadTimeout());
        this.restClient = restClientBuilder
                .baseUrl(properties.getMediaClient().getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(GATEWAY_AUTH_HEADER, internalSecret)
                .build();
        this.properties = properties;
        this.metrics = metrics;
    }

    /** Backward-compatible ctor used by older tests without metrics. */
    public MediaContentHttpClient(RestClient.Builder restClientBuilder,
                                  VisionProperties properties,
                                  String internalSecret) {
        this(restClientBuilder, properties, internalSecret, null);
    }

    @Override
    public MediaContent fetch(UUID mediaId) {
        ContentResponse response = exchange(mediaId);
        byte[] bytes = response.body();
        String contentType = response.contentType();

        if (bytes == null || bytes.length == 0) {
            throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE,
                    "media-service returned an empty body");
        }
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();
        // Strip parameters such as "; charset=..." if present.
        int semi = normalizedType.indexOf(';');
        if (semi >= 0) {
            normalizedType = normalizedType.substring(0, semi).trim();
        }
        if (!properties.getAllowedContentTypes().contains(normalizedType)) {
            throw new MediaContentException(MediaContentException.Reason.UNSUPPORTED_TYPE,
                    "media content type is not allowed for vision analysis");
        }

        VisionImageRendition.Result rendition = VisionImageRendition.prepare(
                bytes,
                normalizedType,
                properties.getMaxDecodedPixels(),
                properties.getMaxSourceEdge(),
                properties.getTargetLongestEdge(),
                properties.getJpegQuality(),
                properties.getMaxImageBytes());
        if (metrics != null) {
            metrics.recordBytes(bytes.length, rendition.bytes().length);
        }
        ClaimedRegion claimedRegion = fetchClaimedRegion(mediaId);
        return new MediaContent(rendition.bytes(), rendition.contentType(), claimedRegion);
    }

    private ClaimedRegion fetchClaimedRegion(UUID mediaId) {
        try {
            return restClient.get()
                    .uri("/internal/media/{mediaId}/metadata", mediaId)
                    .headers(headers -> {
                        String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (traceId != null && !traceId.isBlank()) {
                            headers.set(CorrelationIdFilter.HEADER, traceId);
                        }
                    })
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return null;
                        }
                        try {
                            String json = new String(response.getBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            return parseClaimedRegion(json);
                        } catch (java.io.IOException ex) {
                            log.warn("Failed reading media metadata for {}: {}",
                                    mediaId, ex.getClass().getSimpleName());
                            return null;
                        }
                    });
        } catch (RuntimeException ex) {
            log.warn("media-service metadata call failed for {}: {}",
                    mediaId, ex.getClass().getSimpleName());
            return null;
        }
    }

    static ClaimedRegion parseClaimedRegion(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        // Lightweight parse without pulling Jackson into this hot path dependency graph:
        // metadata JSON is a small fixed shape from media-service.
        Double x = extractNumber(json, "\"x\"");
        Double y = extractNumber(json, "\"y\"");
        Double width = extractNumber(json, "\"width\"");
        Double height = extractNumber(json, "\"height\"");
        if (x == null || y == null || width == null || height == null) {
            return null;
        }
        try {
            return ClaimedRegion.of(x, y, width, height);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Double extractNumber(String json, String key) {
        int claimed = json.indexOf("\"claimedRegion\"");
        if (claimed < 0) {
            return null;
        }
        String slice = json.substring(claimed);
        int keyIdx = slice.indexOf(key);
        if (keyIdx < 0) {
            return null;
        }
        int colon = slice.indexOf(':', keyIdx);
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < slice.length() && Character.isWhitespace(slice.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < slice.length()
                && (Character.isDigit(slice.charAt(end))
                || slice.charAt(end) == '.'
                || slice.charAt(end) == '-'
                || slice.charAt(end) == 'e'
                || slice.charAt(end) == 'E'
                || slice.charAt(end) == '+')) {
            end++;
        }
        if (end <= start) {
            return null;
        }
        try {
            return Double.parseDouble(slice.substring(start, end));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private ContentResponse exchange(UUID mediaId) {
        long maxFetch = properties.getMaxFetchBytes();
        try {
            return restClient.get()
                    .uri("/internal/media/{mediaId}/content", mediaId)
                    .headers(headers -> {
                        String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
                        if (traceId != null && !traceId.isBlank()) {
                            headers.set(CorrelationIdFilter.HEADER, traceId);
                        }
                    })
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                            throw new MediaContentException(MediaContentException.Reason.NOT_FOUND,
                                    "media not found or not ready");
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE,
                                    "media-service returned status " + response.getStatusCode().value());
                        }
                        Long contentLength = response.getHeaders().getContentLength();
                        if (contentLength != null && contentLength > maxFetch) {
                            throw new MediaContentException(MediaContentException.Reason.TOO_LARGE,
                                    "Content-Length exceeds vision fetch cap");
                        }
                        String type = response.getHeaders().getContentType() == null
                                ? null
                                : response.getHeaders().getContentType().toString();
                        byte[] body;
                        try {
                            body = readBounded(response.getBody(), maxFetch);
                        } catch (java.io.IOException ex) {
                            throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE,
                                    "failed reading media body", ex);
                        }
                        return new ContentResponse(body, type);
                    });
        } catch (MediaContentException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            log.warn("media-service content call failed with status {}", ex.getStatusCode().value());
            throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE,
                    "media-service call failed", ex);
        } catch (RestClientException | java.io.UncheckedIOException ex) {
            log.warn("media-service content call failed: {}", ex.getClass().getSimpleName());
            throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE,
                    "media-service call failed", ex);
        }
    }

    static byte[] readBounded(InputStream in, long maxBytes) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > maxBytes) {
                throw new MediaContentException(MediaContentException.Reason.TOO_LARGE,
                        "media content exceeds vision fetch cap while streaming");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private record ContentResponse(byte[] body, String contentType) {
    }
}
