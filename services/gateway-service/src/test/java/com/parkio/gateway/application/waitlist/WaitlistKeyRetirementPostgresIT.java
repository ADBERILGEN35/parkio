package com.parkio.gateway.application.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.gateway.infrastructure.persistence.waitlist.JdbcWaitlistInterestRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * CL-N08 / R-06: old-key email digests must not duplicate under a new email key; shared-secret
 * email rotation is refused without a dedicated token secret; with a dedicated token secret,
 * unsubscribe survives full email rehash and previous-secret removal.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class WaitlistKeyRetirementPostgresIT {

    private static final String OLD_EMAIL_SECRET = "old-email-waitlist-hash-secret-32chars!!";
    private static final String NEW_EMAIL_SECRET = "new-email-waitlist-hash-secret-32chars!!";
    private static final String TOKEN_SECRET = "stable-token-waitlist-hash-secret-32c!";
    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_gateway_n08")
                    .withUsername("parkio_gateway")
                    .withPassword("parkio_gateway");

    private static JdbcTemplate jdbc;
    private static JdbcWaitlistInterestRepository repository;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcWaitlistInterestRepository(jdbc);
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM waitlist_interest");
    }

    @Test
    void startupRefusesWhenPreviousRemovedWhileUnrehashedEmailRowsRemain() {
        String email = "old-key@parkio.dev";
        WaitlistHasher oldHasher = hasher(OLD_EMAIL_SECRET, "", TOKEN_SECRET);
        seedPending(email, oldHasher.hash(email), oldHasher.hashToken("withdraw-token-aaaaaaaaaaaaaaaaaaaa"));

        WaitlistHasher newOnly = hasher(NEW_EMAIL_SECRET, "", TOKEN_SECRET);
        WaitlistProperties props = props(NEW_EMAIL_SECRET, "", TOKEN_SECRET);
        WaitlistKeyRetirementGuard guard = new WaitlistKeyRetirementGuard(jdbc, newOnly, props);

        assertThatThrownBy(guard::assertEmailKeyRetirementSafe)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hash-secret-previous is required");
    }

    @Test
    void withPreviousKeyDuplicateSubmitIsBlockedAndOldUnsubscribeStillWorksAfterRehash() {
        String email = "rotate@parkio.dev";
        String withdrawToken = "withdraw-token-bbbbbbbbbbbbbbbbbbbb";
        WaitlistHasher oldHasher = hasher(OLD_EMAIL_SECRET, "", TOKEN_SECRET);
        String oldEmailHash = oldHasher.hash(email);
        seedPending(email, oldEmailHash, oldHasher.hashToken(withdrawToken));

        WaitlistProperties props = props(NEW_EMAIL_SECRET, OLD_EMAIL_SECRET, TOKEN_SECRET);
        WaitlistHasher hasher = new WaitlistHasher(props);
        WaitlistKeyRetirementGuard guard = new WaitlistKeyRetirementGuard(jdbc, hasher, props);
        guard.assertEmailKeyRetirementSafe();

        WaitlistApplicationService service = new WaitlistApplicationService(
                repository,
                hasher,
                (ip, em) -> reactor.core.publisher.Mono.empty(),
                new NoopEmailSender(),
                props,
                new WaitlistOpsNotifier() {
                    public void subscriptionConfirmed(java.util.UUID interestId, Instant confirmedAt) {}
                },
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                Clock.fixed(NOW, ZoneOffset.UTC));

        // Same address under rotation must not insert a second row.
        service.submit(new SubmitWaitlistCommand(
                        email, NOW, true, "waitlist-consent-v1", "Synthetic Tester", null, null,
                        "parkio.dev-landing", "en", "127.0.0.1", "it-agent"))
                .block();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_interest", Long.class)).isEqualTo(1L);
        String currentHash = hasher.hash(email);
        assertThat(jdbc.queryForObject("SELECT email_hash FROM waitlist_interest", String.class))
                .isEqualTo(currentHash);

        service.withdraw(withdrawToken).block();
        assertThat(jdbc.queryForObject("SELECT status FROM waitlist_interest", String.class))
                .isEqualTo("WITHDRAWN");
    }

    @Test
    void afterIdentityRehashPreviousMayBeRemovedWhileStableTokenSecretKeepsUnsubscribeWorking() {
        String email = "migrated@parkio.dev";
        String withdrawToken = "withdraw-token-cccccccccccccccccccc";
        WaitlistHasher rotating = hasher(NEW_EMAIL_SECRET, OLD_EMAIL_SECRET, TOKEN_SECRET);
        // Seed already on the new email digest (post on-contact migration) with token under stable secret.
        seedPending(email, rotating.hash(email), rotating.hashToken(withdrawToken));

        WaitlistProperties newOnly = props(NEW_EMAIL_SECRET, "", TOKEN_SECRET);
        WaitlistHasher hasher = new WaitlistHasher(newOnly);
        new WaitlistKeyRetirementGuard(jdbc, hasher, newOnly).assertEmailKeyRetirementSafe();

        WaitlistApplicationService service = new WaitlistApplicationService(
                repository,
                hasher,
                (ip, em) -> reactor.core.publisher.Mono.empty(),
                new NoopEmailSender(),
                newOnly,
                new WaitlistOpsNotifier() {
                    public void subscriptionConfirmed(java.util.UUID interestId, Instant confirmedAt) {}
                },
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.withdraw(withdrawToken).block();
        assertThat(jdbc.queryForObject("SELECT status FROM waitlist_interest", String.class))
                .isEqualTo("WITHDRAWN");
    }

    /**
     * R-06 / #415: shared-secret default after full email rehash. Before the fix, previous could
     * stay set (guard early-returned), then be removed after rehash; startup succeeded and the old
     * unsubscribe token returned WAITLIST_TOKEN_INVALID. Refuse the shared mid-rotation config so
     * that path cannot complete; dedicated token secret keeps unsubscribe working after previous
     * removal (see {@link #afterIdentityRehashPreviousMayBeRemovedWhileStableTokenSecretKeepsUnsubscribeWorking}).
     */
    @Test
    void afterIdentityRehash_sharedSecretWithPreviousRequiresDedicatedTokenSecret() {
        String email = "shared-retire@parkio.dev";
        String withdrawToken = "withdraw-token-dddddddddddddddddddd";
        WaitlistHasher sharedOld = hasher(OLD_EMAIL_SECRET, "", "");
        seedPending(email, sharedOld.hash(email), sharedOld.hashToken(withdrawToken));

        WaitlistProperties rotatingShared = props(NEW_EMAIL_SECRET, OLD_EMAIL_SECRET, "");
        WaitlistHasher rotatingHasher = new WaitlistHasher(rotatingShared);
        jdbc.update(
                "UPDATE waitlist_interest SET email_hash = ? WHERE email = ?",
                rotatingHasher.hash(email),
                email);

        assertThatThrownBy(() ->
                        new WaitlistKeyRetirementGuard(jdbc, rotatingHasher, rotatingShared)
                                .assertEmailKeyRetirementSafe())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hash-token-secret");

        // Token secret equal to the new email secret is also refused (still couples retirement).
        WaitlistProperties tokenEqualsCurrent = props(NEW_EMAIL_SECRET, OLD_EMAIL_SECRET, NEW_EMAIL_SECRET);
        assertThatThrownBy(() ->
                        new WaitlistKeyRetirementGuard(
                                        jdbc, new WaitlistHasher(tokenEqualsCurrent), tokenEqualsCurrent)
                                .assertEmailKeyRetirementSafe())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }

    private static void seedPending(String email, String emailHash, String withdrawHash) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO waitlist_interest (
                    id, email, email_hash, consent_timestamp, client_consent_timestamp,
                    consent_text_version, full_name, source, locale, status,
                    verification_token_hash, withdraw_token_hash, verification_expires_at,
                    verification_sent_at, resend_count, ip_hash, created_at
                ) VALUES (?, ?, ?, ?, ?, 'waitlist-consent-v1', 'Synthetic', 'parkio.dev-landing', 'en', 'PENDING',
                          ?, ?, ?, ?, 0, 'ip', ?)
                """,
                id,
                email,
                emailHash,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                "verify-hash",
                withdrawHash,
                Timestamp.from(NOW.plusSeconds(3600)),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }

    private static WaitlistHasher hasher(String email, String emailPrev, String token) {
        return new WaitlistHasher(props(email, emailPrev, token));
    }

    private static WaitlistProperties props(String email, String emailPrev, String token) {
        WaitlistProperties properties = new WaitlistProperties();
        properties.setHashSecret(email);
        properties.setHashSecretPrevious(emailPrev);
        properties.setHashTokenSecret(token);
        properties.setAdmissionsEnabled(true);
        properties.setConsentRequired(true);
        return properties;
    }

    private static final class NoopEmailSender implements WaitlistEmailSender {
        @Override
        public void sendConfirmation(String email, String verificationToken, String withdrawToken, String locale) {}

        @Override
        public void sendWithdrawalNotice(String email, String locale) {}
    }
}
