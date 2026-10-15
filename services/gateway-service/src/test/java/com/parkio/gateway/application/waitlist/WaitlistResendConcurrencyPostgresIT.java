package com.parkio.gateway.application.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.parkio.gateway.infrastructure.persistence.waitlist.JdbcWaitlistInterestRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * F-33: two concurrent resend readers of the same pending row must yield exactly one send claim,
 * one mock provider dispatch, durable counter/token state for the winner, and refuse a stale
 * completion after a newer claim.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class WaitlistResendConcurrencyPostgresIT {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final String EMAIL = "resend-race@parkio.dev";
    private static final String HASH_SECRET = "test-only-waitlist-hash-secret-0123456789";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_gateway")
                    .withUsername("parkio_gateway")
                    .withPassword("parkio_gateway");

    private static JdbcTemplate jdbc;
    private static JdbcWaitlistInterestRepository realRepository;
    private static WaitlistHasher hasher;
    private static WaitlistProperties properties;
    private static Clock clock;

    private JdbcWaitlistInterestRepository repository;
    private WaitlistEmailSender emailSender;
    private WaitlistApplicationService service;
    private final AtomicInteger sendCount = new AtomicInteger();
    private final AtomicReference<String> sentVerificationToken = new AtomicReference<>();
    private final AtomicReference<String> sentWithdrawToken = new AtomicReference<>();

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        realRepository = new JdbcWaitlistInterestRepository(jdbc);
        properties = new WaitlistProperties();
        properties.setAdmissionsEnabled(true);
        properties.setHashSecret(HASH_SECRET);
        properties.setResendCooldown(Duration.ofMinutes(5));
        properties.setMaxResends(5);
        properties.setTokenTtl(Duration.ofHours(48));
        hasher = new WaitlistHasher(properties);
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM waitlist_interest");
        sendCount.set(0);
        sentVerificationToken.set(null);
        sentWithdrawToken.set(null);
        repository = spy(realRepository);
        emailSender = mock(WaitlistEmailSender.class);
        doAnswer(invocation -> {
            sendCount.incrementAndGet();
            sentVerificationToken.set(invocation.getArgument(1));
            sentWithdrawToken.set(invocation.getArgument(2));
            return null;
        }).when(emailSender).sendConfirmation(anyString(), anyString(), anyString(), anyString());
        WaitlistRateLimiter rateLimiter = mock(WaitlistRateLimiter.class);
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
        WaitlistOpsNotifier opsNotifier = mock(WaitlistOpsNotifier.class);
        TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
        service = new WaitlistApplicationService(
                repository, hasher, rateLimiter, emailSender, properties, opsNotifier, tx, clock);
    }

    @Test
    void barrierSynchronizedReadersYieldOneClaimOneSendAndUsableWinningToken() throws Exception {
        String initialVerification = "initial-verification-token-aaaaaaaa";
        String initialWithdraw = "initial-withdraw-token-bbbbbbbb";
        insertPending(EMAIL, initialVerification, initialWithdraw, FIXED_NOW.minus(Duration.ofHours(1)), 0);

        CyclicBarrier afterPendingRead = new CyclicBarrier(2);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<WaitlistInterest> row = (Optional<WaitlistInterest>) invocation.callRealMethod();
            if (row.isPresent() && row.get().status() == WaitlistStatus.PENDING) {
                afterPendingRead.await(15, TimeUnit.SECONDS);
            }
            return row;
        }).when(repository).findByEmailHash(anyString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            SubmitWaitlistCommand command = command(EMAIL);
            Future<?> a = pool.submit(() -> service.submit(command).block());
            Future<?> b = pool.submit(() -> service.submit(command).block());
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(sendCount.get()).isEqualTo(1);
        String winningVerification = sentVerificationToken.get();
        String winningWithdraw = sentWithdrawToken.get();
        assertThat(winningVerification).isNotBlank();
        assertThat(winningWithdraw).isNotBlank();
        assertThat(winningVerification).isNotEqualTo(initialVerification);

        WaitlistInterest row = realRepository.findByEmailHash(hasher.hash(EMAIL)).orElseThrow();
        assertThat(row.verificationTokenHash()).isEqualTo(hasher.hash(winningVerification));
        assertThat(row.withdrawTokenHash()).isEqualTo(hasher.hash(winningWithdraw));
        assertThat(row.resendCount()).isEqualTo(1);
        assertThat(row.verificationClaimVersion()).isEqualTo(1L);
        assertThat(row.verificationSentAt()).isEqualTo(FIXED_NOW);

        assertThat(realRepository.confirmByTokenHash(hasher.hash(winningVerification), FIXED_NOW.plusSeconds(1)))
                .isTrue();
        assertThat(realRepository.findByEmailHash(hasher.hash(EMAIL)).orElseThrow().status())
                .isEqualTo(WaitlistStatus.CONFIRMED);
    }

    @Test
    void staleCompletionCannotOverwriteANewerClaim() {
        String emailHash = hasher.hash(EMAIL);
        insertPending(EMAIL, "token-v0-aaaaaaaaaaaaaaaa", "withdraw-v0-bbbbbbbbbbbb", null, 0);

        String claim1Verification = "token-v1-cccccccccccccccc";
        String claim1Withdraw = "withdraw-v1-dddddddddddd";
        assertThat(realRepository.claimPendingVerification(
                        emailHash,
                        0L,
                        hasher.hash(claim1Verification),
                        hasher.hash(claim1Withdraw),
                        FIXED_NOW.plus(Duration.ofHours(48))))
                .hasValue(1L);

        String claim2Verification = "token-v2-eeeeeeeeeeeeeeee";
        String claim2Withdraw = "withdraw-v2-ffffffffffff";
        assertThat(realRepository.claimPendingVerification(
                        emailHash,
                        1L,
                        hasher.hash(claim2Verification),
                        hasher.hash(claim2Withdraw),
                        FIXED_NOW.plus(Duration.ofHours(48))))
                .hasValue(2L);

        assertThat(realRepository.markVerificationSent(
                        emailHash, 1L, FIXED_NOW.minusSeconds(30), 9))
                .isFalse();
        WaitlistInterest afterStale = realRepository.findByEmailHash(emailHash).orElseThrow();
        assertThat(afterStale.verificationTokenHash()).isEqualTo(hasher.hash(claim2Verification));
        assertThat(afterStale.verificationClaimVersion()).isEqualTo(2L);
        assertThat(afterStale.resendCount()).isZero();
        assertThat(afterStale.verificationSentAt()).isNull();

        assertThat(realRepository.markVerificationSent(
                        emailHash, 2L, FIXED_NOW, 1))
                .isTrue();
        WaitlistInterest afterWin = realRepository.findByEmailHash(emailHash).orElseThrow();
        assertThat(afterWin.resendCount()).isEqualTo(1);
        assertThat(afterWin.verificationSentAt()).isEqualTo(FIXED_NOW);
        assertThat(afterWin.verificationTokenHash()).isEqualTo(hasher.hash(claim2Verification));
        assertThat(afterWin.verificationClaimVersion()).isEqualTo(2L);
    }

    private static void insertPending(
            String email,
            String verificationToken,
            String withdrawToken,
            Instant sentAt,
            int resendCount) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO waitlist_interest (
                    id, email, email_hash, consent_timestamp, source, ip_hash, created_at,
                    status, locale, verification_token_hash, withdraw_token_hash,
                    verification_expires_at, verification_sent_at, resend_count)
                VALUES (?, ?, ?, ?, 'parkio.dev-landing', 'ip-hash', ?, 'PENDING', 'tr', ?, ?, ?, ?, ?)
                """,
                id,
                email,
                hasher.hash(email),
                Timestamp.from(FIXED_NOW.minus(Duration.ofDays(1))),
                Timestamp.from(FIXED_NOW.minus(Duration.ofDays(1))),
                hasher.hash(verificationToken),
                hasher.hash(withdrawToken),
                Timestamp.from(FIXED_NOW.plus(Duration.ofHours(24))),
                sentAt == null ? null : Timestamp.from(sentAt),
                resendCount);
    }

    private static SubmitWaitlistCommand command(String email) {
        return new SubmitWaitlistCommand(
                email,
                FIXED_NOW.minusSeconds(1),
                true,
                WaitlistConsentText.CURRENT_VERSION,
                null,
                null,
                null,
                "parkio.dev-landing",
                "tr",
                "127.0.0.1",
                "it-agent");
    }
}
