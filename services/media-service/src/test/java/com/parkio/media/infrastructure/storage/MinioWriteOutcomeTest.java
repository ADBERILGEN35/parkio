package com.parkio.media.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.media.infrastructure.storage.SingleTransmissionInterceptor.BodyNotSentException;
import com.parkio.media.infrastructure.storage.SingleTransmissionInterceptor.GuardedBody;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import org.junit.jupiter.api.Test;

/**
 * Which failed PUTs the adapter reports as certainly not applied (U05 object write ledger), from
 * the evidence the single-transmission guard leaves rather than from the last reply alone: no
 * attempt sent the body, or the store answered the guarded body's only transmission with a client
 * error and no follow-up was made. Everything else may have been applied.
 */
class MinioWriteOutcomeTest {

    @Test
    void aClientErrorToTheOnlyTransmissionOfAGuardedBodyIsARejection() throws IOException {
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(403, sentOnce(), null))).isTrue();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(400, sentOnce(), null))).isTrue();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(404, sentOnce(), null))).isTrue();
    }

    @Test
    void aClientErrorWithoutTheGuardsEvidenceIsNotARejection() throws IOException {
        // A body the guard never saw: nothing shows how often it was sent.
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(
                reply(403, RequestBody.create(new byte[] {1}, MediaType.get("image/png")), null))).isFalse();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(403, null, null))).isFalse();
    }

    @Test
    void aClientErrorToAFollowUpRequestIsNotARejection() throws IOException {
        Response earlier = new Response.Builder().request(request(sentOnce())).protocol(Protocol.HTTP_1_1)
                .code(503).message("SlowDown").build();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(403, sentOnce(), earlier))).isFalse();
    }

    @Test
    void serverErrorAndRedirectRepliesLeaveTheOutcomeUnknown() throws IOException {
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(500, sentOnce(), null))).isFalse();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(503, sentOnce(), null))).isFalse();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(reply(307, sentOnce(), null))).isFalse();
    }

    @Test
    void aBodyThatNoAttemptSentIsARejectionWhereverTheEvidenceSits() {
        BodyNotSentException neverSent = new BodyNotSentException(new ConnectException("refused"));
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(neverSent)).isTrue();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(
                (Exception) new InterruptedIOException("timeout").initCause(neverSent))).isTrue();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new ExecutionException(neverSent))).isTrue();
    }

    @Test
    void aConnectionFailureWithoutTheGuardsEvidenceIsNotARejection() {
        // It may have followed an earlier attempt that sent the body (a follow-up after a reply).
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new ExecutionException(new ConnectException("refused"))))
                .isFalse();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new IOException(new UnknownHostException("minio"))))
                .isFalse();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new IOException(new NoRouteToHostException("no route"))))
                .isFalse();
    }

    @Test
    void timeoutsAndBrokenConnectionsLeaveTheOutcomeUnknown() {
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new ExecutionException(new InterruptedIOException("timeout"))))
                .isFalse();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new IOException(new SocketTimeoutException("read timed out"))))
                .isFalse();
        assertThat(MinioMediaStorageAdapter.definitelyNotApplied(new IOException("unexpected end of stream"))).isFalse();
    }

    @Test
    void theGuardRefusesASecondTransmission() throws IOException {
        GuardedBody body = sentOnce();

        assertThat(body.isOneShot()).isTrue();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> body.writeTo(new Buffer())))
                .isInstanceOf(java.net.ProtocolException.class);
        assertThat(SingleTransmissionInterceptor.sentAtMostOnce(request(body))).isFalse();
    }

    private static GuardedBody sentOnce() throws IOException {
        GuardedBody body = new GuardedBody(RequestBody.create(new byte[] {1, 2, 3}, MediaType.get("image/png")));
        body.writeTo(new Buffer());
        return body;
    }

    private static Request request(RequestBody body) {
        Request.Builder builder = new Request.Builder().url("http://minio:9000/bucket/media/key.jpg");
        return body == null ? builder.build() : builder.put(body).build();
    }

    private static ErrorResponseException reply(int status, RequestBody body, Response prior) {
        Response response = new Response.Builder()
                .request(request(body))
                .priorResponse(prior)
                .protocol(Protocol.HTTP_1_1).code(status).message("scripted").build();
        return new ErrorResponseException(
                new ErrorResponse("Code", "message", "bucket", "media/key.jpg", "/bucket/media/key.jpg", "req", "host"),
                response, null);
    }
}
