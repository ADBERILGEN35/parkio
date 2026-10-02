package com.parkio.media.application;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * connection by {@link #release()}), dropped (the connection is closed after the full request
 * was received, without a reply), or answered: the next matching requests get scripted replies
 * instead of being forwarded, and the first one is held, as a store or path that answered a
 * request yet still applies it later would. Every request line is recorded with its body size.
 */
final class DelayingStorageRelay {

    private enum Mode { HOLD, DROP, ANSWER }

    private static final String SAME_TARGET = "<same request target>";

    /** A reply the relay sends instead of forwarding a request. */
    record Reply(int status, String reason, Map<String, String> headers, String body, boolean stopAccepting) {

        /** An S3 error document, as MinIO sends it. */
        static Reply s3Error(int status, String reason, String code) {
            return new Reply(status, reason, Map.of("Content-Type", "application/xml"),
                    "<Error><Code>" + code + "</Code><Message>relay fixture</Message><Resource>/fixture</Resource>"
                            + "<RequestId>relay</RequestId></Error>", false);
        }

        /** A redirect back to the request's own target, with an empty body. */
        static Reply redirectToSameTarget(int status) {
            return new Reply(status, "Redirect", Map.of("Location", SAME_TARGET), "", false);
        }

        Reply withHeader(String name, String value) {
            Map<String, String> more = new LinkedHashMap<>(headers);
            more.put(name, value);
            return new Reply(status, reason, more, body, stopAccepting);
        }

        /** After this reply the connection closes and the relay accepts no connection until resumed. */
        Reply thenRefuseConnections() {
            return new Reply(status, reason, headers, body, true);
        }

        byte[] bytes(String requestTarget) {
            byte[] content = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " " + reason + "\r\n");
            headers.forEach((name, value) -> head.append(name).append(": ")
                    .append(SAME_TARGET.equals(value) ? requestTarget : value).append("\r\n"));
            head.append("Content-Length: ").append(content.length).append("\r\n");
            if (stopAccepting) {
                head.append("Connection: close\r\n");
            }
            head.append("\r\n");
            ByteArrayOutputStream whole = new ByteArrayOutputStream();
            whole.writeBytes(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            whole.writeBytes(content);
            return whole.toByteArray();
        }
    }

    private record Seen(String requestLine, int bodyBytes) {
    }

    private final int port;
    private final String upstreamHost;
    private final int upstreamPort;
    private final ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "delaying-storage-relay");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Seen> requests = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;
    private volatile Mode mode;
    private volatile String method;
    private volatile String pathFragment;
    private volatile List<Reply> replies = List.of();
    private volatile byte[] held;
    private volatile CountDownLatch matched = new CountDownLatch(1);

    DelayingStorageRelay(String upstreamHost, int upstreamPort) throws IOException {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.port = server.getLocalPort();
        ServerSocket listening = server;
        threads.submit(() -> acceptLoop(listening));
    }

    int port() {
        return port;
    }

    int upstreamPort() {
        return upstreamPort;
    }

    /** Holds the next request whose line starts with {@code method} and contains {@code pathFragment}. */
    void holdNext(String method, String pathFragment) {
        arm(Mode.HOLD, method, pathFragment, List.of());
    }

    /** Closes the connection of the next matching request after receiving it in full, without replying. */
    void dropNext(String method, String pathFragment) {
        arm(Mode.DROP, method, pathFragment, List.of());
    }

    /**
     * Answers the next matching requests with {@code replies}, one each, instead of forwarding them.
     * The first answered request is held: {@link #release()} still delivers it to MinIO later.
     * Requests beyond the replies are forwarded again.
     */
    void answerNext(String method, String pathFragment, Reply... replies) {
        arm(Mode.ANSWER, method, pathFragment, List.of(replies));
    }

    /** Accepts connections again on the same port after a reply that refused them. */
    synchronized void resumeAccepting() throws IOException {
        if (!server.isClosed()) {
            return;
        }
        ServerSocket reopened = new ServerSocket();
        reopened.setReuseAddress(true);
        reopened.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
        server = reopened;
        threads.submit(() -> acceptLoop(reopened));
    }

    boolean awaitMatched(int seconds) throws InterruptedException {
        return matched.await(seconds, TimeUnit.SECONDS);
    }

    /** Request lines seen so far that start with {@code method} and contain {@code pathFragment}. */
    long count(String method, String pathFragment) {
        return seen(method, pathFragment).size();
    }

    /** Body bytes the client sent in those requests. */
    long bodyBytes(String method, String pathFragment) {
        return seen(method, pathFragment).stream().mapToLong(Seen::bodyBytes).sum();
    }

    /** Listing requests (ListObjectVersions) seen so far for the bucket. */
    long listings(String bucket) {
        return requests.stream().map(Seen::requestLine)
                .filter(line -> line.startsWith("GET /" + bucket + "?") && line.contains("versions")).count();
    }

    /** What the relay saw: its port, upstream, the last request lines and any I/O error (for failure messages). */
    String diagnostics() {
        List<String> lines = new ArrayList<>(requests.stream().map(Seen::requestLine).toList());
        int from = Math.max(0, lines.size() - 12);
        return "relay 127.0.0.1:" + port + " -> " + upstreamHost + ":" + upstreamPort + " accepting=" + !server.isClosed()
                + " requests=" + lines.subList(from, lines.size()) + " errors=" + errors;
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

    private List<Seen> seen(String method, String pathFragment) {
        return requests.stream()
                .filter(seen -> seen.requestLine().startsWith(method + " ") && seen.requestLine().contains(pathFragment))
                .toList();
    }

    private synchronized void arm(Mode mode, String method, String pathFragment, List<Reply> replies) {
        this.held = null;
        this.matched = new CountDownLatch(1);
        this.method = method;
        this.mode = mode;
        this.replies = new CopyOnWriteArrayList<>(replies);
        this.pathFragment = pathFragment;
    }

    /** The armed rule's action for this request, or null to forward it; consumes one-shot rules. */
    private synchronized Mode take(String requestLine) {
        String fragment = pathFragment;
        if (fragment == null || !requestLine.startsWith(method + " ") || !requestLine.contains(fragment)) {
            return null;
        }
        if (mode != Mode.ANSWER || replies.size() <= 1) {
            pathFragment = null;
        }
        return mode;
    }

    private void acceptLoop(ServerSocket listening) {
        while (!listening.isClosed()) {
            try {
                Socket client = listening.accept();
                threads.submit(() -> relay(client));
            } catch (IOException closed) {
                if (!listening.isClosed()) {
                    errors.add("accept: " + closed);
                }
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
                requests.add(new Seen(requestLine, body.length));
                Mode action = take(requestLine);
                if (action == Mode.ANSWER) {
                    if (!answer(client, head, body, requestLine.split(" ")[1])) {
                        return;
                    }
                    continue;
                }
                if (action != null) {
                    if (action == Mode.HOLD) {
                        held = concat(head, body);
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

    /** Sends the next scripted reply; false if the connection must close afterwards. */
    private boolean answer(Socket client, byte[] head, byte[] body, String requestTarget) throws IOException {
        Reply reply;
        synchronized (this) {
            reply = replies.remove(0);
            if (held == null && matched.getCount() > 0) {
                held = concat(head, body);
            }
        }
        matched.countDown();
        client.getOutputStream().write(reply.bytes(requestTarget));
        client.getOutputStream().flush();
        if (reply.stopAccepting()) {
            server.close();
            return false;
        }
        return true;
    }

    private static byte[] concat(byte[] head, byte[] body) {
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        whole.writeBytes(head);
        whole.writeBytes(body);
        return whole.toByteArray();
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
