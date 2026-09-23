package com.parkio.gateway.infrastructure.waitlist.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gateway.application.waitlist.SubmitWaitlistCommand;
import com.parkio.gateway.application.waitlist.WaitlistApplicationService;
import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistHasher;
import com.parkio.gateway.application.waitlist.WaitlistInterest;
import com.parkio.gateway.application.waitlist.WaitlistInterestRepository;
import com.parkio.gateway.application.waitlist.WaitlistOpsNotifier;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import com.parkio.gateway.application.waitlist.WaitlistTokenException;
import com.parkio.gateway.infrastructure.security.JwtTokenValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Synthetic-data acceptance for the gateway half of waitlist Slack
 * notifications: outbox coupling to the confirmation transaction and the
 * export into the slack_biz inbox. No Slack or email is contacted.
 */
@SpringBootTest(properties = {
        "parkio.waitlist.ops-notifications.enabled=true",
        "parkio.waitlist.ops-notifications.environment=acceptance",
        "parkio.waitlist.ops-notifications.contract-version=2",
        // Scheduler must not race the explicit exportDue() calls below.
        "parkio.waitlist.ops-notifications.poll-interval=PT1H",
        "parkio.waitlist.ops-notifications.max-export-attempts=3"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class WaitlistOpsNotificationOutboxTest {

    private static final String EMAIL = "synthetic.subscriber@example.test";

    @TempDir
    static Path inbox;

    @Autowired
    private WaitlistApplicationService service;

    @Autowired
    private WaitlistOpsNotificationExporter exporter;

    @Autowired
    private WaitlistOpsNotificationProperties properties;

    @Autowired
    private WaitlistOpsNotifier notifier;

    @Autowired
    private WaitlistHasher hasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @SpyBean
    private WaitlistInterestRepository repository;

    @MockBean
    private WaitlistRateLimiter rateLimiter;

    @MockBean
    private WaitlistEmailSender emailSender;

    @MockBean
    private JwtTokenValidator tokenValidator;

    private final AtomicReference<String> verificationToken = new AtomicReference<>();
    private final AtomicReference<String> withdrawToken = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        properties.setExportDir(inbox.toString());
        try (Stream<Path> files = Files.list(inbox)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
        doAnswer(invocation -> {
            verificationToken.set(invocation.getArgument(1));
            withdrawToken.set(invocation.getArgument(2));
            return null;
        }).when(emailSender).sendConfirmation(anyString(), anyString(), any(), anyString());
        jdbcTemplate.update("DELETE FROM waitlist_ops_notification_outbox");
        jdbcTemplate.update("DELETE FROM waitlist_interest");
    }

    @AfterEach
    void restore() {
        properties.setExportDir(inbox.toString());
        properties.setEnabled(true);
        properties.setContractVersion(2);
        properties.setMinFreeBytes(512L * 1024 * 1024);
        properties.setMaxInboxBacklog(5000);
        properties.setBatchSize(20);
    }

    @Test
    void committedConfirmationIsExportedOnceWithOnlyAllowListedFields(CapturedOutput output) throws Exception {
        submitPending();
        assertThat(outboxCount()).isZero();

        service.confirm(verificationToken.get()).block();

        assertThat(outboxCount()).isEqualTo(1);
        assertThat(exporter.exportDue().exported()).isEqualTo(1);
        assertThat(outboxStatus()).isEqualTo("EXPORTED");

        List<Path> files = inboxFiles();
        assertThat(files).hasSize(1);
        String raw = Files.readString(files.get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = objectMapper.readValue(raw, Map.class);
        assertThat(envelope).containsOnlyKeys(
                "contractVersion", "eventId", "eventType", "occurredAt", "environment", "producer", "dedupKey",
                "fullName", "confirmedTotal", "confirmedTodayIstanbul", "countsSnapshotAt");
        assertThat(envelope.get("contractVersion")).isEqualTo(2);
        assertThat(envelope.get("eventType")).isEqualTo("waitlist.subscription_confirmed");
        assertThat(envelope.get("environment")).isEqualTo("acceptance");
        assertThat(envelope.get("producer")).isEqualTo("gateway-waitlist-outbox");
        assertThat((String) envelope.get("dedupKey")).matches("waitlist:subscription_confirmed:[0-9a-f]{64}");
        assertThat(envelope.get("fullName")).isEqualTo("Ayşe Yılmaz");
        assertThat(((Number) envelope.get("confirmedTotal")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat(((Number) envelope.get("confirmedTodayIstanbul")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat((String) envelope.get("countsSnapshotAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

        assertNoProhibitedValues(raw);
        assertNoProhibitedValues(output.getAll());
    }

    @Test
    void successfulHandoffFreezesEnvelopeOnExportRetry() throws Exception {
        submitPending();
        service.confirm(verificationToken.get()).block();
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(exporter.exportDue().exported()).isEqualTo(1);
        Path file = inboxFiles().get(0);
        String first = Files.readString(file);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstEnv = objectMapper.readValue(first, Map.class);
        Object firstSnap = firstEnv.get("countsSnapshotAt");

        // Crash window: inbox file exists, outbox still PENDING.
        jdbcTemplate.update("UPDATE waitlist_ops_notification_outbox SET status = 'PENDING', exported_at = NULL");
        makeDue();
        WaitlistOpsNotificationExporter.ExportResult again = exporter.exportDue();
        assertThat(again.exported()).isEqualTo(1);
        assertThat(outboxStatus()).isEqualTo("EXPORTED");
        assertThat(Files.readString(file)).isEqualTo(first);
        assertThat(objectMapper.readValue(Files.readString(file), Map.class).get("countsSnapshotAt"))
                .isEqualTo(firstSnap);
        assertThat(inboxFiles()).hasSize(1);
    }

    @Test
    void contractVersionOneOmitsNameAndCounts() throws Exception {
        int previous = properties.getContractVersion();
        properties.setContractVersion(1);
        try {
            submitPending();
            service.confirm(verificationToken.get()).block();
            assertThat(exporter.exportDue().exported()).isEqualTo(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = objectMapper.readValue(Files.readString(inboxFiles().get(0)), Map.class);
            assertThat(envelope).containsOnlyKeys(
                    "contractVersion", "eventId", "eventType", "occurredAt", "environment", "producer", "dedupKey");
            assertThat(envelope.get("contractVersion")).isEqualTo(1);
        } finally {
            properties.setContractVersion(previous);
        }
    }

    @Test
    void repeatedConfirmAndRedeliveryDoNotCreateSecondNotification() throws Exception {
        submitPending();
        service.confirm(verificationToken.get()).block();
        service.confirm(verificationToken.get()).block(); // idempotent scanner retry

        UUID interestId = interestId();
        notifier.subscriptionConfirmed(interestId, Instant.now()); // replayed application event

        assertThat(outboxCount()).isEqualTo(1);
        assertThat(counter("duplicate_suppressed")).isGreaterThanOrEqualTo(1.0);
        exporter.exportDue();
        exporter.exportDue();
        assertThat(inboxFiles()).hasSize(1);
    }

    @Test
    void rolledBackConfirmationEmitsNothing() {
        submitPending();
        String tokenHash = hasher.hash(verificationToken.get());
        UUID interestId = interestId();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(repository.confirmByTokenHash(tokenHash, Instant.now())).isTrue();
            notifier.subscriptionConfirmed(interestId, Instant.now());
            tx.setRollbackOnly();
        });

        assertThat(interestStatus()).isEqualTo("PENDING");
        assertThat(outboxCount()).isZero();
        assertThat(exporter.exportDue().exported()).isZero();
        assertThat(inboxFiles()).isEmpty();
    }

    @Test
    void failureAfterStatusUpdateRollsBackBothConfirmationAndNotification() {
        submitPending();
        doThrow(new IllegalStateException("synthetic failure"))
                .when(repository).findByVerificationTokenHash(anyString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirm(verificationToken.get()).block())
                .isInstanceOf(IllegalStateException.class);

        assertThat(interestStatus()).isEqualTo("PENDING");
        assertThat(outboxCount()).isZero();
    }

    @Test
    void outboxWriteFailureNeverFailsTheConfirmation() {
        submitPending();
        jdbcTemplate.execute("ALTER TABLE waitlist_ops_notification_outbox RENAME TO waitlist_ops_outbox_hidden");
        try {
            double before = counter("record_failed");
            service.confirm(verificationToken.get()).block();
            assertThat(interestStatus()).isEqualTo("CONFIRMED");
            assertThat(counter("record_failed")).isEqualTo(before + 1);
        } finally {
            jdbcTemplate.execute("ALTER TABLE waitlist_ops_outbox_hidden RENAME TO waitlist_ops_notification_outbox");
        }
        assertThat(outboxCount()).isZero();
    }

    @Test
    void exportFailuresAreRetriedWithBackoffThenStopPermanently() {
        submitPending();
        service.confirm(verificationToken.get()).block();
        properties.setExportDir(inbox.resolve("missing-dir").toString());

        WaitlistOpsNotificationExporter.ExportResult first = exporter.exportDue();
        assertThat(first.retried()).isEqualTo(1);
        assertThat(outboxStatus()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error_category FROM waitlist_ops_notification_outbox", String.class))
                .isEqualTo("export_dir_missing");
        // Backoff: not due again immediately.
        assertThat(exporter.exportDue().retried()).isZero();

        makeDue();
        assertThat(exporter.exportDue().retried()).isEqualTo(1);
        makeDue();
        assertThat(exporter.exportDue().failed()).isEqualTo(1);
        assertThat(outboxStatus()).isEqualTo("FAILED");

        makeDue();
        properties.setExportDir(inbox.toString());
        WaitlistOpsNotificationExporter.ExportResult after = exporter.exportDue();
        assertThat(after.exported() + after.retried() + after.failed()).isZero();
        assertThat(inboxFiles()).isEmpty();
    }

    @Test
    void inboxBacklogDefersExportWithoutConsumingAttemptsOrDroppingRows() throws Exception {
        int previous = properties.getMaxInboxBacklog();
        properties.setMaxInboxBacklog(3);
        try {
            submitPending();
            service.confirm(verificationToken.get()).block();
            for (int i = 0; i < 3; i++) {
                Files.writeString(inbox.resolve("unconsumed-" + i + ".json"), "{}");
            }
            for (int poll = 0; poll < 10; poll++) {
                WaitlistOpsNotificationExporter.ExportResult r = exporter.exportDue();
                assertThat(r.deferred()).isEqualTo("inbox_backlog");
                assertThat(r.exported() + r.retried() + r.failed()).isZero();
            }
            assertThat(outboxStatus()).isEqualTo("PENDING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempts FROM waitlist_ops_notification_outbox", Integer.class)).isZero();
            assertThat(meterRegistry.get("parkio.waitlist.ops.outbox.pending").gauge().value()).isEqualTo(1.0);
            assertThat(meterRegistry.get("parkio.waitlist.ops.inbox.backlog").gauge().value()).isEqualTo(3.0);
            assertThat(counter("export_deferred_inbox_backlog")).isGreaterThanOrEqualTo(10.0);

            Files.delete(inbox.resolve("unconsumed-0.json")); // relay drains below the bound
            WaitlistOpsNotificationExporter.ExportResult resumed = exporter.exportDue();
            assertThat(resumed.deferred()).isNull();
            assertThat(resumed.exported()).isEqualTo(1);
            assertThat(meterRegistry.get("parkio.waitlist.ops.outbox.pending").gauge().value()).isZero();
        } finally {
            properties.setMaxInboxBacklog(previous);
        }
    }

    @Test
    void lowDiskDefersExportAndConfirmationStillSucceeds() {
        long previous = properties.getMinFreeBytes();
        properties.setMinFreeBytes(Long.MAX_VALUE);
        try {
            submitPending();
            service.confirm(verificationToken.get()).block();
            assertThat(interestStatus()).isEqualTo("CONFIRMED");
            for (int poll = 0; poll < properties.getMaxExportAttempts() + 3; poll++) {
                assertThat(exporter.exportDue().deferred()).isEqualTo("low_disk");
            }
            assertThat(outboxStatus()).isEqualTo("PENDING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempts FROM waitlist_ops_notification_outbox", Integer.class)).isZero();
            assertThat(inboxFiles()).isEmpty();
        } finally {
            properties.setMinFreeBytes(previous);
        }
        assertThat(exporter.exportDue().exported()).isEqualTo(1);
    }

    @Test
    void backoffIsBounded() {
        assertThat(exporter.backoff(1)).isEqualTo(properties.getRetryBaseDelay());
        assertThat(exporter.backoff(2)).isEqualTo(properties.getRetryBaseDelay().multipliedBy(2));
        assertThat(exporter.backoff(60)).isEqualTo(properties.getRetryMaxDelay());
    }

    @Test
    void batchSizeCapsExportsPerPoll() {
        int previous = properties.getBatchSize();
        properties.setBatchSize(2);
        try {
            for (int i = 0; i < 5; i++) {
                notifier.subscriptionConfirmed(UUID.randomUUID(), Instant.now());
            }
            assertThat(outboxCount()).isEqualTo(5);
            assertThat(exporter.exportDue().exported()).isEqualTo(2);
            assertThat(inboxFiles()).hasSize(2);
        } finally {
            properties.setBatchSize(previous);
        }
    }

    @Test
    void runtimeDisabledRecordsNothing() {
        properties.setEnabled(false);
        submitPending();
        service.confirm(verificationToken.get()).block();
        assertThat(interestStatus()).isEqualTo("CONFIRMED");
        assertThat(outboxCount()).isZero();
        assertThat(exporter.exportDue().exported()).isZero();
    }

    @Test
    void invalidTokenStillRejectedAndEmitsNothing() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirm("not-a-real-token").block())
                .isInstanceOf(WaitlistTokenException.class);
        assertThat(outboxCount()).isZero();
    }

    private void submitPending() {
        service.submit(new SubmitWaitlistCommand(
                EMAIL, Instant.now(), "Ayşe Yılmaz", "Izmir", "driver", "parkio.dev-landing", "tr",
                "198.51.100.23", "synthetic-agent")).block();
        assertThat(verificationToken.get()).isNotNull();
    }

    private void assertNoProhibitedValues(String text) {
        WaitlistInterest row = repository.findByEmailHash(hasher.hash(EMAIL)).orElse(null);
        assertThat(text).doesNotContain(EMAIL, "198.51.100.23", "Izmir", verificationToken.get(), withdrawToken.get());
        assertThat(text).doesNotContain("waitlist/confirm", "waitlist/unsubscribe");
        // Envelope JSON may include fullName (allowlisted for Slack). Assert no email/IP/tokens.
        if (row != null) {
            assertThat(text).doesNotContain(row.id().toString(), row.emailHash());
        }
    }

    private UUID interestId() {
        return jdbcTemplate.queryForObject("SELECT id FROM waitlist_interest", UUID.class);
    }

    private String interestStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM waitlist_interest", String.class);
    }

    private int outboxCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM waitlist_ops_notification_outbox", Integer.class);
    }

    private String outboxStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM waitlist_ops_notification_outbox", String.class);
    }

    private void makeDue() {
        jdbcTemplate.update("UPDATE waitlist_ops_notification_outbox SET next_attempt_at = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
    }

    private double counter(String outcome) {
        var counter = meterRegistry.find(JdbcWaitlistOpsNotificationOutbox.METRIC).tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private List<Path> inboxFiles() {
        try (Stream<Path> files = Files.list(inbox)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
