package com.parkio.media.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.media.application.port.MediaStoragePort.StoredVersion;
import com.parkio.media.infrastructure.config.MediaInfrastructureConfig;
import com.parkio.media.infrastructure.config.MediaProperties;
import com.parkio.media.infrastructure.storage.ScriptedS3Endpoint.Reply;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The isolated recovery's restored source bucket (U02, coordinator option A): it is honoured only
 * with restore replay on and only for account erasure's listing, and a bad value stops the
 * service from starting. Against a scripted S3 endpoint, so the bucket of every request is seen.
 */
class RestoredSourceBucketTest {

    private static final String BUCKET = "parkio-iso-0123456789ab-media";
    private static final String SOURCE = "parkio-media";
    private static final String KEY = "media/0f0e0d0c-0000-4000-8000-0000000000a1/object.png";
    private static final String EMPTY_LISTING = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>" + BUCKET + "</Name>"
            + "<Prefix>" + KEY + "</Prefix><KeyMarker></KeyMarker><VersionIdMarker></VersionIdMarker>"
            + "<MaxKeys>100</MaxKeys><IsTruncated>false</IsTruncated></ListVersionsResult>";

    @Test
    void unsetMeansNoMappingWhateverTheReplayFlag() {
        assertThat(RestoredSourceBucket.of(null, BUCKET, false)).isEmpty();
        assertThat(RestoredSourceBucket.of(null, BUCKET, true)).isEmpty();
    }

    @Test
    void setWhileRestoreReplayIsOffStopsTheServiceFromStarting() {
        assertThatThrownBy(() -> RestoredSourceBucket.of(SOURCE, BUCKET, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("parkio.media.storage.restored-source-bucket is set but parkio.privacy.restore-replay.enabled"
                        + " is false; it is honoured only during an isolated recovery");
        assertThatThrownBy(() -> adapter("http://127.0.0.1:9", SOURCE, false))
                .as("the storage adapter is created at startup").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEmptyOrInvalidBucketNameIsRefused() {
        for (String invalid : List.of("", " ", "ab", "Parkio-Media", "parkio_media", "-parkio-media", "parkio-media.",
                "a".repeat(64), "parkio media")) {
            assertThatThrownBy(() -> RestoredSourceBucket.of(invalid, BUCKET, true)).as("'%s'", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("parkio.media.storage.restored-source-bucket is not a valid bucket name");
        }
    }

    @Test
    void theConfiguredBucketItselfIsRefused() {
        assertThatThrownBy(() -> RestoredSourceBucket.of(BUCKET, BUCKET, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("parkio.media.storage.restored-source-bucket must name the restored source bucket, not the"
                        + " configured bucket");
    }

    @Test
    void erasureListsAKeyOfTheSourceBucketInTheConfiguredBucket() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                new Reply(200, Map.of("Content-Type", "application/xml"), EMPTY_LISTING, false),
                Reply.s3Error(404, "NoSuchKey"))) {
            List<StoredVersion> versions = adapter(endpoint.url(), SOURCE, true).versionsOf(SOURCE, KEY);

            assertThat(versions).isEmpty();
            assertThat(endpoint.requestLines()).isNotEmpty()
                    .allSatisfy(line -> assertThat(line).contains("/" + BUCKET).doesNotContain("/" + SOURCE + "?")
                            .doesNotContain("/" + SOURCE + "/"));
        }
    }

    @Test
    void anyOtherBucketIsStillRefusedWithoutARequest() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint()) {
            MinioMediaStorageAdapter adapter = adapter(endpoint.url(), SOURCE, true);

            assertThatThrownBy(() -> adapter.versionsOf("parkio-other-media", KEY))
                    .isInstanceOf(MediaStorageException.class)
                    .hasMessage("Object is stored in bucket 'parkio-other-media', not the configured bucket '" + BUCKET
                            + "'; its deletion cannot be confirmed");
            assertThat(endpoint.requests()).isZero();
        }
    }

    @Test
    void withoutTheMappingTheSourceBucketIsRefused() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint()) {
            for (boolean replay : List.of(true, false)) {
                MinioMediaStorageAdapter adapter = adapter(endpoint.url(), null, replay);

                assertThatThrownBy(() -> adapter.versionsOf(SOURCE, KEY)).isInstanceOf(MediaStorageException.class);
            }
            assertThat(endpoint.requests()).isZero();
        }
    }

    @Test
    void aVersionDeleteStaysInTheConfiguredBucket() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint()) {
            MinioMediaStorageAdapter adapter = adapter(endpoint.url(), SOURCE, true);

            assertThatThrownBy(() -> adapter.removeVersion(new StoredVersion(SOURCE, KEY, "null", false)))
                    .isInstanceOf(MediaStorageException.class);
            assertThat(endpoint.requests()).isZero();
        }
    }

    @Test
    void uploadsReadsAndPresignedUrlsNeverUseTheSourceBucket() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(Reply.ok(), Reply.ok())) {
            MinioMediaStorageAdapter adapter = adapter(endpoint.url(), SOURCE, true);

            assertThat(adapter.store(KEY, "synthetic".getBytes(), "image/png").bucket()).isEqualTo(BUCKET);
            String url = adapter.generatePresignedGetUrl(KEY, Duration.ofMinutes(5));
            assertThat(url).contains("/" + BUCKET + "/").doesNotContain(SOURCE + "/");
            try {
                adapter.load(KEY);
            } catch (MediaStorageException ignored) {
                // the scripted reply has no body; only the request's bucket matters here
            }
            assertThat(endpoint.requestLines()).isNotEmpty()
                    .allSatisfy(line -> assertThat(line).contains("/" + BUCKET + "/").doesNotContain("/" + SOURCE + "/"));
        }
    }

    private static MinioMediaStorageAdapter adapter(String endpoint, String source, boolean restoreReplay) {
        MediaProperties properties = new MediaProperties();
        MediaProperties.Storage storage = properties.getStorage();
        storage.setEndpoint(endpoint);
        storage.setPublicEndpoint(endpoint);
        storage.setBucket(BUCKET);
        storage.setRestoredSourceBucket(source);
        storage.setAccessKey("parkio-test");
        storage.setSecretKey("parkio-test-secret");
        storage.setRegion("us-east-1");
        storage.setConnectTimeout(Duration.ofSeconds(2));
        storage.setReadTimeout(Duration.ofSeconds(2));
        storage.setWriteTimeout(Duration.ofSeconds(2));
        storage.setCallTimeout(Duration.ofSeconds(4));
        MediaInfrastructureConfig config = new MediaInfrastructureConfig();
        return new MinioMediaStorageAdapter(config.internalMinioClient(properties), config.presignMinioClient(properties),
                config.versionListingClient(properties), properties, 100, restoreReplay);
    }
}
