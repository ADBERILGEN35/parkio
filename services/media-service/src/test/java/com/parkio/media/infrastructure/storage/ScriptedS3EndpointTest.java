package com.parkio.media.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.media.infrastructure.storage.ScriptedS3Endpoint.Reply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fixture's refuse-connections guarantee that {@link MinioResendClassificationTest} relies on:
 * once a client has received a reply scripted with {@code thenRefuseConnections()}, every new
 * connection is refused. Closing a listener does not stop a thread already blocked in
 * {@code accept()} from accepting a connection that arrives before it wakes up, so the guarantee
 * only holds if the reply is sent after that thread has finished. Repeats the round trip because
 * the window is short (CI run 36996196233 lost it once).
 */
class ScriptedS3EndpointTest {

    private static final int ROUNDS = 2000;

    @Test
    void aConnectionAfterARefusingReplyIsAlwaysRefused() throws Exception {
        List<String> accepted = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                    Reply.s3Error(503, "SlowDown").thenRefuseConnections())) {
                assertThat(roundTrip(endpoint.port())).startsWith("HTTP/1.1 503");
                try (Socket again = new Socket()) {
                    again.connect(new InetSocketAddress("127.0.0.1", endpoint.port()), 1000);
                    accepted.add("round " + round + ": connected after the refusing reply");
                } catch (ConnectException refused) {
                    // expected
                }
            }
        }
        assertThat(accepted).as("connections accepted after a refusing reply").isEmpty();
    }

    private static String roundTrip(int port) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write("PUT /bucket/key HTTP/1.1\r\nHost: fixture\r\nContent-Length: 0\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int n;
            while ((n = in.read(buffer)) != -1) { // the reply carries Connection: close
                reply.write(buffer, 0, n);
            }
            return reply.toString(StandardCharsets.ISO_8859_1);
        }
    }
}
