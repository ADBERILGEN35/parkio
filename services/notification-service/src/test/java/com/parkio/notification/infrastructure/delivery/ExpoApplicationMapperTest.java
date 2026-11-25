package com.parkio.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.parkio.notification.application.port.PushMessage;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.infrastructure.config.ExpoPushConfig;
import com.parkio.notification.infrastructure.metrics.PushSenderMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Uses Boot's application message converters and the production RestClient bean. That bean pins its own bounded
 * transport (#595), which replaces the mock an auto-configured MockRestServiceServer would bind, so each test binds
 * the mock to a copy of the production client: same converters, base URL and headers, mocked transport only.
 */
@RestClientTest(value = ExpoPushNotificationSender.class, properties = {
        "parkio.notification.delivery.push.provider=expo",
        "parkio.notification.delivery.push.expo.access-token=synthetic-test-only"})
@AutoConfigureMockRestServiceServer(enabled = false)
@Import({ExpoPushConfig.class, ExpoApplicationMapperTest.Metrics.class})
class ExpoApplicationMapperTest {
    @Autowired RestClient expoPushRestClient;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    SimpleMeterRegistry registry;
    ExpoPushNotificationSender sender;
    MockRestServiceServer server;

    @BeforeEach
    void bindMockTransportToTheProductionClient() {
        RestClient.Builder production = expoPushRestClient.mutate();
        server = MockRestServiceServer.bindTo(production).build();
        registry = new SimpleMeterRegistry();
        sender = new ExpoPushNotificationSender(production.build(), new PushSenderMetrics(registry), "default");
    }

    private double count(String meter) {
        return registry.counter("parkio.notification.push." + meter + ".count").count();
    }

    @TestConfiguration
    static class Metrics {
        @Bean PushSenderMetrics pushSenderMetrics() {
            return new PushSenderMetrics(new SimpleMeterRegistry());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"data\":{\"status\":\"ok\",\"id\":\"ticket-actual-mapper\"}}",
            "{\"data\":[{\"status\":\"ok\",\"id\":\"ticket-actual-mapper\"}]}"})
    void acceptsSingleAndArrayTicketsWithProviderIdentity(String json) {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        var result = sender.send(new PushMessage("ExponentPushToken[synthetic]",
                DevicePlatform.IOS, "Title", "Body"));
        assertThat(result.delivered()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("ticket-actual-mapper");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{", "{\"data\":[]}", "{\"data\":[null]}",
            "{\"data\":{\"status\":\"ok\"}}", "{\"data\":{\"status\":\"ok\",\"id\":\" \"}}",
            "{\"data\":{\"status\":\"unexpected\"}}",
            "{\"data\":[{\"status\":\"ok\",\"id\":\"one\"},{\"status\":\"ok\",\"id\":\"two\"}]}"})
    void unreadableOrUncorrelatedResponseDoesNotInventSuccessOrSafeRetry(String json) {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        var result = sender.send(new PushMessage("ExponentPushToken[synthetic]",
                DevicePlatform.IOS, "Title", "Body"));
        assertThat(result.delivered()).isFalse();
        assertThat(result.providerMessageId()).isNull();
        assertThat(result.failureReason()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
        assertThat(count("outcome_unknown")).isEqualTo(1);
        assertThat(count("provider_unavailable")).isZero();
        server.verify();
    }

    static Stream<IOException> failuresAfterConnecting() {
        return Stream.of(
                new SocketTimeoutException("synthetic read timeout"),
                new HttpTimeoutException("synthetic request timeout"),
                new SocketException("synthetic connection reset"));
    }

    @ParameterizedTest
    @MethodSource("failuresAfterConnecting")
    void transportFailureAfterConnectingHasUnknownAcceptance(IOException failure) {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withException(failure));
        var result = sender.send(new PushMessage("ExponentPushToken[synthetic]",
                DevicePlatform.IOS, "Title", "Body"));
        assertThat(result.delivered()).isFalse();
        assertThat(result.failureReason()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
        assertThat(count("outcome_unknown")).isEqualTo(1);
        assertThat(count("provider_unavailable")).isZero();
        server.verify();
    }

    static Stream<IOException> failuresBeforeTransmission() {
        return Stream.of(
                new ConnectException("synthetic connection refused"),
                new UnknownHostException("synthetic.invalid"),
                new NoRouteToHostException("synthetic no route"),
                new HttpConnectTimeoutException("synthetic connect timeout"),
                new IOException("synthetic client wrapper", new ConnectException("synthetic connection refused")));
    }

    @ParameterizedTest
    @MethodSource("failuresBeforeTransmission")
    void failureToConnectProvesNoTransmissionAndStaysRetryable(IOException failure) {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withException(failure));
        var result = sender.send(new PushMessage("ExponentPushToken[synthetic]",
                DevicePlatform.IOS, "Title", "Body"));
        assertThat(result.delivered()).isFalse();
        assertThat(result.failureReason()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(count("provider_unavailable")).isEqualTo(1);
        assertThat(count("outcome_unknown")).isZero();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdk", "simple"})
    void actualRefusedConnectionStaysRetryable(String clientKind) throws Exception {
        int closedPort;
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        try (var jdkClient = jdkClient()) {
            var actual = realSender("http://127.0.0.1:" + closedPort, clientKind, jdkClient);
            var result = actual.send(new PushMessage("ExponentPushToken[synthetic]", DevicePlatform.IOS, "Title", "Body"));
            assertThat(result.delivered()).isFalse();
            assertThat(result.failureReason()).isEqualTo("PROVIDER_UNAVAILABLE");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdk", "simple"})
    void actualTransportTimeoutAfterPeerAcceptsBodyRemainsUnknown(String clientKind) throws Exception {
        var received = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var peer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var peerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        peer.setExecutor(peerExecutor);
        peer.createContext("/push/send", exchange -> {
            exchange.getRequestBody().readAllBytes();
            received.countDown();
            try {
                release.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        peer.start();
        try (var jdkClient = jdkClient()) {
            var actual = realSender("http://127.0.0.1:" + peer.getAddress().getPort(), clientKind, jdkClient);
            var result = actual.send(new PushMessage("ExponentPushToken[synthetic]", DevicePlatform.IOS, "Title", "Body"));
            assertThat(received.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(result.failureReason()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
        } finally {
            release.countDown();
            peer.stop(0);
            peerExecutor.shutdownNow();
            assertThat(peerExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private static HttpClient jdkClient() {
        // Windows reports a refused loopback connect only after SYN retries (about 2 s).
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * A real client for the JDK (Boot's default here) or the URLConnection request factory. It keeps
     * Jackson 2 like the application's builder ({@code preferred-json-mapper=jackson2}); a plain
     * Framework 7 builder would pick Jackson 3.
     */
    @SuppressWarnings("removal") // Jackson 2 converter, until the Jackson 3 follow-up of #515.
    private ExpoPushNotificationSender realSender(String baseUrl, String clientKind, HttpClient jdkClient) {
        ClientHttpRequestFactory factory;
        if ("jdk".equals(clientKind)) {
            var jdk = new JdkClientHttpRequestFactory(jdkClient);
            jdk.setReadTimeout(Duration.ofMillis(150));
            factory = jdk;
        } else {
            var simple = new SimpleClientHttpRequestFactory();
            simple.setConnectTimeout(Duration.ofSeconds(5));
            simple.setReadTimeout(Duration.ofMillis(150));
            factory = simple;
        }
        var client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .configureMessageConverters(converters -> converters.registerDefaults()
                        .withJsonConverter(new MappingJackson2HttpMessageConverter(mapper)))
                .build();
        return new ExpoPushNotificationSender(client, new PushSenderMetrics(new SimpleMeterRegistry()), "default");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void credentialHttpStatusIsTerminalConfigurationFailure(int status) {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));
        var result = sender.send(new PushMessage("ExponentPushToken[synthetic]",
                DevicePlatform.IOS, "Title", "Body"));
        assertThat(result.failureReason()).isEqualTo("PROVIDER_CONFIGURATION_ERROR");
        assertThat(count("failed")).isEqualTo(1);
        assertThat(count("provider_unavailable")).isZero();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 413, 429, 500, 502, 503})
    void otherHttpRejectionKeepsBoundedRetry(int status) {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));
        var result = sender.send(new PushMessage("ExponentPushToken[synthetic]",
                DevicePlatform.IOS, "Title", "Body"));
        assertThat(result.failureReason()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(count("provider_unavailable")).isEqualTo(1);
        assertThat(count("outcome_unknown")).isZero();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"data\":{\"status\":\"error\",\"message\":\"synthetic prose\",\"details\":{\"error\":\"DeviceNotRegistered\"}}}",
            "{\"data\":[{\"status\":\"error\",\"message\":\"synthetic prose\",\"details\":{\"error\":\"DeviceNotRegistered\"}}]}"})
    void deviceNotRegisteredInEitherTicketShapeIsInvalidToken(String json) {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        var result = sender.send(new PushMessage("ExponentPushToken[synthetic]",
                DevicePlatform.IOS, "Title", "Body"));
        assertThat(result.failureReason()).isEqualTo("INVALID_DEVICE_TOKEN");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"InvalidCredentials", "UNAUTHORIZED", "MismatchSenderId"})
    void credentialFailuresAreNotInvalidTokens(String code) {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withSuccess("{\"data\":{\"status\":\"error\",\"details\":{\"error\":\""
                        + code + "\"}}}", MediaType.APPLICATION_JSON));
        var result = sender.send(new PushMessage("ExponentPushToken[synthetic]",
                DevicePlatform.IOS, "Title", "Body"));
        assertThat(result.failureReason()).isEqualTo("PROVIDER_CONFIGURATION_ERROR");
    }
}
