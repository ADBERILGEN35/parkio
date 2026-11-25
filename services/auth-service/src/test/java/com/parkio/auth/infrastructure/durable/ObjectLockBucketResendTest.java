package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How often one S3 write reaches the provider (#616 R3), with the client the store config builds and
 * a local socket server in place of the provider. The adapter sends each write once. MinIO turns
 * OkHttp's retry after a connection failure off for these PUTs, so a connection lost after the
 * request is not retried; OkHttp still follows a 503 that carries {@code Retry-After: 0} with one
 * resend. That resend carries the same precondition, so it is harmless.
 */
class ObjectLockBucketResendTest {

    private static final String KEY = "records/resend-probe.json";
    private static final byte[] BYTES = "{\"probe\":1}".getBytes(StandardCharsets.UTF_8);
    private static final ZonedDateTime UNTIL = ZonedDateTime.ofInstant(Instant.parse("2026-12-01T00:00:00Z"),
            ZoneOffset.UTC);

    /** The request line and headers of each PUT the server received. */
    private final List<String> puts = new CopyOnWriteArrayList<>();
    private volatile boolean dropTheConnection;
    private ServerSocket server;

    @BeforeEach
    void start() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("s3-resend-probe").start(this::serve);
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    @Test
    void aConnectionLostAfterTheWriteIsNotRetried() {
        dropTheConnection = true;

        assertUnavailable(() -> bucket().putIfAbsent(KEY, BYTES, EvidenceRetentionMode.GOVERNANCE, UNTIL));

        assertThat(puts).hasSize(1);
    }

    @Test
    void aServiceUnavailableAnswerWithRetryAfterZeroIsSentOnceMoreWithTheSamePrecondition() {
        assertUnavailable(() -> bucket().putIfAbsent(KEY, BYTES, EvidenceRetentionMode.GOVERNANCE, UNTIL));

        assertThat(puts).hasSize(2).allSatisfy(head -> assertThat(head.toLowerCase(Locale.ROOT))
                .startsWith("put /probe-bucket/" + KEY)
                .contains("\r\nif-none-match: *\r\n"));
    }

    private ObjectLockBucket bucket() {
        ObjectLockStoreProperties properties = new ObjectLockStoreProperties();
        properties.setEndpoint("http://127.0.0.1:" + server.getLocalPort());
        properties.setAccessKey("resend-probe-access");
        properties.setSecretKey("resend-probe-secret");
        return new ObjectLockBucket(ObjectLockDurableStoreConfig.client(properties), "probe-bucket");
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
    }

    /** Reads each request whole, then drops the connection or answers 503 with {@code Retry-After: 0}. */
    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                String head = head(in);
                in.readNBytes(contentLength(head));
                if (head.startsWith("PUT ")) {
                    puts.add(head);
                }
                if (dropTheConnection) {
                    socket.setSoLinger(true, 0);
                    continue;
                }
                String body = "<Error><Code>SlowDown</Code><Message>slow down</Message></Error>";
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 0\r\nContent-Type: application/xml\r\n"
                        + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException ex) {
                // The server was closed, or the client went away: take the next connection.
            }
        }
    }

    private static String head(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int next;
        while ((next = in.read()) != -1) {
            head.append((char) next);
            if (head.length() >= 4 && head.lastIndexOf("\r\n\r\n") == head.length() - 4) {
                break;
            }
        }
        return head.toString();
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                return Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        return 0;
    }
}
