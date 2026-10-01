package com.parkio.media.application;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Byte-level HTTP/1.1 relay between the service and MinIO, standing in for any network path that
 * can deliver a request after its client gave up (a buffering proxy or load balancer, a delayed
 * TCP path). Requests are forwarded unchanged, Host header included, so SigV4 signatures stay
 * valid. One request matching an armed rule can be held instead (delivered later on a fresh
 * connection by {@link #release()}) or dropped (the connection is closed after the full request
 * was received, without a reply). Every request line is recorded.
 */
final class DelayingStorageRelay {

    private enum Mode { HOLD, DROP }

    private final ServerSocket server;
    private final String upstreamHost;
    private final int upstreamPort;
    private final ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "delaying-storage-relay");
        thread.setDaemon(true);
        return thread;
    });
    private final List<String> requestLines = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private volatile Mode mode;
    private volatile String method;
    private volatile String pathFragment;
    private volatile byte[] held;
    private volatile CountDownLatch matched = new CountDownLatch(1);

    DelayingStorageRelay(String upstreamHost, int upstreamPort) throws IOException {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        threads.submit(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    int upstreamPort() {
        return upstreamPort;
    }

    /** Holds the next request whose line starts with {@code method} and contains {@code pathFragment}. */
    void holdNext(String method, String pathFragment) {
        arm(Mode.HOLD, method, pathFragment);
    }

    /** Closes the connection of the next matching request after receiving it in full, without replying. */
    void dropNext(String method, String pathFragment) {
        arm(Mode.DROP, method, pathFragment);
    }

    boolean awaitMatched(int seconds) throws InterruptedException {
        return matched.await(seconds, TimeUnit.SECONDS);
    }

    /** Request lines seen so far that start with {@code method} and contain {@code pathFragment}. */
    long count(String method, String pathFragment) {
        return requestLines.stream().filter(line -> line.startsWith(method + " ") && line.contains(pathFragment)).count();
    }

    /** What the relay saw: its port, upstream, the last request lines and any I/O error (for failure messages). */
    String diagnostics() {
        int from = Math.max(0, requestLines.size() - 12);
        return "relay 127.0.0.1:" + port() + " -> " + upstreamHost + ":" + upstreamPort + " accepting=" + !server.isClosed()
                + " requests=" + requestLines.subList(from, requestLines.size()) + " errors=" + errors;
    }

    /** Delivers the held request to MinIO now, on a fresh connection, and returns the reply's status line. */
    String release() throws IOException {
        byte[] request = held;
        if (request == null) {
            return "nothing held";
        }
        held = null;
        try (Socket socket = new Socket(upstreamHost, upstreamPort)) {
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                    .readLine();
        }
    }

    private void arm(Mode mode, String method, String pathFragment) {
        this.held = null;
        this.matched = new CountDownLatch(1);
        this.method = method;
        this.mode = mode;
        this.pathFragment = pathFragment;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                threads.submit(() -> relay(client));
            } catch (IOException closed) {
                errors.add("accept: " + closed);
                return;
            }
        }
    }

    private void relay(Socket client) {
        try (client; Socket upstream = new Socket(upstreamHost, upstreamPort)) {
            threads.submit(() -> pump(upstream, client));
            InputStream in = client.getInputStream();
            OutputStream out = upstream.getOutputStream();
            while (true) {
                byte[] head = readHead(in);
                if (head == null) {
                    return;
                }
                String text = new String(head, StandardCharsets.ISO_8859_1);
                byte[] body = in.readNBytes(contentLength(text));
                String requestLine = text.substring(0, text.indexOf("\r\n"));
                requestLines.add(requestLine);
                String fragment = pathFragment;
                if (fragment != null && requestLine.startsWith(method + " ") && requestLine.contains(fragment)) {
                    pathFragment = null;
                    if (mode == Mode.HOLD) {
                        ByteArrayOutputStream whole = new ByteArrayOutputStream();
                        whole.write(head);
                        whole.write(body);
                        held = whole.toByteArray();
                        matched.countDown();
                        while (in.read() != -1) {
                            // never forwarded on this connection; wait until the client gives up
                        }
                    } else {
                        matched.countDown();
                    }
                    return;
                }
                out.write(head);
                out.write(body);
                out.flush();
            }
        } catch (IOException | RuntimeException failure) {
            errors.add("relay: " + failure);
        }
    }

    private static void pump(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException ignored) {
            // connection closed
        }
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matchedBytes = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            boolean next = (b == '\r' && (matchedBytes == 0 || matchedBytes == 2))
                    || (b == '\n' && (matchedBytes == 1 || matchedBytes == 3));
            matchedBytes = next ? matchedBytes + 1 : (b == '\r' ? 1 : 0);
            if (matchedBytes == 4) {
                return head.toByteArray();
            }
        }
        return null;
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                throw new IllegalStateException("relay does not support chunked request bodies");
            }
            if (lower.startsWith("content-length:")) {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return 0;
    }
}
