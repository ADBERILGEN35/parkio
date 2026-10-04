package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.PasswordResetLimiter;
import com.parkio.auth.application.VerificationResendLimiter;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.PasswordResetEmailSender;
import com.parkio.auth.application.port.RoleRepository;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * CL-F14.2 measurement harness (owner decision B10a): login, forgot-password and
 * resend-verification must take the same time for an existing and an unknown account. For each
 * endpoint, 500 interleaved pairs (after 50 warm-up pairs) are timed through the whole HTTP stack
 * on PostgreSQL; the medians may differ by less than 5 ms. The e-mail provider is a stand-in that
 * takes 25 ms per message, like a real provider call, and every message must still be delivered.
 * Synthetic accounts only; the lockout tracker and the cooldown limiters are stubbed open so every
 * sample is comparable.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class AccountExistenceTimingPostgresIT {

    private static final String GATEWAY_SECRET = "test-only-parkio-gateway-internal-secret-0123456789";
    private static final int WARMUP = 50;
    private static final int SAMPLES = 500;
    private static final double MAX_MEDIAN_DIFFERENCE_MS = 5.0;
    private static final long PROVIDER_LATENCY_MS = 25;
    private static final String CORRECT_PASSWORD = "CorrectHorse-123";
    private static final String WRONG_PASSWORD = "WrongHorse-456";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_timing_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
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
        registry.add("parkio.security.email-verification.log-token", () -> "false");
        registry.add("parkio.security.password-reset.log-token", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthUserRepository authUsers;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RoleJpaRepository roleEntities;

    @MockBean private LoginFailureTracker loginFailures;
    @MockBean private VerificationResendLimiter verificationResendLimiter;
    @MockBean private PasswordResetLimiter passwordResetLimiter;
    @MockBean private EmailVerificationSender emailVerificationSender;
    @MockBean private PasswordResetEmailSender passwordResetEmailSender;

    private final AtomicInteger verificationsDelivered = new AtomicInteger();
    private final AtomicInteger resetsDelivered = new AtomicInteger();
    private final Random order = new Random(20261003L);

    @BeforeEach
    void stubCollaborators() {
        if (roleEntities.findByName(RoleName.USER).isEmpty()) {
            roleEntities.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
        when(loginFailures.isLocked(anyString(), any())).thenReturn(false);
        when(loginFailures.recordFailure(anyString(), any()))
                .thenReturn(new LoginFailureTracker.LoginFailureOutcome(1, false, null));
        when(verificationResendLimiter.tryAcquire(anyString())).thenReturn(true);
        when(passwordResetLimiter.tryAcquire(anyString())).thenReturn(true);
        doAnswer(invocation -> {
            Thread.sleep(PROVIDER_LATENCY_MS);
            verificationsDelivered.incrementAndGet();
            return null;
        }).when(emailVerificationSender).sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));
        doAnswer(invocation -> {
            Thread.sleep(PROVIDER_LATENCY_MS);
            resetsDelivered.incrementAndGet();
            return null;
        }).when(passwordResetEmailSender).sendResetLink(anyString(), anyString(), any(EmailLocale.class));
    }

    @Test
    void loginTakesTheSameTimeForAnUnknownAccountAsForAWrongPassword() throws Exception {
        List<String> existing = seed("timing-login", true);

        Timings timings = interleave(existing, email -> post("/api/v1/auth/login",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, WRONG_PASSWORD)));

        timings.assertMediansWithinThreshold("login");
    }

    @Test
    void forgotPasswordTakesTheSameTimeAndStillDeliversEveryResetLink() throws Exception {
        List<String> existing = seed("timing-forgot", true);
        resetsDelivered.set(0);

        Timings timings = interleave(existing, email -> post("/api/v1/auth/forgot-password",
                "{\"email\":\"%s\",\"locale\":\"en\"}".formatted(email)));

        timings.assertMediansWithinThreshold("forgot-password");
        awaitDeliveries(resetsDelivered, existing.size());
    }

    @Test
    void resendVerificationTakesTheSameTimeAndStillDeliversEveryLink() throws Exception {
        List<String> existing = seed("timing-resend", false);
        verificationsDelivered.set(0);

        Timings timings = interleave(existing, email -> post("/api/v1/auth/resend-verification",
                "{\"email\":\"%s\",\"locale\":\"en\"}".formatted(email)));

        timings.assertMediansWithinThreshold("resend-verification");
        awaitDeliveries(verificationsDelivered, existing.size());
    }

    /** One existing account per sample, so no sample sees state left by another. */
    private List<String> seed(String prefix, boolean verified) {
        String passwordHash = new BCryptPasswordEncoder().encode(CORRECT_PASSWORD);
        Role user = roleRepository.findByName(RoleName.USER).orElseThrow();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < WARMUP + SAMPLES; i++) {
            String email = (prefix + "-" + i + "-" + UUID.randomUUID() + "@timing.parkio.test").toLowerCase(Locale.ROOT);
            AuthUser account = AuthUser.register(email, passwordHash, "seed-" + UUID.randomUUID(),
                    now.plus(1, ChronoUnit.DAYS), now, EmailLocale.EN, Set.of(user), now);
            if (verified) {
                account.verifyEmail(now);
            }
            authUsers.save(account);
            emails.add(email);
        }
        return emails;
    }

    private Timings interleave(List<String> existing, RequestFor request) throws Exception {
        Timings timings = new Timings();
        for (int i = 0; i < existing.size(); i++) {
            String unknown = "timing-unknown-" + UUID.randomUUID() + "@timing.parkio.test";
            boolean existingFirst = order.nextBoolean();
            long first = time(request, existingFirst ? existing.get(i) : unknown);
            long second = time(request, existingFirst ? unknown : existing.get(i));
            if (i >= WARMUP) {
                timings.existing.add(existingFirst ? first : second);
                timings.unknown.add(existingFirst ? second : first);
            }
        }
        return timings;
    }

    private long time(RequestFor request, String email) throws Exception {
        long started = System.nanoTime();
        int status = mockMvc.perform(request.build(email)).andReturn().getResponse().getStatus();
        long elapsed = System.nanoTime() - started;
        assertThat(status).as("uniform status").isIn(200, 202, 401);
        return elapsed;
    }

    private static MockHttpServletRequestBuilder post(String path, String body) {
        return MockMvcRequestBuilders.post(path)
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static void awaitDeliveries(AtomicInteger delivered, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (delivered.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(delivered.get()).as("messages delivered").isEqualTo(expected);
    }

    @FunctionalInterface
    private interface RequestFor {
        MockHttpServletRequestBuilder build(String email);
    }

    private static final class Timings {
        private final List<Long> existing = new ArrayList<>();
        private final List<Long> unknown = new ArrayList<>();

        void assertMediansWithinThreshold(String endpoint) {
            double existingMedian = medianMs(existing);
            double unknownMedian = medianMs(unknown);
            double difference = existingMedian - unknownMedian;
            System.out.printf(Locale.ROOT,
                    "TIMING endpoint=%s pairs=%d existingMedianMs=%.3f unknownMedianMs=%.3f diffMs=%.3f "
                            + "existingP90Ms=%.3f unknownP90Ms=%.3f%n",
                    endpoint, existing.size(), existingMedian, unknownMedian, difference,
                    percentileMs(existing, 0.9), percentileMs(unknown, 0.9));
            assertThat(existing).hasSize(SAMPLES);
            assertThat(Math.abs(difference))
                    .as("%s median difference (existing %.3f ms, unknown %.3f ms)", endpoint, existingMedian, unknownMedian)
                    .isLessThan(MAX_MEDIAN_DIFFERENCE_MS);
        }

        private static double medianMs(List<Long> nanos) {
            return percentileMs(nanos, 0.5);
        }

        private static double percentileMs(List<Long> nanos, double quantile) {
            List<Long> sorted = new ArrayList<>(nanos);
            Collections.sort(sorted);
            int n = sorted.size();
            double position = quantile * (n - 1);
            int lower = (int) Math.floor(position);
            int upper = (int) Math.ceil(position);
            double value = sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * (position - lower);
            return value / 1_000_000.0;
        }
    }
}
