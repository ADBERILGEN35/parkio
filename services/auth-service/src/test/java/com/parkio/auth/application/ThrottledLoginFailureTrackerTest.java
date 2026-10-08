package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The v2 rules of {@link ThrottledLoginFailureTracker} on an in-memory store with a virtual clock. */
class ThrottledLoginFailureTrackerTest {

    private static final Instant START = Instant.parse("2026-10-08T09:00:00Z");
    private static final String EMAIL = "owner@example.com";
    private static final String HOME = "198.51.100.7";
    private static final String ATTACKER = "203.0.113.9";

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private InMemoryLoginThrottleStore store;
    private ThrottledLoginFailureTracker tracker;

    @BeforeEach
    void setUp() {
        store = new InMemoryLoginThrottleStore(now::get);
        tracker = new ThrottledLoginFailureTracker(store);
    }

    @Test
    void pairTiersApplyToKnownAndUnknownClientsAlike() {
        tracker.clearAfterSuccess(EMAIL, HOME);
        for (int i = 0; i < 4; i++) {
            assertThat(tracker.recordFailure(EMAIL, HOME, now()).delay()).isZero();
        }
        assertThat(tracker.recordFailure(EMAIL, HOME, now()).delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(tracker.retryAfter(EMAIL, HOME, now())).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void accountTiersEscalateForUnknownClientsOnly() {
        tracker.clearAfterSuccess(EMAIL, HOME);
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        assertThat(tracker.retryAfter(EMAIL, "192.0.2.200", now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        assertThat(tracker.retryAfter(EMAIL, HOME, now())).isZero();

        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_SECOND_CAP - LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        assertThat(tracker.retryAfter(EMAIL, "192.0.2.200", now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_SECOND_DELAY);

        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_THIRD_CAP - LoginThrottlePolicy.ACCOUNT_SECOND_CAP);
        assertThat(tracker.retryAfter(EMAIL, "192.0.2.200", now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_THIRD_DELAY);
        assertThat(tracker.retryAfter(EMAIL, LoginFailureTracker.UNKNOWN_CLIENT, now()))
                .isEqualTo(LoginThrottlePolicy.ACCOUNT_THIRD_DELAY);
        // The known client is not delayed by other clients' failures, and its own failure reports only its pair.
        assertThat(tracker.retryAfter(EMAIL, HOME, now())).isZero();
        assertThat(tracker.recordFailure(EMAIL, HOME, now()).delay()).isZero();
    }

    @Test
    void retryAfterIsTheLongerOfThePairAndAccountWaitsForAnUnknownClient() {
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_FIRST_CAP - 5);
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure(EMAIL, ATTACKER, now());
        }
        // Pair: 30 s from its 5th failure; account: 10 s from its 50th. The longer one applies.
        assertThat(tracker.retryAfter(EMAIL, ATTACKER, now())).isEqualTo(Duration.ofSeconds(30));
        advance(Duration.ofSeconds(31));
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_THIRD_CAP - LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        assertThat(tracker.retryAfter(EMAIL, ATTACKER, now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_THIRD_DELAY);
    }

    @Test
    void keysCarryDigestsInTheV1LayoutNotTheRawEmailOrAddress() {
        String email = ThrottledLoginFailureTracker.digest(EMAIL);
        String client = ThrottledLoginFailureTracker.digest(HOME);
        assertThat(email).hasSize(32).matches("[0-9a-f]+");
        assertThat(ThrottledLoginFailureTracker.pairFailuresKey(email, client))
                .isEqualTo("auth:login:v2:pair:" + email + ":" + client + ":failures");
        assertThat(ThrottledLoginFailureTracker.pairWaitKey(email, client))
                .isEqualTo("auth:login:v2:pair:" + email + ":" + client + ":wait");
        assertThat(ThrottledLoginFailureTracker.accountFailuresKey(email)).isEqualTo("auth:login:v2:account:" + email + ":failures");
        assertThat(ThrottledLoginFailureTracker.accountWaitKey(email)).isEqualTo("auth:login:v2:account:" + email + ":wait");
        assertThat(ThrottledLoginFailureTracker.clientsKey(email)).isEqualTo("auth:login:v2:account:" + email + ":clients");
        assertThat(ThrottledLoginFailureTracker.knownKey(email, client)).isEqualTo("auth:login:v2:known:" + email + ":" + client);
        assertThat(ThrottledLoginFailureTracker.digest("other@example.com")).isNotEqualTo(email);
    }

    @Test
    void admissionLetsOneUnknownAttemptThroughEachAccountWaitAndClaimsIt() {
        tracker.clearAfterSuccess(EMAIL, HOME);
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_SECOND_CAP);
        advance(LoginThrottlePolicy.ACCOUNT_SECOND_DELAY);
        assertThat(tracker.retryAfter(EMAIL, "198.18.0.1", now())).isZero();

        assertThat(tracker.admit(EMAIL, "198.18.0.1", now())).isZero();
        // The wait is claimed for the admitted attempt: the next unknown client waits, the known one does not.
        assertThat(tracker.admit(EMAIL, "198.18.0.2", now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_SECOND_DELAY);
        assertThat(tracker.retryAfter(EMAIL, "198.18.0.3", now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_SECOND_DELAY);
        assertThat(tracker.admit(EMAIL, HOME, now())).isZero();
    }

    @Test
    void admissionClaimsThePairWaitSoParallelAttemptsFromOneClientGetOneEvaluation() {
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure(EMAIL, ATTACKER, now());
        }
        advance(Duration.ofSeconds(30));
        assertThat(tracker.admit(EMAIL, ATTACKER, now())).isZero();
        assertThat(tracker.admit(EMAIL, ATTACKER, now())).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void belowTheFirstTierAdmissionClaimsNothing() {
        assertThat(tracker.admit(EMAIL, ATTACKER, now())).isZero();
        assertThat(tracker.admit(EMAIL, ATTACKER, now())).isZero();
        assertThat(store.keys()).noneMatch(key -> key.endsWith(":wait"));
    }

    @Test
    void retryAfterClaimsNothing() {
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        advance(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        for (int i = 0; i < 3; i++) {
            assertThat(tracker.retryAfter(EMAIL, "198.18.0." + i, now())).isZero();
        }
        assertThat(tracker.admit(EMAIL, "198.18.0.9", now())).isZero();
    }

    /** B1 of the #311 review: concurrent attempts from many addresses get one evaluation per account wait. */
    @Test
    void concurrentAdmissionsFromManyAddressesAdmitExactlyOnePerWait() throws Exception {
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_THIRD_CAP);
        advance(LoginThrottlePolicy.ACCOUNT_THIRD_DELAY);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Duration>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String client = "198.18.1." + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return tracker.admit(EMAIL, client, now());
                }));
            }
            start.countDown();
            long admitted = 0;
            for (Future<Duration> result : results) {
                if (result.get(10, TimeUnit.SECONDS).isZero()) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theUnknownClientBucketIsNeverKnown() {
        tracker.clearAfterSuccess(EMAIL, LoginFailureTracker.UNKNOWN_CLIENT);
        tracker.clearAfterPasswordReset(EMAIL, LoginFailureTracker.UNKNOWN_CLIENT);
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_FIRST_CAP);

        assertThat(tracker.retryAfter(EMAIL, LoginFailureTracker.UNKNOWN_CLIENT, now()))
                .isEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        assertThat(store.keys()).noneMatch(key -> key.contains(":known:"));
    }

    @Test
    void aKnownClientStaysKnownForThirtyDaysAfterItsLastSuccess() {
        tracker.clearAfterSuccess(EMAIL, HOME);
        advance(LoginThrottlePolicy.KNOWN_CLIENT_TTL.minusDays(1));
        tracker.clearAfterSuccess(EMAIL, HOME);
        advance(LoginThrottlePolicy.KNOWN_CLIENT_TTL.minusMinutes(1));
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        assertThat(tracker.retryAfter(EMAIL, HOME, now())).isZero();

        advance(Duration.ofMinutes(2));
        failFromDistinctClients(1);
        assertThat(tracker.retryAfter(EMAIL, HOME, now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
    }

    @Test
    void successClearsOnlyThisClientsPairAndKeepsTheAccountCounter() {
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure(EMAIL, ATTACKER, now());
        }
        tracker.recordFailure(EMAIL, HOME, now());

        tracker.clearAfterSuccess(EMAIL, HOME);

        String email = ThrottledLoginFailureTracker.digest(EMAIL);
        assertThat(store.counter(ThrottledLoginFailureTracker.pairFailuresKey(email, ThrottledLoginFailureTracker.digest(HOME))))
                .isZero();
        assertThat(store.counter(ThrottledLoginFailureTracker.accountFailuresKey(email))).isEqualTo(21);
        assertThat(tracker.retryAfter(EMAIL, ATTACKER, now())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void resetClearsEveryPairKeepsTheAccountCounterAndMakesTheResettingClientKnown() {
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_THIRD_CAP);
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure(EMAIL, ATTACKER, now());
        }
        String laptop = "192.0.2.77";
        for (int i = 0; i < 10; i++) {
            tracker.recordFailure(EMAIL, laptop, now());
        }
        String phone = "2001:db8:aa:bb::/64";

        tracker.clearAfterPasswordReset(EMAIL, phone);

        String email = ThrottledLoginFailureTracker.digest(EMAIL);
        assertThat(store.keys()).noneMatch(key -> key.startsWith(ThrottledLoginFailureTracker.PREFIX + "pair:"));
        assertThat(store.counter(ThrottledLoginFailureTracker.accountFailuresKey(email))).isEqualTo(230);
        // The resetting client is known: the account wait does not apply to it; other unknown clients still wait.
        assertThat(tracker.retryAfter(EMAIL, phone, now())).isZero();
        assertThat(tracker.retryAfter(EMAIL, laptop, now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_THIRD_DELAY);
        assertThat(tracker.retryAfter(EMAIL, ATTACKER, now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_THIRD_DELAY);
    }

    @Test
    void resetWithoutAClientMarksNothingKnown() {
        tracker.recordFailure(EMAIL, HOME, now());
        tracker.clearAfterPasswordReset(EMAIL, null);
        assertThat(store.keys()).noneMatch(key -> key.contains(":known:"));
        assertThat(store.keys()).noneMatch(key -> key.startsWith(ThrottledLoginFailureTracker.PREFIX + "pair:"));
    }

    @Test
    void forgetAccountRemovesEverythingOfTheAccountOnly() {
        tracker.clearAfterSuccess(EMAIL, HOME);
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        tracker.recordFailure("neighbour@example.com", ATTACKER, now());

        tracker.forgetAccount(EMAIL);

        String email = ThrottledLoginFailureTracker.digest(EMAIL);
        assertThat(store.keys()).noneMatch(key -> key.contains(email));
        assertThat(store.keys()).anyMatch(key -> key.contains(ThrottledLoginFailureTracker.digest("neighbour@example.com")));
    }

    @Test
    void theKeySpaceHoldsNoRawEmailOrAddress() {
        tracker.clearAfterSuccess(EMAIL, HOME);
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        tracker.recordFailure(EMAIL, ATTACKER, now());
        assertThat(store.keys()).isNotEmpty().allSatisfy(key -> assertThat(key)
                .doesNotContain(EMAIL).doesNotContain("example.com").doesNotContain(HOME).doesNotContain(ATTACKER)
                .doesNotContain("192.0.2."));
    }

    private void failFromDistinctClients(long failures) {
        for (long i = 0; i < failures; i++) {
            // Spread over many addresses so no single pair reaches its own tier.
            tracker.recordFailure(EMAIL, "192.0.2." + (i % 250), now());
        }
    }

    private Instant now() {
        return now.get();
    }

    private void advance(Duration duration) {
        now.set(now.get().plus(duration));
    }
}
