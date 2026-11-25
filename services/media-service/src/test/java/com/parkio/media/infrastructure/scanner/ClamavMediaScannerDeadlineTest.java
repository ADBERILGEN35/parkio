package com.parkio.media.infrastructure.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.media.application.port.MediaScannerUnavailableException;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ClamavMediaScannerDeadlineTest {
    private ClamavMediaScanner scanner(ServerSocket server) {
        return new ClamavMediaScanner("127.0.0.1", server.getLocalPort(),
                Duration.ofMillis(150), Duration.ofMillis(150));
    }

    @Test
    void peerThatNeverReadsCannotKeepWriterAlive() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setReceiveBufferSize(1024);
            var future = executor.submit(() -> scanner(server).scan(new byte[10 * 1024 * 1024]));
            try (var peer = server.accept()) {
                assertThatThrownBy(() -> future.get(1500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(MediaScannerUnavailableException.class);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void peerReadsButNeverRespondsAndRepeatedTimeoutsReleaseSockets() throws Exception {
        try (var server = new ServerSocket(0); var peers = Executors.newSingleThreadExecutor()) {
            var scanner = scanner(server);
            for (int i = 0; i < 6; i++) {
                var drained = peers.submit(() -> {
                    try (var peer = server.accept()) {
                        drain(peer);
                        peer.setSoTimeout(2000);
                        return peer.getInputStream().read();
                    }
                });
                assertThatThrownBy(() -> scanner.scan(new byte[128]))
                        .isInstanceOf(MediaScannerUnavailableException.class);
                assertThat(drained.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            }
        }
    }

    @Test
    void unresolvableHostDoesNotFailConstructionAndEachScanFailsClosed() {
        // RFC 6761: names under .invalid never resolve. Startup must not depend on clamd DNS.
        var scanner = new ClamavMediaScanner("clamd.invalid", 3310, Duration.ofMillis(150), Duration.ofMillis(150));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> scanner.scan(new byte[1]))
                    .isInstanceOf(MediaScannerUnavailableException.class);
        }
    }

    @Test
    void configuredHostnameIsResolvedWhenScanning() throws Exception {
        var loopback = InetAddress.getByName("localhost");
        try (var server = new ServerSocket(0, 50, loopback); var peers = Executors.newSingleThreadExecutor()) {
            var scanner = new ClamavMediaScanner("localhost", server.getLocalPort(),
                    Duration.ofSeconds(2), Duration.ofSeconds(2));
            var served = peers.submit(() -> {
                try (var peer = server.accept()) {
                    drain(peer);
                    peer.getOutputStream().write("stream: OK\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
                return true;
            });
            assertThat(scanner.scan(new byte[64]).clean()).isTrue();
            assertThat(served.get(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cleanAndInfectedRepliesUseActualProtocol() throws Exception {
        try (var server = new ServerSocket(0); var peers = Executors.newSingleThreadExecutor()) {
            var scanner = scanner(server);
            for (String response : new String[] {"stream: OK\0", "stream: synthetic-signature FOUND\0"}) {
                var served = peers.submit(() -> {
                    try (var peer = server.accept()) {
                        drain(peer);
                        peer.getOutputStream().write(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    }
                    return true;
                });
                assertThat(scanner.scan(new byte[64]).clean()).isEqualTo(response.contains("OK"));
                assertThat(served.get(2, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void slowReaderCannotExtendOverallDeadline() throws Exception {
        try (var server = new ServerSocket(0); var peers = Executors.newSingleThreadExecutor()) {
            server.setReceiveBufferSize(1024);
            var scanEnded = new CountDownLatch(1);
            var serving = peers.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(1000);
                    // The peer stays connected and keeps reading for 3 s, three times the bound
                    // below, unless the scan gives up first. A scanner without an overall deadline
                    // stays in its blocking write until this peer stops, so it fails the bound.
                    long readUntil = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                    long bytesRead = 0;
                    while (System.nanoTime() < readUntil && !scanEnded.await(50, TimeUnit.MILLISECONDS)) {
                        int count = peer.getInputStream().read(new byte[64]);
                        if (count < 0) {
                            break;
                        }
                        bytesRead += count;
                    }
                    return new SlowPeer(bytesRead, scanEnded.getCount() == 0);
                }
            });
            long start = System.nanoTime();
            assertThatThrownBy(() -> scanner(server).scan(new byte[10 * 1024 * 1024]))
                    .isInstanceOf(MediaScannerUnavailableException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            scanEnded.countDown();
            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
            SlowPeer peer = serving.get(2, TimeUnit.SECONDS);
            // The peer read while the scan ran and was still reading when the scan failed, so
            // the deadline ended the scan, not the peer closing the connection.
            assertThat(peer.bytesRead()).isPositive();
            assertThat(peer.readUntilScanEnded()).isTrue();
        }
    }

    private record SlowPeer(long bytesRead, boolean readUntilScanEnded) {
    }

    @Test
    void cancellationInterruptClosesSocketAndTerminatesWorker() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            var scan = executor.submit(() -> new ClamavMediaScanner("127.0.0.1", server.getLocalPort(),
                    Duration.ofSeconds(10), Duration.ofSeconds(10)).scan(new byte[64]));
            try (var peer = server.accept()) {
                drain(peer);
                scan.cancel(true);
                peer.setSoTimeout(1500);
                assertThat(peer.getInputStream().read()).isEqualTo(-1);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentScansAreBoundedAndCancellationReleasesEveryPermit() throws Exception {
        var executor = Executors.newFixedThreadPool(16);
        var sockets = new java.util.ArrayList<Socket>();
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            var scanner = new ClamavMediaScanner("127.0.0.1", server.getLocalPort(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5));
            for (int i = 0; i < 16; i++) futures.add(executor.submit(() -> scanner.scan(new byte[1])));
            for (int i = 0; i < 16; i++) {
                var socket = server.accept();
                socket.setSoTimeout(2000);
                sockets.add(socket);
                drain(socket);
            }
            assertThatThrownBy(() -> scanner.scan(new byte[1]))
                    .isInstanceOf(MediaScannerUnavailableException.class).hasMessageContaining("capacity");
            futures.forEach(f -> f.cancel(true));
            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            for (var socket : sockets) assertThat(socket.getInputStream().read()).isEqualTo(-1);
            try (var peerExecutor = Executors.newSingleThreadExecutor()) {
                var response = peerExecutor.submit(() -> {
                    try (var socket = server.accept()) {
                        drain(socket);
                        socket.getOutputStream().write("stream: OK\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    }
                    return true;
                });
                assertThat(scanner.scan(new byte[1]).clean()).isTrue();
                response.get(2, TimeUnit.SECONDS);
            }
        } finally {
            futures.forEach(f -> f.cancel(true));
            for (var socket : sockets) socket.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void drain(Socket socket) throws Exception {
        var in = new DataInputStream(socket.getInputStream());
        assertThat(new String(in.readNBytes(10), java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("zINSTREAM\0");
        for (int count; (count = in.readInt()) != 0;) {
            assertThat(in.readNBytes(count)).hasSize(count);
        }
    }
}
