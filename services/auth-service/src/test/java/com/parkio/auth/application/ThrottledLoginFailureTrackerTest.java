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
        tracker = new ThrottledLoginFailureTracker(store, LoginThrottleTestKeys.CURRENT);
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
    void keysCarryKeyedDigestsUnderTheSecretsKeyIdNotTheRawEmailOrAddress() {
        String email = tracker.digest(EMAIL);
        String client = tracker.digest(HOME);
        assertThat(email).hasSize(32).matches("[0-9a-f]+");
        assertThat(tracker.prefix()).isEqualTo("auth:login:v3:" + LoginThrottleTestKeys.CURRENT.kid() + ":");
        assertThat(LoginThrottleTestKeys.CURRENT.kid()).hasSize(8).matches("[0-9a-f]+");
        assertThat(tracker.pairFailuresKey(email, client))
                .isEqualTo(tracker.prefix() + "pair:" + email + ":" + client + ":failures");
        assertThat(tracker.accountFailuresKey(email)).isEqualTo(tracker.prefix() + "account:" + email + ":failures");
        assertThat(tracker.knownKey(email, client)).isEqualTo(tracker.prefix() + "known:" + email + ":" + client);
        assertThat(tracker.digest("other@example.com")).isNotEqualTo(email);
        // Keyed: another secret gives other digests and another key id; the same secret gives the same.
        ThrottledLoginFailureTracker other = new ThrottledLoginFailureTracker(store, LoginThrottleTestKeys.NEW_ONLY);
        assertThat(other.digest(EMAIL)).isNotEqualTo(email);
        assertThat(other.prefix()).isNotEqualTo(tracker.prefix());
        assertThat(new ThrottledLoginFailureTracker(store, LoginThrottleKeys.of(LoginThrottleTestKeys.SECRET_A, null)).digest(EMAIL))
                .isEqualTo(email);
        assertThat(email).isNotEqualTo(unkeyedSha256(EMAIL));
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
    void aKnownClientStaysKnownForTheRetentionWindowAfterItsLastSuccess() {
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

        String email = tracker.digest(EMAIL);
        assertThat(store.counter(tracker.pairFailuresKey(email, tracker.digest(HOME))))
                .isZero();
        assertThat(store.counter(tracker.accountFailuresKey(email))).isEqualTo(21);
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

        String email = tracker.digest(EMAIL);
        assertThat(store.keys()).noneMatch(key -> key.startsWith(tracker.prefix() + "pair:"));
        assertThat(store.counter(tracker.accountFailuresKey(email))).isEqualTo(230);
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
        assertThat(store.keys()).noneMatch(key -> key.startsWith(tracker.prefix() + "pair:"));
    }

    @Test
    void forgetAccountRemovesEverythingOfTheAccountOnly() {
        tracker.clearAfterSuccess(EMAIL, HOME);
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        tracker.recordFailure("neighbour@example.com", ATTACKER, now());

        tracker.forgetAccount(EMAIL);

        String email = tracker.digest(EMAIL);
        assertThat(store.keys()).noneMatch(key -> key.contains(email));
        assertThat(store.keys()).anyMatch(key -> key.contains(tracker.digest("neighbour@example.com")));
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

    /** Only a successful authentication writes a known marker: admission and failures never do. */
    @Test
    void failuresAndAdmissionsNeverMakeAClientKnown() {
        for (int i = 0; i < 25; i++) {
            tracker.admit(EMAIL, HOME, now());
            tracker.recordFailure(EMAIL, HOME, now());
            advance(Duration.ofHours(2));
        }
        tracker.retryAfter(EMAIL, HOME, now());
        assertThat(store.keys()).noneMatch(key -> key.contains(":known:"));
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        assertThat(tracker.retryAfter(EMAIL, HOME, now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
    }

    /** A successful refresh-token rotation keeps the client known without a password login. */
    @Test
    void aTokenRefreshKeepsTheClientKnownForAnotherRetentionWindow() {
        tracker.clearAfterSuccess(EMAIL, HOME);
        advance(LoginThrottlePolicy.KNOWN_CLIENT_TTL.minusDays(1));
        tracker.refreshKnownClient(EMAIL, HOME);
        advance(LoginThrottlePolicy.KNOWN_CLIENT_TTL.minusMinutes(1));
        failFromDistinctClients(LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        assertThat(tracker.retryAfter(EMAIL, HOME, now())).isZero();
        advance(Duration.ofMinutes(2));
        failFromDistinctClients(1);
        assertThat(tracker.retryAfter(EMAIL, HOME, now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        // A refresh from a never-seen client makes it known too (the token proves a prior login).
        tracker.refreshKnownClient(EMAIL, "192.0.2.200");
        assertThat(tracker.retryAfter(EMAIL, "192.0.2.200", now())).isZero();
        // The shared unknown bucket is never known, and a refresh writes nothing else.
        tracker.refreshKnownClient(EMAIL, LoginFailureTracker.UNKNOWN_CLIENT);
        assertThat(store.keys()).filteredOn(key -> key.contains(":known:")).hasSize(1);
    }

    /**
     * Key rotation: during the overlap the entries written under the previous secret keep their effect
     * (waits honoured, known client recognised), new entries go under the current secret only, a reset and
     * an erasure clear both key ids, and after the overlap the old entries are not read at all.
     */
    @Test
    void rotationOverlapHonoursThePreviousSecretsEntriesAndWritesOnlyUnderTheCurrentOne() {
        ThrottledLoginFailureTracker old = new ThrottledLoginFailureTracker(store, LoginThrottleTestKeys.OLD);
        old.clearAfterSuccess(EMAIL, HOME);
        for (int i = 0; i < 10; i++) {
            old.recordFailure(EMAIL, ATTACKER, now());
        }
        for (int i = 0; i < LoginThrottlePolicy.ACCOUNT_FIRST_CAP; i++) {
            old.recordFailure(EMAIL, "203.0.113." + (i % 200), now());
        }
        ThrottledLoginFailureTracker rotated = new ThrottledLoginFailureTracker(store, LoginThrottleTestKeys.ROTATED);
        assertThat(rotated.prefix()).isNotEqualTo(old.prefix());
        assertThat(rotated.admit(EMAIL, ATTACKER, now())).isEqualTo(Duration.ofMinutes(5));
        assertThat(rotated.retryAfter(EMAIL, ATTACKER, now())).isEqualTo(Duration.ofMinutes(5));
        assertThat(rotated.retryAfter(EMAIL, "192.0.2.1", now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        assertThat(rotated.admit(EMAIL, "192.0.2.1", now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        assertThat(rotated.retryAfter(EMAIL, HOME, now())).isZero();
        assertThat(rotated.admit(EMAIL, HOME, now())).isZero();
        // The old wait was only checked, never renewed: once it lapses nothing under the old key id runs.
        advance(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        assertThat(rotated.retryAfter(EMAIL, "192.0.2.1", now())).isZero();
        // New failures count under the current key id only.
        rotated.recordFailure(EMAIL, "198.18.0.1", now());
        assertThat(store.keys()).filteredOn(key -> key.startsWith(rotated.prefix() + "pair:")).hasSize(1);
        assertThat(store.counter(rotated.accountFailuresKey(rotated.digest(EMAIL)))).isEqualTo(1);
        assertThat(store.counter(old.accountFailuresKey(old.digest(EMAIL)))).isEqualTo(60);
        // A success and a reset clear both key ids; the resetting client is known under the current one.
        rotated.clearAfterSuccess(EMAIL, ATTACKER);
        assertThat(store.keys()).noneMatch(key -> key.contains(":" + old.digest(ATTACKER) + ":"))
                .noneMatch(key -> key.contains(":" + rotated.digest(ATTACKER) + ":failures"));
        rotated.clearAfterPasswordReset(EMAIL, "192.0.2.77");
        assertThat(store.keys()).noneMatch(key -> key.contains(":pair:"));
        assertThat(store.keys()).anyMatch(key -> key.equals(rotated.knownKey(rotated.digest(EMAIL), rotated.digest("192.0.2.77"))));
        // Erasure removes the account under both key ids and leaves other accounts alone.
        old.recordFailure("neighbour@example.com", ATTACKER, now());
        rotated.forgetAccount(EMAIL);
        assertThat(store.keys()).noneMatch(key -> key.contains(old.digest(EMAIL)) || key.contains(rotated.digest(EMAIL)));
        assertThat(store.keys()).anyMatch(key -> key.contains(old.digest("neighbour@example.com")));
        // After the overlap the previous secret is gone: its entries are neither read nor cleared.
        old.clearAfterSuccess(EMAIL, HOME);
        ThrottledLoginFailureTracker newOnly = new ThrottledLoginFailureTracker(store, LoginThrottleTestKeys.NEW_ONLY);
        failFromDistinctClientsWith(newOnly, LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
        assertThat(newOnly.retryAfter(EMAIL, HOME, now())).isEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
    }

    private static String unkeyedSha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void failFromDistinctClientsWith(ThrottledLoginFailureTracker target, long failures) {
        for (long i = 0; i < failures; i++) {
            target.recordFailure(EMAIL, "203.0.113." + (i % 200) + "", now());
        }
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
