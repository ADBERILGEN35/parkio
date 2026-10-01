package com.parkio.media.infrastructure.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An HTTP/1.1 endpoint on the IPv4 loopback that answers each request with the next scripted
 * reply, the way an S3 store or a network path might, and records every request it received
 * with its body size. A reply can close the listener before it is sent, so any further
 * connection is refused.
 */
public final class ScriptedS3Endpoint implements AutoCloseable {

    private static final String SAME_TARGET = "<same request target>";

    /** A scripted reply. */
    public record Reply(int status, Map<String, String> headers, String body, boolean refuseConnectionsFirst) {

        /** An S3 error document. */
        public static Reply s3Error(int status, String code) {
            return new Reply(status, Map.of("Content-Type", "application/xml"),
                    "<Error><Code>" + code + "</Code><Message>scripted</Message><Resource>/fixture</Resource>"
                            + "<RequestId>scripted</RequestId></Error>", false);
        }

        /** A redirect back to the request's own target, with an empty body. */
        public static Reply redirectToSameTarget(int status) {
            return new Reply(status, Map.of("Location", SAME_TARGET), "", false);
        }

        /** No reply at all: the request is read in full and the connection then stays silent. */
        public static Reply none() {
            return new Reply(0, Map.of(), "", false);
        }

        /** A plain success with an ETag (a PUT or HEAD the store accepted). */
        public static Reply ok() {
            return new Reply(200, Map.of("ETag", "\"0123456789abcdef0123456789abcdef\""), "", false);
        }

        public Reply with(String header, String value) {
            Map<String, String> more = new LinkedHashMap<>(headers);
            more.put(header, value);
            return new Reply(status, more, body, refuseConnectionsFirst);
        }

        /** Closes the listener before this reply is sent: the connection closes after it, and any new one is refused. */
        public Reply thenRefuseConnections() {
            return new Reply(status, headers, body, true).with("Connection", "close");
        }
    }

    private final ServerSocket server;
    private final Deque<Reply> script;
    private final List<String> requestLines = new CopyOnWriteArrayList<>();
    private final List<Integer> bodySizes = new CopyOnWriteArrayList<>();

    public ScriptedS3Endpoint(Reply... replies) throws IOException {
        this.script = new ArrayDeque<>(List.of(replies));
        this.server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(this::acceptLoop, "scripted-s3-endpoint");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    public int port() {
        return server.getLocalPort();
    }

    public int requests() {
        return requestLines.size();
    }

    public long bodyBytes() {
        return bodySizes.stream().mapToLong(Integer::longValue).sum();
    }

    public List<String> requestLines() {
        return List.copyOf(requestLines);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread connection = new Thread(() -> serve(client), "scripted-s3-connection");
                connection.setDaemon(true);
                connection.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            while (true) {
                byte[] head = readHead(in);
                if (head == null) {
                    return;
                }
                String text = new String(head, StandardCharsets.ISO_8859_1);
                byte[] body = in.readNBytes(contentLength(text));
                String requestLine = text.substring(0, text.indexOf("\r\n"));
                requestLines.add(requestLine);
                bodySizes.add(body.length);
                Reply reply = next();
                if (reply.status() == 0) {
                    while (in.read() != -1) {
                        // silent until the client gives up
                    }
                    return;
                }
                if (reply.refuseConnectionsFirst()) {
                    server.close();
                }
                out.write(bytes(reply, requestLine.split(" ")[1], requestLine.startsWith("HEAD ")));
                out.flush();
                if ("close".equals(reply.headers().get("Connection"))) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // the client went away
        }
    }

    private synchronized Reply next() {
        return script.isEmpty() ? Reply.s3Error(500, "InternalError") : script.poll();
    }

    private static byte[] bytes(Reply reply, String requestTarget, boolean head) {
        byte[] content = reply.body().getBytes(StandardCharsets.UTF_8);
        StringBuilder text = new StringBuilder("HTTP/1.1 " + reply.status() + " Scripted\r\n");
        reply.headers().forEach((name, value) -> text.append(name).append(": ")
                .append(SAME_TARGET.equals(value) ? requestTarget : value).append("\r\n"));
        text.append("Content-Length: ").append(content.length).append("\r\n\r\n");
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        whole.writeBytes(text.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!head) {
            whole.writeBytes(content);
        }
        return whole.toByteArray();
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            boolean next = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3));
            matched = next ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) {
                return head.toByteArray();
            }
        }
        return null;
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return 0;
    }
}
