package com.parkio.aivalidation.infrastructure.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.aivalidation.infrastructure.config.VisionProperties;
import com.parkio.aivalidation.infrastructure.web.CorrelationIdFilter;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

/**
 * Wire-level tests for {@link MediaContentHttpClient} against a local HTTP stub:
 * gateway-auth + correlation headers, vision rendition, and fail-closed mapping.
 */
class MediaContentHttpClientTest {

    private static final String SECRET = "test-internal-secret";
    private static final byte[] JPEG = VisionTestImages.jpeg(800, 600);

    private HttpServer server;
    private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();
    private volatile int responseStatus = 200;
    private volatile byte[] responseBody = JPEG;
    private volatile String responseContentType = "image/jpeg";

    private record RecordedRequest(String path, String gatewayAuth, String correlationId) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            recorded.add(new RecordedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Gateway-Auth"),
                    exchange.getRequestHeaders().getFirst(CorrelationIdFilter.HEADER)));
            exchange.getResponseHeaders().set("Content-Type", responseContentType);
            exchange.sendResponseHeaders(responseStatus, responseBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBody);
            }
        });
        recorded.clear();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    private MediaContentHttpClient client() {
        return client(new VisionProperties());
    }

    private MediaContentHttpClient client(VisionProperties properties) {
        properties.getMediaClient().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.getMediaClient().setConnectTimeout(Duration.ofSeconds(1));
        properties.getMediaClient().setReadTimeout(Duration.ofSeconds(2));
        return new MediaContentHttpClient(RestClient.builder(), properties, SECRET);
    }

    @Test
    void fetchesAndDownscalesWithAuthAndCorrelationHeaders() {
        UUID mediaId = UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");

        MediaContentFetcher.MediaContent content = client().fetch(mediaId);

        assertThat(content.contentType()).isEqualTo("image/jpeg");
        assertThat(content.bytes().length).isGreaterThan(0);
        assertThat(content.bytes()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(content.bytes()[1] & 0xFF).isEqualTo(0xD8);
        assertThat(recorded).extracting(RecordedRequest::path)
                .contains("/internal/media/" + mediaId + "/content",
                        "/internal/media/" + mediaId + "/metadata");
        RecordedRequest contentReq = recorded.stream()
                .filter(r -> r.path().endsWith("/content"))
                .findFirst()
                .orElseThrow();
        assertThat(contentReq.gatewayAuth()).isEqualTo(SECRET);
        assertThat(contentReq.correlationId()).isEqualTo("corr-123");
    }

    @Test
    void highResolutionSourceIsAcceptedAfterRendition() {
        responseBody = VisionTestImages.jpeg(4000, 3000);
        VisionProperties properties = new VisionProperties();
        properties.setMaxImageBytes(1_500_000L);

        MediaContentFetcher.MediaContent content = client(properties).fetch(UUID.randomUUID());
        assertThat(content.bytes().length).isLessThanOrEqualTo(1_500_000);
    }

    @Test
    void notFoundMapsToNotFoundReason() {
        responseStatus = 404;
        responseBody = "{\"code\":\"MEDIA_NOT_FOUND\"}".getBytes();
        responseContentType = "application/json";

        assertThatThrownBy(() -> client().fetch(UUID.randomUUID()))
                .isInstanceOfSatisfying(MediaContentException.class, ex ->
                        assertThat(ex.reason()).isEqualTo(MediaContentException.Reason.NOT_FOUND));
    }

    @Test
    void serverErrorMapsToUnavailable() {
        responseStatus = 500;

        assertThatThrownBy(() -> client().fetch(UUID.randomUUID()))
                .isInstanceOfSatisfying(MediaContentException.class, ex ->
                        assertThat(ex.reason()).isEqualTo(MediaContentException.Reason.UNAVAILABLE));
    }

    @Test
    void unreachableServerMapsToUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> client().fetch(UUID.randomUUID()))
                .isInstanceOfSatisfying(MediaContentException.class, ex ->
                        assertThat(ex.reason()).isEqualTo(MediaContentException.Reason.UNAVAILABLE));
    }

    @Test
    void oversizedFetchCapMapsToTooLarge() {
        VisionProperties properties = new VisionProperties();
        properties.setMaxFetchBytes(2_000);
        responseBody = VisionTestImages.jpeg(1200, 900);
        assertThat(responseBody.length).isGreaterThan(2_000);

        assertThatThrownBy(() -> client(properties).fetch(UUID.randomUUID()))
                .isInstanceOfSatisfying(MediaContentException.class, ex ->
                        assertThat(ex.reason()).isEqualTo(MediaContentException.Reason.TOO_LARGE));
    }

    @Test
    void readBoundedRejectsStreamPastCap() {
        byte[] data = new byte[200];
        assertThatThrownBy(() -> MediaContentHttpClient.readBounded(
                new java.io.ByteArrayInputStream(data), 50))
                .isInstanceOfSatisfying(MediaContentException.class, ex ->
                        assertThat(ex.reason()).isEqualTo(MediaContentException.Reason.TOO_LARGE));
    }

    @Test
    void disallowedContentTypeMapsToUnsupported() {
        responseContentType = "image/gif";

        assertThatThrownBy(() -> client().fetch(UUID.randomUUID()))
                .isInstanceOfSatisfying(MediaContentException.class, ex ->
                        assertThat(ex.reason()).isEqualTo(MediaContentException.Reason.UNSUPPORTED_TYPE));
    }

    @Test
    void emptyBodyMapsToUnavailable() {
        responseBody = new byte[0];

        assertThatThrownBy(() -> client().fetch(UUID.randomUUID()))
                .isInstanceOfSatisfying(MediaContentException.class, ex ->
                        assertThat(ex.reason()).isEqualTo(MediaContentException.Reason.UNAVAILABLE));
    }
}
