package com.parkio.media.infrastructure.storage;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * Sends a request body at most once (U05 object write ledger). OkHttp 4.12 repeats a request on its
 * own: as a follow-up to a reply (503 with {@code Retry-After: 0}, a 307 or 308 redirect that keeps
 * the body, 408, 421, an authentication challenge) and after a failed attempt. The MinIO SDK turns
 * off only the second kind for an upload's PUT. A PUT transmitted twice can be applied twice, at
 * different times, and the reply to the second transmission says nothing about the first.
 *
 * <p>This application interceptor wraps every request body as one-shot: OkHttp then makes no
 * follow-up and no recovery once the body has been sent, while an attempt whose connection never
 * carried the body may still be repeated on another route. It also leaves the evidence the write
 * classifier ({@link MinioMediaStorageAdapter#definitelyNotApplied}) relies on, instead of the last
 * reply alone: a call that failed before any attempt started to send its (non-empty) body fails
 * with {@link BodyNotSentException}, and the request of a reply carries the guarded body with its
 * transmission count.
 */
public final class SingleTransmissionInterceptor implements Interceptor {

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        RequestBody body = request.body();
        if (body == null) {
            return chain.proceed(request);
        }
        long length = body.contentLength();
        GuardedBody guarded = new GuardedBody(body);
        try {
            return chain.proceed(request.newBuilder().method(request.method(), guarded).build());
        } catch (IOException failure) {
            if (guarded.transmissions() == 0 && length > 0) {
                throw new BodyNotSentException(failure);
            }
            throw failure;
        }
    }

    /** The request carried a guarded body that was transmitted at most once. */
    static boolean sentAtMostOnce(Request request) {
        return request != null && request.body() instanceof GuardedBody guarded && guarded.transmissions() <= 1;
    }

    /** The call failed before any of its attempts started to send the request body: no byte of it left. */
    static final class BodyNotSentException extends IOException {
        BodyNotSentException(IOException cause) {
            super("request body never sent (" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")", cause);
        }
    }

    /** One-shot body that counts its transmissions and refuses a second one. */
    static final class GuardedBody extends RequestBody {

        private final RequestBody delegate;
        private final AtomicInteger transmissions = new AtomicInteger();

        GuardedBody(RequestBody delegate) {
            this.delegate = delegate;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public boolean isOneShot() {
            return true;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            if (transmissions.incrementAndGet() > 1) {
                throw new ProtocolException("request body already sent once; it is not sent again");
            }
            delegate.writeTo(sink);
        }

        int transmissions() {
            return transmissions.get();
        }
    }
}
