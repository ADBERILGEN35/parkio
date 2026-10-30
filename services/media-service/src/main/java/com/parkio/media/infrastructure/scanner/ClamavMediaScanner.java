package com.parkio.media.infrastructure.scanner;

import com.parkio.media.application.port.MediaScanner;
import com.parkio.media.application.port.MediaScannerUnavailableException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MediaScanner} backed by a ClamAV daemon spoken to over TCP using the clamd
 * {@code INSTREAM} command (no temp files, bytes streamed straight from memory). Pure
 * JDK sockets — no extra dependency.
 *
 * <p>Protocol: send {@code zINSTREAM\0}, then a sequence of {@code <uint32 length><bytes>}
 * chunks terminated by a zero-length chunk; clamd replies with {@code stream: OK},
 * {@code stream: <sig> FOUND} or {@code ... ERROR}.
 *
 * <p><b>Fail-closed:</b> any connect/read timeout, I/O error, protocol error or
 * {@code ERROR} reply is surfaced as {@link MediaScannerUnavailableException} so the
 * caller rejects the upload rather than serving unscanned bytes.
 */
public class ClamavMediaScanner implements MediaScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamavMediaScanner.class);

    /** clamd default chunk ceiling is generous; 32 KiB keeps memory/syscalls modest. */
    private static final int CHUNK_SIZE = 32 * 1024;
    private static final byte[] INSTREAM_COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);

    private final String host;
    private final int port;
    private final InetSocketAddress address;
    private final long connectTimeoutNanos;
    private final long readTimeoutNanos;
    private final long totalTimeoutNanos;
    // Fail fast on saturation: no executor, no queue of timed-out writers.
    private final Semaphore scans = new Semaphore(16);

    public ClamavMediaScanner(String host, int port, Duration connectTimeout, Duration readTimeout) {
        this.host = host;
        this.port = port;
        this.connectTimeoutNanos = positiveNanos(connectTimeout);
        this.readTimeoutNanos = positiveNanos(readTimeout);
        this.totalTimeoutNanos = Math.addExact(connectTimeoutNanos, readTimeoutNanos);
        // Resolve configured infrastructure at bean construction, never in a timed scan.
        this.address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) {
            throw new IllegalArgumentException("Cannot resolve configured clamd host");
        }
    }

    @Override
    public ScanReport scan(byte[] content) {
        long started = System.nanoTime();
        long deadline = started + totalTimeoutNanos;
        if (!scans.tryAcquire()) {
            throw new MediaScannerUnavailableException("clamd scan capacity exhausted");
        }
        try (SocketChannel socket = SocketChannel.open(); Selector selector = Selector.open()) {
            socket.configureBlocking(false);
            SelectionKey key = socket.register(selector, SelectionKey.OP_CONNECT);
            if (!socket.connect(address)) {
                while (!socket.finishConnect()) {
                    awaitReady(selector, key, SelectionKey.OP_CONNECT, started + connectTimeoutNanos);
                }
            }
            write(socket, selector, key, ByteBuffer.wrap(INSTREAM_COMMAND), deadline);
            ByteBuffer length = ByteBuffer.allocate(Integer.BYTES);
            for (int offset = 0; offset < content.length; offset += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, content.length - offset);
                length.clear().putInt(len).flip();
                write(socket, selector, key, length, deadline);
                write(socket, selector, key, ByteBuffer.wrap(content, offset, len), deadline);
            }
            length.clear().putInt(0).flip();
            write(socket, selector, key, length, deadline);
            String response = readResponse(socket, selector, key,
                    System.nanoTime() + Math.min(remaining(deadline), readTimeoutNanos));
            remaining(deadline);
            return interpret(response);
        } catch (IOException e) {
            throw new MediaScannerUnavailableException("clamd scan failed (" + host + ":" + port + ")", e);
        } finally {
            scans.release();
        }
    }

    private static long positiveNanos(Duration duration) {
        long nanos = duration.toNanos();
        if (nanos <= 0 || nanos > Duration.ofHours(1).toNanos()) {
            throw new IllegalArgumentException("clamd timeout must be positive and at most one hour");
        }
        return nanos;
    }

    private static long remaining(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("clamd scan cancelled");
        }
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new SocketTimeoutException("clamd scan deadline expired");
        }
        return nanos;
    }

    private static void awaitReady(Selector selector, SelectionKey key, int operation, long deadline)
            throws IOException {
        key.interestOps(operation);
        selector.select(Math.max(1, (remaining(deadline) + 999_999) / 1_000_000));
        selector.selectedKeys().clear();
        remaining(deadline);
    }

    private static void write(SocketChannel socket, Selector selector, SelectionKey key,
                              ByteBuffer bytes, long deadline) throws IOException {
        while (bytes.hasRemaining()) {
            remaining(deadline);
            if (socket.write(bytes) == 0) {
                awaitReady(selector, key, SelectionKey.OP_WRITE, deadline);
            }
        }
    }

    private ScanReport interpret(String response) {
        String trimmed = response.trim();
        if (trimmed.endsWith("OK") && !trimmed.contains("FOUND")) {
            return ScanReport.ofClean();
        }
        if (trimmed.endsWith("FOUND")) {
            // Format: "stream: <signature> FOUND"
            String signature = trimmed;
            int colon = trimmed.indexOf(':');
            if (colon >= 0) {
                signature = trimmed.substring(colon + 1, trimmed.length() - "FOUND".length()).trim();
            }
            log.warn("clamd reported an infected upload (signature redacted in client metrics)");
            return ScanReport.ofInfected(signature);
        }
        // Anything else (including "... ERROR") means we cannot trust a clean result.
        throw new MediaScannerUnavailableException("Unexpected clamd response: " + trimmed);
    }

    private static String readResponse(SocketChannel socket, Selector selector, SelectionKey key,
                                       long deadline) throws IOException {
        StringBuilder sb = new StringBuilder();
        ByteBuffer buffer = ByteBuffer.allocate(512);
        while (sb.length() < 4096) {
            remaining(deadline);
            int count = socket.read(buffer);
            if (count < 0) {
                throw new IOException("clamd response ended without terminator");
            }
            if (count == 0) {
                awaitReady(selector, key, SelectionKey.OP_READ, deadline);
                continue;
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                byte b = buffer.get();
                if (b == 0) {
                    return sb.toString();
                }
                sb.append((char) (b & 0xff));
            }
            buffer.clear();
        }
        throw new IOException("clamd response exceeds limit");
    }
}
