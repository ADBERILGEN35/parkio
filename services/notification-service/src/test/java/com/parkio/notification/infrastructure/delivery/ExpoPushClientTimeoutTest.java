package com.parkio.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.parkio.notification.application.port.PushMessage;
import com.parkio.notification.application.port.PushSendResult;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.infrastructure.config.ExpoPushConfig;
import com.parkio.notification.infrastructure.metrics.PushSenderMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * #595: the client {@link ExpoPushConfig} builds bounds both the connection and the exchange. Without the bounds, a
 * send to an Expo that never answers blocks the delivery tick, and the claims it holds, until the process restarts.
 * The tests use the production beans over a real loopback transport, never the network.
 */
@RestClientTest(value = ExpoPushNotificationSender.class, properties = {
        "parkio.notification.delivery.push.provider=expo",
        "parkio.notification.delivery.push.expo.access-token=synthetic-test-only",
        "parkio.notification.delivery.push.expo.connect-timeout=300ms",
        "parkio.notification.delivery.push.expo.read-timeout=1500ms"})
@AutoConfigureMockRestServiceServer(enabled = false)
@Import({ExpoPushConfig.class, ExpoPushClientTimeoutTest.Metrics.class})
class ExpoPushClientTimeoutTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(1500);
    /** Scheduling slack for a loaded runner. An unbounded client never returns at all. */
    private static final Duration SLACK = Duration.ofSeconds(5);

    private static final SilentPeer SILENT_EXPO = SilentPeer.start();

    @DynamicPropertySource
    static void expoBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("parkio.notification.delivery.push.expo.base-url", SILENT_EXPO::baseUrl);
    }

    @AfterAll
    static void stopSilentExpo() {
        SILENT_EXPO.close();
    }

    @TestConfiguration
    static class Metrics {
        @Bean
        SimpleMeterRegistry pushMeterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        PushSenderMetrics pushSenderMetrics(SimpleMeterRegistry pushMeterRegistry) {
            return new PushSenderMetrics(pushMeterRegistry);
        }
    }

    @Autowired ExpoPushNotificationSender sender;
    @Autowired SimpleMeterRegistry pushMeterRegistry;
    @Autowired RestClient expoPushRestClient;

    @Test
    void responseThatNeverCompletesFailsWithinTheReadBoundAsUnknownOutcome() throws Exception {
        long started = System.nanoTime();
        PushSendResult result = assertTimeoutPreemptively(READ_TIMEOUT.plus(SLACK), () -> sender.send(message()),
                "a send to an Expo that never answers must end at the read timeout");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // Expo received the request, so it may have accepted it: unknown, never retryable.
        assertThat(SILENT_EXPO.awaitRequestHead(Duration.ofSeconds(1))).isTrue();
        assertThat(SILENT_EXPO.received()).startsWith("POST /push/send ");
        assertThat(result.delivered()).isFalse();
        assertThat(result.failureReason()).isEqualTo(ExpoPushNotificationSender.FAILURE_OUTCOME_UNKNOWN);
        assertThat(elapsed).isGreaterThanOrEqualTo(READ_TIMEOUT);
        assertThat(pushMeterRegistry.counter("parkio.notification.push.outcome_unknown.count").count()).isEqualTo(1);
        assertThat(pushMeterRegistry.counter("parkio.notification.push.provider_unavailable.count").count()).isZero();
    }

    @Test
    void connectionThatNeverOpensFailsWithinTheConnectBoundAndStaysRetryable() throws Exception {
        if (OS.current() != OS.LINUX) {
            // #641: only Linux keeps such a connect pending; Windows refuses it at once.
            Assumptions.abort("a full accept queue stalls a connect only on Linux, not on " + OS.current());
        }
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (FullBacklogPeer unanswered = FullBacklogPeer.start()) {
            // The production transport, aimed at a listener whose accept queue is full.
            ExpoPushNotificationSender sender = new ExpoPushNotificationSender(
                    expoPushRestClient.mutate().baseUrl(unanswered.baseUrl()).build(),
                    new PushSenderMetrics(registry), "default");

            long started = System.nanoTime();
            PushSendResult result = assertTimeoutPreemptively(READ_TIMEOUT.plus(SLACK), () -> sender.send(message()),
                    "a connection that never opens must end at the connect timeout");
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            // No connection, so Expo never received the request: bounded retry cannot duplicate it.
            assertThat(result.delivered()).isFalse();
            assertThat(result.failureReason()).isEqualTo(ExpoPushNotificationSender.FAILURE_PROVIDER_UNAVAILABLE);
            assertThat(elapsed).isGreaterThanOrEqualTo(CONNECT_TIMEOUT).isLessThan(READ_TIMEOUT);
            assertThat(registry.counter("parkio.notification.push.provider_unavailable.count").count()).isEqualTo(1);
            assertThat(registry.counter("parkio.notification.push.outcome_unknown.count").count()).isZero();
        }
    }

    private static PushMessage message() {
        return new PushMessage("ExponentPushToken[synthetic]", DevicePlatform.IOS, "Title", "Body");
    }

    /**
     * A loopback listener that never accepts, with its accept queue filled. Linux then drops every new SYN, so a
     * connect stays pending until the client's connect timeout ends it. Other platforms need not: Windows refuses
     * the connect instead, so the test that uses this peer runs only on Linux.
     */
    private static final class FullBacklogPeer implements AutoCloseable {
        private final ServerSocket listener;
        private final List<Socket> queued = new ArrayList<>();

        private FullBacklogPeer(ServerSocket listener) {
            this.listener = listener;
        }

        static FullBacklogPeer start() throws IOException {
            FullBacklogPeer peer = new FullBacklogPeer(new ServerSocket(0, 1, InetAddress.getLoopbackAddress()));
            InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    peer.listener.getLocalPort());
            for (int i = 0; i < 16; i++) {
                Socket filler = new Socket();
                try {
                    filler.connect(address, 200);
                    peer.queued.add(filler);
                } catch (SocketTimeoutException full) {
                    filler.close();
                    return peer;
                }
            }
            peer.close();
            throw new IllegalStateException("the accept queue never filled; this platform cannot stall a connect");
        }

        String baseUrl() {
            return "http://127.0.0.1:" + listener.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            for (Socket socket : queued) {
                socket.close();
            }
            listener.close();
        }
    }
}
