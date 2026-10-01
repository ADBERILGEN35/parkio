package com.parkio.parking.infrastructure.client;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.unit.DataSize;

/**
 * Size and time bounds for municipal feed responses (CL-F23). The per-socket read timeout
 * does not bound a response that keeps trickling bytes, and {@code body(JsonNode.class)}
 * reads the whole body into memory. The interceptor lets a response body be read only up to
 * {@code maxSize} and only until {@code maxTime} after the request was sent (measured with
 * the monotonic clock). Past either bound the read fails with
 * {@link FeedResponseLimitException}, which the clients never retry, so the failure takes the
 * existing source-failure path once instead of once per attempt.
 */
public final class BoundedFeedResponses {

    private BoundedFeedResponses() {
    }

    public static ClientHttpRequestInterceptor interceptor(DataSize maxSize, Duration maxTime) {
        Objects.requireNonNull(maxSize, "maxSize");
        Objects.requireNonNull(maxTime, "maxTime");
        long maxBytes = maxSize.toBytes();
        if (maxBytes <= 0 || maxTime.isZero() || maxTime.isNegative()) {
            throw new IllegalArgumentException("feed response bounds must be positive: " + maxSize + ", " + maxTime);
        }
        long maxNanos = maxTime.toNanos();
        return (request, body, execution) -> {
            long deadline = System.nanoTime() + maxNanos;
            ClientHttpResponse response = execution.execute(request, body);
            long declared = response.getHeaders().getContentLength();
            if (declared > maxBytes) {
                response.close();
                throw FeedResponseLimitException.size(maxBytes);
            }
            return new BoundedResponse(response, maxBytes, deadline, maxTime);
        };
    }

    /** True when the failure, or one of its causes, is a feed size or time bound violation. */
    public static boolean exceededLimit(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof FeedResponseLimitException) {
                return true;
            }
        }
        return false;
    }

    /** A municipal feed response exceeded its configured size or time bound. */
    public static final class FeedResponseLimitException extends IOException {

        private FeedResponseLimitException(String message) {
            super(message);
        }

        static FeedResponseLimitException size(long maxBytes) {
            return new FeedResponseLimitException("municipal feed response exceeds " + maxBytes + " bytes");
        }

        static FeedResponseLimitException time(Duration maxTime) {
            return new FeedResponseLimitException("municipal feed response not complete within " + maxTime);
        }
    }

    private static final class BoundedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final long maxBytes;
        private final long deadline;
        private final Duration maxTime;
        private InputStream body;

        BoundedResponse(ClientHttpResponse delegate, long maxBytes, long deadline, Duration maxTime) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
            this.deadline = deadline;
            this.maxTime = maxTime;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                body = new BoundedInputStream(delegate.getBody(), maxBytes, deadline, maxTime);
            }
            return body;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {

        private final long maxBytes;
        private final long deadline;
        private final Duration maxTime;
        private long read;

        BoundedInputStream(InputStream in, long maxBytes, long deadline, Duration maxTime) {
            super(in);
            this.maxBytes = maxBytes;
            this.deadline = deadline;
            this.maxTime = maxTime;
        }

        @Override
        public int read() throws IOException {
            checkTime();
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            checkTime();
            // Ask for at most one byte past the bound, so an oversized body is detected
            // without reading (or buffering) the rest of it.
            int allowed = (int) Math.min(length, maxBytes - read + 1);
            int count = super.read(buffer, offset, allowed);
            if (count > 0) {
                count(count);
            }
            checkTime();
            return count;
        }

        @Override
        public long skip(long n) throws IOException {
            checkTime();
            long skipped = super.skip(Math.min(n, maxBytes - read + 1));
            if (skipped > 0) {
                count(skipped);
            }
            return skipped;
        }

        private void count(long bytes) throws IOException {
            read += bytes;
            if (read > maxBytes) {
                throw FeedResponseLimitException.size(maxBytes);
            }
        }

        private void checkTime() throws IOException {
            if (System.nanoTime() - deadline > 0) {
                throw FeedResponseLimitException.time(maxTime);
            }
        }
    }
}
