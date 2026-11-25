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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How often one S3 write reaches the provider (#616 R3), with the client the store config builds and
 * a local socket server in place of the provider. The adapter sends each write once.
 *
 * <p>OkHttp resends a request after a connection failure only on a connection it had used before (on
 * a fresh one to a single address it has no route left), so these tests first open a keep-alive
 * connection with the store's own discovery request, as the bean does at startup. On that reused
 * connection MinIO's switch matters: it turns OkHttp's retry after a connection failure off for the
 * store's PUTs, whose bodies are streams, so a write lost with the connection is sent once; the
 * control shows that the same OkHttp settings without the switch send it twice. OkHttp still follows
 * a 503 that carries {@code Retry-After: 0} with one resend, which carries the same precondition.
 */
class ObjectLockBucketResendTest {

    private static final String KEY = "records/resend-probe.json";
    private static final byte[] BYTES = "{\"probe\":1}".getBytes(StandardCharsets.UTF_8);
    private static final ZonedDateTime UNTIL = ZonedDateTime.ofInstant(Instant.parse("2026-12-01T00:00:00Z"),
            ZoneOffset.UTC);
    private static final String OBJECT_LOCK_CONFIGURATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<ObjectLockConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
            + "<ObjectLockEnabled>Enabled</ObjectLockEnabled></ObjectLockConfiguration>";

    /** The request line and headers of each PUT the server received. */
    private final List<String> puts = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
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
    void aWriteLostWithItsReusedConnectionIsNotSentAgain() {
        dropTheConnection = true;
        ObjectLockBucket bucket = bucket();
        bucket.requireRetentionFeature();

        assertUnavailable(() -> bucket.putIfAbsent(KEY, BYTES, EvidenceRetentionMode.GOVERNANCE, UNTIL));

        assertThat(puts).hasSize(1);
        assertThat(connections).as("the discovery request and the write shared one connection").hasValue(1);
    }

    /** Control: the same OkHttp settings without MinIO's switch send that write again. */
    @Test
    void okHttpAloneSendsAWriteLostWithItsReusedConnectionAgain() throws IOException {
        dropTheConnection = true;
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .callTimeout(Duration.ofSeconds(15))
                .build();
        String base = "http://127.0.0.1:" + server.getLocalPort() + "/probe-bucket";
        try (Response discovery = client.newCall(new Request.Builder().url(base + "?object-lock").build()).execute()) {
            assertThat(discovery.code()).isEqualTo(200);
            assertThat(discovery.body().string()).contains("ObjectLockEnabled");
        }

        Request write = new Request.Builder().url(base + "/" + KEY)
                .put(RequestBody.create(BYTES, MediaType.get("application/json"))).build();
        assertThatThrownBy(() -> client.newCall(write).execute().close()).isInstanceOf(IOException.class);

        assertThat(puts).hasSize(2);
        assertThat(connections).hasValue(2);
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

    /**
     * Keeps each connection open for the next request: the discovery GET is answered with 200; a PUT
     * then has its connection dropped, or is answered 503 with {@code Retry-After: 0}.
     */
    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                connections.incrementAndGet();
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                for (String head = head(in); !head.isEmpty(); head = head(in)) {
                    in.readNBytes(contentLength(head));
                    if (!head.startsWith("PUT ")) {
                        respond(out, "200 OK", OBJECT_LOCK_CONFIGURATION, "");
                        continue;
                    }
                    puts.add(head);
                    if (dropTheConnection) {
                        socket.setSoLinger(true, 0);
                    } else {
                        respond(out, "503 Service Unavailable",
                                "<Error><Code>SlowDown</Code><Message>slow down</Message></Error>",
                                "Retry-After: 0\r\nConnection: close\r\n");
                    }
                    break;
                }
            } catch (IOException ex) {
                // The server was closed, or the client went away: take the next connection.
            }
        }
    }

    private static void respond(OutputStream out, String status, String body, String headers) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: application/xml\r\nContent-Length: " + body.length()
                + "\r\n" + headers + "\r\n" + body).getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** The request line and headers; empty once the client has closed the connection. */
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
