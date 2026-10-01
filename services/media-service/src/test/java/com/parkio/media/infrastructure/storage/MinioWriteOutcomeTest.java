package com.parkio.media.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

/**
 * Which failed PUTs the adapter reports as certainly not applied (U05 object write ledger): only a
 * client-error reply and a connection that never opened. Everything else may have been applied.
 */
class MinioWriteOutcomeTest {

    @Test
    void clientErrorRepliesAreRejections() {
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(403, "AccessDenied"))).isTrue();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(400, "IncompleteBody"))).isTrue();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(404, "NoSuchBucket"))).isTrue();
    }

    @Test
    void serverErrorRepliesLeaveTheOutcomeUnknown() {
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(500, "InternalError"))).isFalse();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(503, "SlowDown"))).isFalse();
    }

    @Test
    void aConnectionThatNeverOpenedSentNothing() {
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new ExecutionException(new ConnectException("refused"))))
                .isTrue();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new IOException(new UnknownHostException("minio"))))
                .isTrue();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new IOException(new NoRouteToHostException("no route"))))
                .isTrue();
    }

    @Test
    void timeoutsAndBrokenConnectionsLeaveTheOutcomeUnknown() {
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new ExecutionException(new InterruptedIOException("timeout"))))
                .isFalse();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new IOException(new SocketTimeoutException("read timed out"))))
                .isFalse();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new IOException("unexpected end of stream"))).isFalse();
    }

    private static ErrorResponseException reply(int status, String code) {
        Response response = new Response.Builder()
                .request(new Request.Builder().url("http://minio:9000/bucket/media/key.jpg").build())
                .protocol(Protocol.HTTP_1_1).code(status).message(code).build();
        return new ErrorResponseException(
                new ErrorResponse(code, code, "bucket", "media/key.jpg", "/bucket/media/key.jpg", "req", "host"),
                response, null);
    }
}
