package com.parkio.auth.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.domain.EmailLocale;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Distinguishes a thrown mock transport failure from a real bounded HTTP
 * read-timeout against a hanging local socket.
 */
class ResendEmailSenderBoundedTimeoutTest {

    private ServerSocket server;
    private ExecutorService executor;

    @AfterEach
    void tearDown() throws IOException {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (server != null && !server.isClosed()) {
            server.close();
        }
    }

    @Test
    void connectAndReadTimeoutsAreFiniteByDefault() {
        TransactionalEmailProperties.Resend resend = new TransactionalEmailProperties().getResend();
        assertThat(resend.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(resend.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(resend.getConnectTimeout().isZero()).isFalse();
        assertThat(resend.getConnectTimeout().isNegative()).isFalse();
        assertThat(resend.getReadTimeout().isZero()).isFalse();
        assertThat(resend.getReadTimeout().isNegative()).isFalse();
    }

    @Test
    void realBoundedReadTimeoutFailsWithoutWaitingUnbounded() throws Exception {
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = server.getLocalPort();
        executor = Executors.newSingleThreadExecutor();
        AtomicBoolean accepted = new AtomicBoolean(false);
        Future<?> holder = executor.submit(() -> {
            try (Socket client = server.accept()) {
                accepted.set(true);
                // Accept TCP but never complete an HTTP response body/headers.
                Thread.sleep(30_000L);
                try (OutputStream ignored = client.getOutputStream()) {
                    // keep socket open until interrupted
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // expected when test closes the server
            }
        });

        Duration connectTimeout = Duration.ofMillis(400);
        Duration readTimeout = Duration.ofMillis(400);
        TransactionalEmailProperties properties = new TransactionalEmailProperties();
        properties.setFrom("Parkio <verify@example.com>");
        properties.setReplyTo("support@example.com");
        properties.getResend().setApiKey("re_test_key");
        properties.getResend().setBaseUrl("http://127.0.0.1:" + port);
        properties.getResend().setConnectTimeout(connectTimeout);
        properties.getResend().setReadTimeout(readTimeout);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        RestClient client = RestClient.builder()
                .baseUrl(properties.getResend().getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer re_test_key")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        ResendEmailSender sender = new ResendEmailSender(
                client,
                properties,
                new EmailDeliveryMetrics(new SimpleMeterRegistry()),
                "https://app.example.com/verify-email",
                "https://app.example.com/reset-password");

        long started = System.nanoTime();
        assertThatThrownBy(() -> sender.sendVerificationLink("user@example.com", "tok-timeout", EmailLocale.EN))
                .isInstanceOf(EmailDeliveryException.class);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(accepted).as("server accepted the outbound connection").isTrue();
        // Bound is readTimeout (+ small scheduling slack). Must not approach the 30s hold.
        assertThat(elapsedMs).isLessThan(5_000L);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(readTimeout.toMillis() / 2);
        holder.cancel(true);
    }
}
