package com.parkio.media.infrastructure.scanner;

import com.parkio.media.application.port.MediaScanner;
import com.parkio.media.application.port.MediaScannerUnavailableException;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public ClamavMediaScanner(String host, int port, Duration connectTimeout, Duration readTimeout) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = (int) connectTimeout.toMillis();
        this.readTimeoutMillis = (int) readTimeout.toMillis();
    }

    @Override
    public ScanReport scan(byte[] content) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);

            OutputStream rawOut = socket.getOutputStream();
            DataOutputStream out = new DataOutputStream(rawOut);
            out.write(INSTREAM_COMMAND);
            for (int offset = 0; offset < content.length; offset += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, content.length - offset);
                out.writeInt(len);          // 4-byte big-endian length prefix
                out.write(content, offset, len);
            }
            out.writeInt(0);                // zero-length chunk terminates the stream
            out.flush();

            String response = readResponse(socket.getInputStream());
            return interpret(response);
        } catch (IOException e) {
            throw new MediaScannerUnavailableException("clamd scan failed (" + host + ":" + port + ")", e);
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

    private static String readResponse(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        // clamd terminates the reply with a NUL byte.
        while ((b = in.read()) != -1) {
            if (b == 0) {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }
}
