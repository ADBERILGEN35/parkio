package com.parkio.notification.infrastructure.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A loopback peer that accepts every connection and reads what the client sends, but never answers: an Expo
 * endpoint whose response never completes (#595). Connections stay open until {@link #close()}.
 */
final class SilentPeer implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> connections = new CopyOnWriteArrayList<>();
    private final StringBuffer received = new StringBuffer();
    private final CountDownLatch requestHeadReceived = new CountDownLatch(1);

    private SilentPeer(ServerSocket server) {
        this.server = server;
        Thread acceptor = new Thread(this::acceptUntilClosed, "silent-peer-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    static SilentPeer start() {
        try {
            return new SilentPeer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        } catch (IOException ex) {
            throw new IllegalStateException("cannot bind the silent peer", ex);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** Connections accepted so far, including ones the client has since closed. */
    int connectionCount() {
        return connections.size();
    }

    /** True once a complete request head (request line and headers) has arrived. */
    boolean awaitRequestHead(Duration timeout) throws InterruptedException {
        return requestHeadReceived.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Everything received so far, on every connection. */
    String received() {
        return received.toString();
    }

    private void acceptUntilClosed() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                connections.add(socket);
                Thread reader = new Thread(() -> readWithoutAnswering(socket), "silent-peer-read");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void readWithoutAnswering(Socket socket) {
        byte[] buffer = new byte[4096];
        try (InputStream in = socket.getInputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                received.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
                if (received.indexOf("\r\n\r\n") >= 0) {
                    requestHeadReceived.countDown();
                }
            }
        } catch (IOException closed) {
            // The client gave up, or close() ran.
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        for (Socket socket : connections) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
