package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.application.port.DurableErasureRecordStore;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.auth.infrastructure.lifecycle.ErasureDurableRecordingWorker;
import com.parkio.auth.infrastructure.persistence.ErasureDurableWorkerRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureRequestJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureServiceAckJpaRepository;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import io.minio.messages.Item;
import io.minio.messages.RetentionMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * U02 #172 review B1, on real PostgreSQL (Flyway) and a disposable MinIO object-lock bucket.
 *
 * <p>The after-commit attempt publishes the record and then loses the frontier write that would
 * cover it (a MinIO client fails that one PUT). The retry rebuilds the record from the request
 * row read back from PostgreSQL. The clock has sub-microsecond digits: PostgreSQL keeps
 * microseconds and the JDBC driver rounds when it writes, while evidence format v1 truncates
 * {@code erasedAt}. The retry must produce the same canonical record bytes and body digest, so
 * there is no conflict, the frontier is completed and the request becomes DURABLY_RECORDED.
 * Both retry paths are covered: an explicit retry and the retry worker. Synthetic users and keys.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ObjectLockRetryAfterPartialPublicationPostgresMinioIT {

    private static final String PASSWORD = "pw";
    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-erasure-evidence-precision-it";
    private static final String DATABASE = "auth-db:object-lock-precision-it";
    private static final TrustedKey PRODUCER = TrustedKey.active("auth-object-lock-precision-it-key-2026a",
            "auth-object-lock-precision-it",
            "object-lock-precision-it-key-not-a-secret".getBytes(StandardCharsets.UTF_8), Instant.parse("2026-01-01T00:00:00Z"));
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Near the real time (object-lock retention must lie in the store's future), with 789 ns
     * below the microsecond: the driver stores ...457 us where format v1 truncates to ...456 us.
     */
    private static final MutableClock CLOCK = new MutableClock(
            Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789));

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_object_lock_precision_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(
                    // RELEASE.2024-09-13T20-26-02Z; same publisher digest as CI Compose.
                    "ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c"))
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("management.tracing.enabled", () -> "false");
        registry.add("parkio.privacy.account-erasure.enabled", () -> "true");
        registry.add("parkio.privacy.account-erasure.durable-recording-enabled", () -> "true");
        registry.add("parkio.privacy.account-erasure.participants", () -> "user");
        // The scheduled worker bean stays disabled. A fixed-delay @Scheduled method also runs once right
        // after the context starts, whatever the delay, and that run could claim a request between this
        // class's assertions (it did in CI). The worker test runs the real worker on its own thread.
        registry.add("parkio.privacy.account-erasure.durable-recording-retry-worker-enabled", () -> "false");
        try {
            ObjectLockTestBuckets.createLockedBucket(minioClient(), BUCKET);
        } catch (Exception ex) {
            throw new IllegalStateException("could not create the object-lock test bucket", ex);
        }
    }

    /** The object-lock store on a MinIO client that can fail one frontier write; the clock above. */
    @TestConfiguration
    static class PartialPublicationConfig {

        @Bean
        @Primary
        Clock subMicrosecondClock() {
            return CLOCK;
        }

        @Bean
        FrontierFailingMinioClient frontierFailingMinioClient() {
            return new FrontierFailingMinioClient(minioClient());
        }

        @Bean
        @Primary
        DurableErasureRecordStore objectLockStoreWithFailingFrontier(FrontierFailingMinioClient client) {
            return new ObjectLockDurableErasureRecordStore(new ObjectLockBucket(client, BUCKET),
                    new EvidenceTrust(DATABASE, java.util.List.of(PRODUCER)), PRODUCER.keyId(),
                    RetentionMode.GOVERNANCE, Duration.ofDays(1), CLOCK);
        }
    }

    @MockBean private AuthUserRepository users;
    @MockBean private RefreshTokenRepository refreshTokens;
    @MockBean private PasswordResetRepository passwordResets;
    @MockBean private PasswordHasher passwordHasher;
    @MockBean private OutboxEventAppender outbox;
    @MockBean private InboxEventRepository inbox;
    @MockBean private LoginFailureTracker loginFailureTracker;
    @MockBean private EmailVerificationSender emailVerificationSender;

    @Autowired private AccountErasureApplicationService service;
    /** The scheduled Spring bean: disabled in this context, so its ticks change nothing. */
    @Autowired private ErasureDurableRecordingWorker scheduledWorker;
    @Autowired private ErasureDurableWorkerRepository workerRepository;
    @Autowired private ObjectProvider<DurableErasureRecordStore> stores;
    @Autowired private DurableErasureRecordStore store;
    @Autowired private FrontierFailingMinioClient failingClient;
    @Autowired private ErasureRequestJpaRepository requests;
    @Autowired private ErasureServiceAckJpaRepository acks;
    @Autowired private ErasedUserTombstoneJpaRepository tombstones;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        acks.deleteAll();
        requests.deleteAll();
        tombstones.deleteAll();
        when(users.save(any(AuthUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordHasher.matches(eq(PASSWORD), eq("hash"))).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("replacement-hash");
        when(inbox.tryClaim(any(), any(), any())).thenReturn(true);
    }

    @Test
    void anExplicitRetryAfterThePartialPublicationCompletesTheSameRecord() throws Exception {
        assertRetryAfterPartialPublication(requestId -> service.persistDurableRecord(requestId));
    }

    @Test
    void theWorkerRetryAfterThePartialPublicationCompletesTheSameRecord() throws Exception {
        assertRetryAfterPartialPublication(requestId -> {
            // Past the retry backoff; whole hours keep the clock's sub-microsecond digits.
            CLOCK.advance(Duration.ofHours(1));
            retryWorker().tick();
        });
    }

    @Test
    void theScheduledWorkerBeanChangesNothingInThisContext() throws Exception {
        AuthUser user = newUser();
        failingClient.failTheFrontierWriteAfterTheNextRecord();
        UUID requestId = service.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        // Reading the counter resets it, as the other tests expect.
        assertThat(failingClient.injectedFailures()).isEqualTo(1);
        CLOCK.advance(Duration.ofHours(1));

        // A tick of the Spring bean from another thread, as the scheduler would run it.
        Thread scheduler = new Thread(scheduledWorker::tick, "scheduling-guard");
        scheduler.start();
        scheduler.join(Duration.ofSeconds(30).toMillis());

        assertThat(scheduler.isAlive()).isFalse();
        assertThat(recordingStatus(requestId)).isEqualTo("PENDING_DURABLE");
        assertThat(store.findByRequestId(requestId)).as("still not covered by a frontier").isEmpty();
        retryWorker().tick();
        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");
        assertThat(store.findByRequestId(requestId)).isPresent();
    }

    /**
     * The real worker with the production defaults (application.yml), enabled, for this test thread
     * only. Constructor order: service, repository, stores, clock, worker enabled, durable recording
     * enabled, batch size, max attempts, base backoff ms, max backoff ms, lease ms.
     */
    private ErasureDurableRecordingWorker retryWorker() {
        return new ErasureDurableRecordingWorker(
                service, workerRepository, stores, CLOCK, true, true, 20, 10, 5_000, 900_000, 120_000);
    }

    private void assertRetryAfterPartialPublication(Retry retry) throws Exception {
        AuthUser user = newUser();
        failingClient.failTheFrontierWriteAfterTheNextRecord();

        UUID requestId = service.requestDeletion(user.id(), PASSWORD).erasureRequestId();

        // The after-commit attempt published the record and lost the covering frontier write.
        assertThat(failingClient.injectedFailures()).isEqualTo(1);
        assertThat(recordingStatus(requestId)).isEqualTo("PENDING_DURABLE");
        assertThat(store.findByRequestId(requestId)).as("not durable before the frontier covers it").isEmpty();
        byte[] published = canonicalRecord(requestId);
        Instant requestedAtInDatabase = requestedAtInDatabase(requestId);
        DurableErasureRecord rebuiltFromDatabase = DurableErasureRecord.of(requestId, user.id(), requestedAtInDatabase);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(field(published, "erasedAt"))
                .as("erasedAt of the published record vs the request row in PostgreSQL")
                .isEqualTo(DurableErasureEvidence.erasedAt(requestedAtInDatabase));
        softly.assertThat(field(published, "bodyDigest"))
                .as("bodyDigest of the published record vs the record rebuilt from PostgreSQL")
                .isEqualTo(rebuiltFromDatabase.bodyDigest());

        Throwable failure = catchThrowable(() -> retry.run(requestId));

        softly.assertThat(failure).as("retry after the partial publication").isNull();
        softly.assertThat(recordingStatus(requestId)).as("durable recording status after the retry")
                .isEqualTo("DURABLY_RECORDED");
        softly.assertThat(recordVersions(requestId)).as("record versions in the bucket").isEqualTo(1);
        softly.assertThat(canonicalRecord(requestId)).as("canonical record bytes after the retry").isEqualTo(published);
        softly.assertThat(store.findByRequestId(requestId)).as("record covered by the frontier after the retry")
                .map(DurableErasureRecord::bodyDigest)
                .contains(rebuiltFromDatabase.bodyDigest());
        softly.assertAll();

        service.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), requestId, user.id(), "user", "SUCCESS", CLOCK.instant()));
        assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo("COMPLETE");
    }

    @FunctionalInterface
    private interface Retry {
        void run(UUID requestId) throws Exception;
    }

    private AuthUser newUser() {
        Instant now = CLOCK.instant();
        AuthUser user = AuthUser.register(
                "rider-" + UUID.randomUUID() + "@example.com",
                "hash",
                "vhash",
                now.plusSeconds(3600),
                now,
                EmailLocale.TR,
                Set.of(new Role(UUID.randomUUID(), RoleName.USER)),
                now);
        user.verifyEmail(now);
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        return user;
    }

    private Instant requestedAtInDatabase(UUID requestId) {
        return jdbc.queryForObject("SELECT requested_at FROM erasure_requests WHERE id = ?",
                Timestamp.class, requestId).toInstant();
    }

    private String recordingStatus(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT durable_recording_status FROM erasure_requests WHERE id = ?", String.class, requestId);
    }

    /** The first (canonical) version of the request's record object. */
    private static byte[] canonicalRecord(UUID requestId) {
        return new ObjectLockBucket(minioClient(), BUCKET).oldest(DurableErasureEvidence.recordKey(requestId))
                .orElseThrow().bytes();
    }

    private static int recordVersions(UUID requestId) throws Exception {
        String key = DurableErasureEvidence.recordKey(requestId);
        int versions = 0;
        for (Result<Item> result : minioClient().listObjects(ListObjectsArgs.builder()
                .bucket(BUCKET).prefix(key).includeVersions(true).build())) {
            Item item = result.get();
            if (key.equals(item.objectName()) && !item.isDeleteMarker()) {
                versions++;
            }
        }
        return versions;
    }

    private static String field(byte[] record, String name) throws IOException {
        JsonNode body = JSON.readTree(record);
        return body.path(name).asText();
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static MinioClient minioClient() {
        return MinioClient.builder().endpoint(minioEndpoint()).credentials(ACCESS_KEY, SECRET_KEY).region("us-east-1").build();
    }

    /** Fails the first frontier write that follows a record write, once armed. */
    static final class FrontierFailingMinioClient extends MinioClient {

        private volatile boolean armed;
        private volatile boolean recordWritten;
        private final AtomicInteger injectedFailures = new AtomicInteger();

        FrontierFailingMinioClient(MinioClient client) {
            super(client);
        }

        void failTheFrontierWriteAfterTheNextRecord() {
            recordWritten = false;
            armed = true;
        }

        int injectedFailures() {
            return injectedFailures.getAndSet(0);
        }

        @Override
        public ObjectWriteResponse putObject(PutObjectArgs args)
                throws ErrorResponseException, InsufficientDataException, InternalException, InvalidKeyException,
                InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
                XmlParserException {
            if (armed && recordWritten && DurableErasureEvidence.FRONTIER_KEY.equals(args.object())) {
                armed = false;
                injectedFailures.incrementAndGet();
                throw new IOException("injected: the frontier write failed after the record was published");
            }
            ObjectWriteResponse response = super.putObject(args);
            if (armed && args.object().startsWith("records/")) {
                recordWritten = true;
            }
            return response;
        }
    }

    /** A clock with nanosecond digits that the worker test moves forward. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
