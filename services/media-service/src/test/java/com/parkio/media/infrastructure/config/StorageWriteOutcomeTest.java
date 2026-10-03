package com.parkio.media.infrastructure.config;

import static org.assertj.core.api.Assertions.catchThrowable;

import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.application.port.MediaStoragePort.WriteNotAppliedException;
import com.parkio.media.infrastructure.storage.MinioMediaStorageAdapter;
import com.parkio.media.infrastructure.storage.ScriptedS3Endpoint;
import com.parkio.media.infrastructure.storage.ScriptedS3Endpoint.Reply;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How the production adapter, on the production storage client, reports a failed upload PUT
 * (U05 object write ledger): a rejection only when no attempt sent the body or the store answered
 * its only transmission with a client error; every other failure leaves the write's outcome
 * unknown, so the upload keeps its write recorded. A scripted endpoint plays the store.
 */
class StorageWriteOutcomeTest {

    private static final byte[] CONTENT = "write outcome fixture body".getBytes(StandardCharsets.UTF_8);
    private static final String KEY = "media/owner/key.png";

    @Test
    void aClientErrorToTheOnlyTransmissionIsARejection() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(Reply.s3Error(403, "AccessDenied"))) {
            Throwable failure = store(endpoint.url());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requests()).as("transmissions").isEqualTo(1);
            softly.assertThat(failure).isInstanceOf(WriteNotAppliedException.class);
            softly.assertAll();
        }
    }

    @Test
    void aStoreThatCannotBeReachedReceivedNothing() throws Exception {
        int unused;
        try (ServerSocket probe = new ServerSocket(0)) {
            unused = probe.getLocalPort();
        }
        Throwable failure = store("http://127.0.0.1:" + unused);

        org.assertj.core.api.Assertions.assertThat(failure).isInstanceOf(WriteNotAppliedException.class);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {500, 503, 301, 302, 303, 307, 308})
    void aServerErrorOrRedirectLeavesTheOutcomeUnknown(int status) throws Exception {
        Reply reply = status >= 500 ? Reply.s3Error(status, "Scripted").with("Retry-After", "0")
                : Reply.redirectToSameTarget(status);
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(reply, Reply.s3Error(403, "AccessDenied"))) {
            Throwable failure = store(endpoint.url());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requests()).as("transmissions").isEqualTo(1);
            softly.assertThat(failure).isNotNull().isNotInstanceOf(WriteNotAppliedException.class);
            softly.assertAll();
        }
    }

    @Test
    void aStoreThatNeverAnswersLeavesTheOutcomeUnknown() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(Reply.none())) {
            Throwable failure = store(endpoint.url());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.bodyBytes()).as("body bytes received").isEqualTo(CONTENT.length);
            softly.assertThat(failure).isNotNull().isNotInstanceOf(WriteNotAppliedException.class);
            softly.assertAll();
        }
    }

    @Test
    void anAcceptedPutIsStored() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(Reply.ok())) {
            MediaStoragePort.StoredObject stored = adapter(endpoint.url()).store(KEY, CONTENT, "image/png");

            org.assertj.core.api.Assertions.assertThat(stored.objectKey()).isEqualTo(KEY);
        }
    }

    private static Throwable store(String endpoint) {
        MinioMediaStorageAdapter adapter = adapter(endpoint);
        return catchThrowable(() -> adapter.store(KEY, CONTENT, "image/png"));
    }

    private static MinioMediaStorageAdapter adapter(String endpoint) {
        MediaProperties properties = new MediaProperties();
        MediaProperties.Storage storage = properties.getStorage();
        storage.setEndpoint(endpoint);
        storage.setBucket("bucket");
        storage.setAccessKey("parkio-test");
        storage.setSecretKey("parkio-test-secret");
        storage.setRegion("us-east-1");
        storage.setConnectTimeout(Duration.ofSeconds(2));
        storage.setReadTimeout(Duration.ofSeconds(2));
        storage.setWriteTimeout(Duration.ofSeconds(2));
        storage.setCallTimeout(Duration.ofSeconds(4));
        MediaInfrastructureConfig config = new MediaInfrastructureConfig();
        return new MinioMediaStorageAdapter(config.internalMinioClient(properties), config.presignMinioClient(properties),
                config.versionListingClient(properties), properties, 100);
    }
}
