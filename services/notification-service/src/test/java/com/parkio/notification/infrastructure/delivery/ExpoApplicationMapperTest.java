package com.parkio.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

import com.parkio.notification.application.port.PushMessage;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.infrastructure.config.ExpoPushConfig;
import com.parkio.notification.infrastructure.metrics.PushSenderMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import java.net.SocketTimeoutException;

/** Uses Boot's application message converters and the production RestClient bean. */
@RestClientTest(value = ExpoPushNotificationSender.class, properties = {
        "parkio.notification.delivery.push.provider=expo",
        "parkio.notification.delivery.push.expo.access-token=synthetic-test-only"})
@Import({ExpoPushConfig.class, ExpoApplicationMapperTest.Metrics.class})
class ExpoApplicationMapperTest {
    @Autowired ExpoPushNotificationSender sender;
    @Autowired MockRestServiceServer server;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

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
        server.verify();
    }

    @Test
    void transportTimeoutHasUnknownAcceptance() {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withException(new SocketTimeoutException("synthetic timeout")));
        var result = sender.send(new PushMessage("ExponentPushToken[synthetic]",
                DevicePlatform.IOS, "Title", "Body"));
        assertThat(result.failureReason()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
    }

    @Test
    void actualTransportTimeoutAfterPeerAcceptsBodyRemainsUnknown() throws Exception {
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
        try {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(500);
            factory.setReadTimeout(150);
            var client = org.springframework.web.client.RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + peer.getAddress().getPort())
                    .requestFactory(factory)
                    .messageConverters(converters -> {
                        converters.clear();
                        converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper));
                    }).build();
            var actual = new ExpoPushNotificationSender(client, new PushSenderMetrics(new SimpleMeterRegistry()), "default");
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
