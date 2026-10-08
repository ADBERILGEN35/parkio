package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Measured behaviour of the login throttle (owner decision 2026-10-08 item 6): attack throughput,
 * lockout and recovery of the account owner, IPv6 rotation and shared NAT. Each scenario runs against
 * policy v2 ({@link ThrottledLoginFailureTracker}, {@link LoginClientKeys}) and against a replica of the
 * v1 rules merged in #306 ({@link V1Replica}), on a virtual clock. The attacker is optimal: it knows the
 * policy, times its attempts to the opening of every wait and, in the in-flight scenarios, sends several
 * attempts at that instant from different addresses, all admitted or refused before any of their failures
 * is recorded (the race between the check and the failure; v1 checked without claiming, v2 claims the waits
 * atomically). The login control flow is that of {@code AuthApplicationService.login}: admit, then record
 * the failure or the success. The table is printed and written to {@code build/login-throttle-simulation.md};
 * the assertions pin the v2 bounds.
 */
class LoginThrottleSimulationTest {

    private static final Instant START = Instant.parse("2026-10-08T00:00:00Z");
    private static final Duration DAY = Duration.ofHours(24);
    private static final String OWNER = "owner@example.com";
    private static final String HOME = "198.51.100.7";
    private static final String NEW_DEVICE = "2001:db8:99:1::42";
    private static final String MOBILE = "192.0.2.150";
    private static final String NAT = "203.0.113.7";
    private static final Map<String, String[]> REPORT = new TreeMap<>();

    enum Kind { RIGHT_PASSWORD, WRONG_PASSWORD, RESET }

    enum Result { SUCCESS, FAILED, REFUSED }

    record Step(Duration at, String email, String ip, Kind kind) {
    }

    /** A throttle under test with its clock and its client-key rule. */
    static final class Sim {
        final AtomicReference<Instant> now = new AtomicReference<>(START);
        final LoginFailureTracker tracker;
        final UnaryOperator<String> clientKey;

        Sim(boolean v2) {
            InMemoryLoginThrottleStore store = new InMemoryLoginThrottleStore(now::get);
            tracker = v2 ? new ThrottledLoginFailureTracker(store) : new V1Replica(store);
            clientKey = v2 ? LoginClientKeys::fromIpLiteral : ip -> ip.trim().toLowerCase(Locale.ROOT);
        }

        Instant now() {
            return now.get();
        }

        /** The control flow of {@code AuthApplicationService.login}. */
        Result login(String email, String ip, boolean rightPassword) {
            if (!admit(email, ip)) {
                return Result.REFUSED;
            }
            if (rightPassword) {
                tracker.clearAfterSuccess(email, clientKey.apply(ip));
                return Result.SUCCESS;
            }
            fail(email, ip);
            return Result.FAILED;
        }

        boolean admit(String email, String ip) {
            return tracker.admit(email, clientKey.apply(ip), now()).isZero();
        }

        void fail(String email, String ip) {
            tracker.recordFailure(email, clientKey.apply(ip), now());
        }

        void reset(String email, String ip) {
            tracker.clearAfterPasswordReset(email, ip == null ? null : clientKey.apply(ip));
        }
    }

    static final class Outcome {
        final List<Duration> guesses = new ArrayList<>();
        int legitAttempts;
        int legitRefused;
        int legitSucceeded;
        Duration firstSuccessAfterReset;
        Result firstResultAfterReset;

        long guessesIn(Duration from, Duration to) {
            return guesses.stream().filter(at -> at.compareTo(from) >= 0 && at.compareTo(to) < 0).count();
        }

        long hour(int index) {
            return guessesIn(Duration.ofHours(index - 1L), Duration.ofHours(index));
        }
    }

    /**
     * Runs an optimal attacker against {@code victim} for {@code span} (address {@code i} of a pool of
     * {@code pool}, used round-robin) interleaved with the scheduled legitimate {@code steps}.
     */
    static Outcome run(Sim sim, String victim, IntFunction<String> attacker, int pool, Duration span, List<Step> steps) {
        return run(sim, victim, attacker, pool, 1, span, steps);
    }

    /** As above, with {@code inFlight} attempts sent at every opening from consecutive addresses of the pool. */
    static Outcome run(Sim sim, String victim, IntFunction<String> attacker, int pool, int inFlight, Duration span,
            List<Step> steps) {
        Outcome out = new Outcome();
        Instant end = START.plus(span);
        PriorityQueue<Step> queue = new PriorityQueue<>(Comparator.comparing(Step::at));
        queue.addAll(steps);
        int next = 0;
        Instant resetAt = null;
        while (true) {
            int chosen = -1;
            Duration wait = null;
            if (pool > 0) {
                for (int j = 0; j < Math.min(pool, 64); j++) {
                    int index = (next + j) % pool;
                    Duration candidate = sim.tracker.retryAfter(victim, sim.clientKey.apply(attacker.apply(index)), sim.now());
                    if (wait == null || candidate.compareTo(wait) < 0) {
                        wait = candidate;
                        chosen = index;
                    }
                    if (candidate.isZero()) {
                        break;
                    }
                }
            }
            Instant attackAt = pool > 0 ? sim.now().plus(wait) : end;
            Step step = queue.peek();
            Instant stepAt = step == null ? null : START.plus(step.at());
            if (stepAt != null && stepAt.isBefore(end) && !stepAt.isAfter(attackAt)) {
                queue.poll();
                if (stepAt.isAfter(sim.now())) {
                    sim.now.set(stepAt);
                }
                if (step.kind() == Kind.RESET) {
                    sim.reset(step.email(), step.ip());
                    resetAt = sim.now();
                    continue;
                }
                out.legitAttempts++;
                Result result = sim.login(step.email(), step.ip(), step.kind() == Kind.RIGHT_PASSWORD);
                if (resetAt != null && out.firstResultAfterReset == null) {
                    out.firstResultAfterReset = result;
                }
                if (result == Result.REFUSED) {
                    out.legitRefused++;
                } else if (result == Result.SUCCESS) {
                    out.legitSucceeded++;
                    if (resetAt != null && out.firstSuccessAfterReset == null) {
                        out.firstSuccessAfterReset = Duration.between(resetAt, sim.now());
                    }
                }
                continue;
            }
            if (!attackAt.isBefore(end)) {
                return out;
            }
            sim.now.set(attackAt);
            // All in-flight attempts pass admission (or not) before any of their failures is recorded.
            List<String> admitted = new ArrayList<>();
            for (int j = 0; j < Math.min(inFlight, pool); j++) {
                String ip = attacker.apply((chosen + j) % pool);
                if (sim.admit(victim, ip)) {
                    admitted.add(ip);
                }
            }
            for (String ip : admitted) {
                sim.fail(victim, ip);
                out.guesses.add(Duration.between(START, sim.now()));
            }
            next = (chosen + Math.min(inFlight, pool)) % pool;
        }
    }

    /** {@code count} steps every {@code every}, starting at {@code first}. */
    static List<Step> every(Duration first, Duration every, int count, String email, String ip, Kind kind) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            steps.add(new Step(first.plus(every.multipliedBy(i)), email, ip, kind));
        }
        return steps;
    }

    static String ipv4Pool(int i) {
        return "100.64." + (i / 256) + "." + (i % 256);
    }

    /** A new address inside one /64 for every attempt. */
    static String ipv6InOneSlash64(int i) {
        return String.format("2001:db8:abcd:1:%x:%x:%x:%x", (i >>> 24) & 0xff, (i >>> 16) & 0xff, (i >>> 8) & 0xff, i & 0xff);
    }

    /** A new /64 inside one /48 for every attempt. */
    static String ipv6AcrossOneSlash48(int i) {
        return String.format("2001:db8:abcd:%x::1", i & 0xffff);
    }

    private static final Duration OWNER_FIRST = Duration.ofMinutes(7).plusSeconds(13).plusMillis(300);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    void a_distributedAttackFromAThousandAddresses_ownerOnAKnownNetwork() {
        Outcome[] outcomes = new Outcome[2];
        for (int v = 0; v < 2; v++) {
            Sim sim = new Sim(v == 1);
            assertThat(sim.login(OWNER, HOME, true)).isEqualTo(Result.SUCCESS);
            outcomes[v] = run(sim, OWNER, LoginThrottleSimulationTest::ipv4Pool, 1000, DAY,
                    every(OWNER_FIRST, TEN_MINUTES, 144, OWNER, HOME, Kind.RIGHT_PASSWORD));
        }
        Outcome v1 = outcomes[0];
        Outcome v2 = outcomes[1];
        row("A1", "Distributed attack, 1,000 IPv4 addresses: guesses in hour 1", v1.hour(1), v2.hour(1));
        row("A2", "same: guesses in hour 2", v1.hour(2), v2.hour(2));
        row("A3", "same: guesses per hour, hours 3-24 (max)", maxHour(v1, 3, 24), maxHour(v2, 3, 24));
        row("A4", "same: guesses in 24 h", v1.guesses.size(), v2.guesses.size());
        row("A5", "same: owner on a network used before, 144 logins with the right password: refused",
                v1.legitRefused + "/" + v1.legitAttempts, v2.legitRefused + "/" + v2.legitAttempts);

        assertThat(v2.hour(1)).isLessThanOrEqualTo(160);
        assertThat(maxHour(v2, 3, 24)).isLessThanOrEqualTo(12);
        assertThat(v2.guesses.size()).isLessThanOrEqualTo(480);
        assertThat(v2.legitRefused).isZero();
        assertThat(v2.legitSucceeded).isEqualTo(144);
        // The v1 replica reproduces the measured v1 lockout (#306 review: 0 of 360 owner attempts admitted).
        assertThat(v1.guesses.size()).isGreaterThan(8000);
        assertThat(v1.legitRefused).isGreaterThanOrEqualTo(140);
    }

    /** #311 review B1: attempts in flight at every opening, from different addresses. */
    @Test
    void a2_distributedAttackWithAttemptsInFlight() {
        Outcome[][] outcomes = new Outcome[2][2];
        int[] inFlight = {8, 32};
        for (int v = 0; v < 2; v++) {
            for (int k = 0; k < inFlight.length; k++) {
                outcomes[v][k] = run(new Sim(v == 1), OWNER, LoginThrottleSimulationTest::ipv4Pool, 1000, inFlight[k], DAY, List.of());
            }
        }
        row("A6", "Distributed attack, 1,000 addresses, 8 attempts in flight at every opening: guesses in 24 h",
                outcomes[0][0].guesses.size(), outcomes[1][0].guesses.size());
        row("A7", "same with 32 attempts in flight: guesses in 24 h", outcomes[0][1].guesses.size(), outcomes[1][1].guesses.size());
        row("A8", "same with 32 in flight: guesses per hour, hours 3-24 (max)", maxHour(outcomes[0][1], 3, 24),
                maxHour(outcomes[1][1], 3, 24));
        // v2 claims each wait atomically: concurrency adds at most the attempts in flight at the first tier crossing.
        assertThat(outcomes[1][0].guesses.size()).isLessThanOrEqualTo(480 + 8);
        assertThat(outcomes[1][1].guesses.size()).isLessThanOrEqualTo(480 + 32);
        assertThat(maxHour(outcomes[1][1], 3, 24)).isLessThanOrEqualTo(12);
        // The v1 replica checks without claiming: attempts in flight multiply its throughput (the race of #306
        // review N5), until each address's own pair tiers bind.
        assertThat(outcomes[0][0].guesses.size()).isGreaterThan(4 * 8000);
    }

    @Test
    void b_distributedAttack_ownerOnANewDeviceRecoversByPasswordReset() {
        Outcome[] outcomes = new Outcome[2];
        for (int v = 0; v < 2; v++) {
            Sim sim = new Sim(v == 1);
            List<Step> steps = new ArrayList<>(every(OWNER_FIRST, TEN_MINUTES, 72, OWNER, NEW_DEVICE, Kind.RIGHT_PASSWORD));
            steps.add(new Step(Duration.ofHours(12), OWNER, NEW_DEVICE, Kind.RESET));
            steps.addAll(every(Duration.ofHours(12).plusSeconds(31).plusMillis(700), TEN_MINUTES, 72, OWNER, NEW_DEVICE,
                    Kind.RIGHT_PASSWORD));
            outcomes[v] = run(sim, OWNER, LoginThrottleSimulationTest::ipv4Pool, 1000, DAY, steps);
        }
        Outcome v1 = outcomes[0];
        Outcome v2 = outcomes[1];
        row("B1", "Same attack, owner on a new device: logins refused (144 tries, password reset from the device at 12 h)",
                v1.legitRefused + "/" + v1.legitAttempts, v2.legitRefused + "/" + v2.legitAttempts);
        row("B2", "same: the owner's first login after the reset (next scheduled try, 31.7 s later)",
                describe(v1.firstResultAfterReset), describe(v2.firstResultAfterReset));
        row("B3", "same: attacker guesses in 24 h", v1.guesses.size(), v2.guesses.size());

        assertThat(v2.legitRefused).isEqualTo(72);
        assertThat(v2.legitSucceeded).isEqualTo(72);
        assertThat(v2.firstResultAfterReset).isEqualTo(Result.SUCCESS);
        assertThat(v1.firstResultAfterReset).isEqualTo(Result.REFUSED);
    }

    @Test
    void c_cheapDistributedAttackFromTwelveAddresses() {
        Outcome[] outcomes = new Outcome[2];
        for (int v = 0; v < 2; v++) {
            Sim sim = new Sim(v == 1);
            outcomes[v] = run(sim, OWNER, LoginThrottleSimulationTest::ipv4Pool, 12, DAY, List.of());
        }
        row("C1", "Distributed attack, 12 IPv4 addresses: guesses in 24 h", outcomes[0].guesses.size(), outcomes[1].guesses.size());
        row("C2", "same: guesses per hour, hours 3-24 (max)", maxHour(outcomes[0], 3, 24), maxHour(outcomes[1], 3, 24));
        assertThat(outcomes[1].guesses.size()).isLessThanOrEqualTo(480);
    }

    @Test
    void d_bruteForceFromOneAddress() {
        Outcome[] outcomes = new Outcome[2];
        for (int v = 0; v < 2; v++) {
            Sim sim = new Sim(v == 1);
            outcomes[v] = run(sim, OWNER, i -> "100.64.0.1", 1, DAY, List.of());
        }
        row("D1", "One IPv4 address: guesses in hour 1", outcomes[0].hour(1), outcomes[1].hour(1));
        row("D2", "same: guesses in 24 h", outcomes[0].guesses.size(), outcomes[1].guesses.size());
        assertThat(outcomes[1].hour(1)).isLessThanOrEqualTo(20);
        assertThat(outcomes[1].guesses.size()).isLessThanOrEqualTo(45);
    }

    @Test
    void e_ipv6RotationInsideOneSlash64() {
        Outcome[] outcomes = new Outcome[2];
        for (int v = 0; v < 2; v++) {
            Sim sim = new Sim(v == 1);
            assertThat(sim.login(OWNER, HOME, true)).isEqualTo(Result.SUCCESS);
            outcomes[v] = run(sim, OWNER, LoginThrottleSimulationTest::ipv6InOneSlash64, 1 << 24, DAY,
                    every(OWNER_FIRST, TEN_MINUTES, 144, OWNER, HOME, Kind.RIGHT_PASSWORD));
        }
        Outcome v1 = outcomes[0];
        Outcome v2 = outcomes[1];
        row("E1", "IPv6 attacker with one /64, new address per attempt: guesses in 24 h", v1.guesses.size(), v2.guesses.size());
        row("E2", "same: owner on a network used before, 144 logins: refused",
                v1.legitRefused + "/" + v1.legitAttempts, v2.legitRefused + "/" + v2.legitAttempts);
        assertThat(v2.guesses.size()).isLessThanOrEqualTo(45);
        assertThat(v2.legitRefused).isZero();
    }

    @Test
    void f_ipv6RotationAcrossOneSlash48() {
        Outcome[] outcomes = new Outcome[2];
        for (int v = 0; v < 2; v++) {
            Sim sim = new Sim(v == 1);
            outcomes[v] = run(sim, OWNER, LoginThrottleSimulationTest::ipv6AcrossOneSlash48, 1 << 16, DAY, List.of());
        }
        row("F1", "IPv6 attacker with one /48, new /64 per attempt: guesses in 24 h", outcomes[0].guesses.size(),
                outcomes[1].guesses.size());
        assertThat(outcomes[1].guesses.size()).isLessThanOrEqualTo(480);
    }

    @Test
    void g_sharedNatOtherAccountsAreUnaffected() {
        Outcome[] outcomes = new Outcome[2];
        for (int v = 0; v < 2; v++) {
            Sim sim = new Sim(v == 1);
            List<Step> steps = new ArrayList<>();
            for (int user = 0; user < 200; user++) {
                String email = "neighbour" + user + "@example.com";
                Duration at = Duration.ofSeconds(37L + user * 409L);
                steps.add(new Step(at, email, NAT, Kind.WRONG_PASSWORD));
                steps.add(new Step(at.plusSeconds(20), email, NAT, Kind.WRONG_PASSWORD));
                steps.add(new Step(at.plusSeconds(45), email, NAT, Kind.RIGHT_PASSWORD));
            }
            outcomes[v] = run(sim, OWNER, i -> NAT, 1, DAY, steps);
        }
        row("G1", "Shared NAT: attacker brute-forces one account; 200 other accounts behind the same address log in "
                + "(2 typos + right password): refused", outcomes[0].legitRefused + "/" + outcomes[0].legitAttempts,
                outcomes[1].legitRefused + "/" + outcomes[1].legitAttempts);
        assertThat(outcomes[1].legitRefused).isZero();
        assertThat(outcomes[1].legitSucceeded).isEqualTo(200);
    }

    @Test
    void h_sharedNatAttackerOnTheOwnersOwnAddress() {
        Outcome[] fromNat = new Outcome[2];
        Outcome[] fromMobile = new Outcome[2];
        for (int v = 0; v < 2; v++) {
            Sim sim = new Sim(v == 1);
            assertThat(sim.login(OWNER, NAT, true)).isEqualTo(Result.SUCCESS);
            List<Step> steps = new ArrayList<>(every(OWNER_FIRST, Duration.ofMinutes(30), 24, OWNER, NAT, Kind.RIGHT_PASSWORD));
            steps.add(new Step(Duration.ofHours(12), OWNER, NAT, Kind.RESET));
            steps.add(new Step(Duration.ofHours(12).plusSeconds(45), OWNER, NAT, Kind.RIGHT_PASSWORD));
            fromNat[v] = run(sim, OWNER, i -> NAT, 1, DAY, steps);

            Sim mobile = new Sim(v == 1);
            assertThat(mobile.login(OWNER, NAT, true)).isEqualTo(Result.SUCCESS);
            fromMobile[v] = run(mobile, OWNER, i -> NAT, 1, DAY,
                    every(OWNER_FIRST, Duration.ofMinutes(30), 48, OWNER, MOBILE, Kind.RIGHT_PASSWORD));
        }
        row("H1", "Attacker behind the owner's own NAT address: owner logins from that address refused "
                + "(25 tries, reset at 12 h)", fromNat[0].legitRefused + "/" + fromNat[0].legitAttempts,
                fromNat[1].legitRefused + "/" + fromNat[1].legitAttempts);
        row("H2", "same attacker: owner logins from another network (mobile) refused",
                fromMobile[0].legitRefused + "/" + fromMobile[0].legitAttempts,
                fromMobile[1].legitRefused + "/" + fromMobile[1].legitAttempts);
        row("H3", "same attacker: guesses in 24 h", fromNat[0].guesses.size(), fromNat[1].guesses.size());
        assertThat(fromMobile[1].legitRefused).isZero();
        assertThat(fromNat[1].guesses.size()).isLessThanOrEqualTo(70);
    }

    @Test
    void i_forgottenPasswordWithoutAnAttack() {
        Result[] laptop = new Result[2];
        for (int v = 0; v < 2; v++) {
            Sim sim = new Sim(v == 1);
            List<Step> steps = new ArrayList<>(every(Duration.ofSeconds(1), Duration.ofSeconds(40), 25, OWNER, "192.0.2.77",
                    Kind.WRONG_PASSWORD));
            steps.add(new Step(Duration.ofMinutes(20), OWNER, MOBILE, Kind.RESET));
            steps.add(new Step(Duration.ofMinutes(20).plusSeconds(30), OWNER, "192.0.2.77", Kind.RIGHT_PASSWORD));
            Outcome out = run(sim, OWNER, i -> "unused", 0, DAY, steps);
            laptop[v] = out.firstResultAfterReset;
        }
        row("I1", "No attack: owner mistypes 25 times on a laptop, resets from the phone: the laptop's next login, 30 s after the reset",
                describe(laptop[0]), describe(laptop[1]));
        assertThat(laptop[1]).isEqualTo(Result.SUCCESS);
    }

    @AfterAll
    static void writeReport() throws IOException {
        StringBuilder table = new StringBuilder()
                .append("| Id | Scenario (24 h, optimal attacker) | v1 (#306) | v2 (proposed) |\n")
                .append("|---|---|---|---|\n");
        REPORT.forEach((id, cells) -> table.append("| ").append(id).append(" | ").append(cells[0]).append(" | ")
                .append(cells[1]).append(" | ").append(cells[2]).append(" |\n"));
        System.out.println(table);
        Path out = Path.of("build", "login-throttle-simulation.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table.toString(), StandardCharsets.UTF_8);
    }

    private static long maxHour(Outcome outcome, int from, int to) {
        long max = 0;
        for (int hour = from; hour <= to; hour++) {
            max = Math.max(max, outcome.hour(hour));
        }
        return max;
    }

    private static String describe(Result result) {
        return result == null ? "no attempt" : switch (result) {
            case SUCCESS -> "succeeded";
            case REFUSED -> "refused";
            case FAILED -> "evaluated, wrong password";
        };
    }

    private static void row(String id, String scenario, Object v1, Object v2) {
        REPORT.put(id, new String[] {scenario, String.valueOf(v1), String.valueOf(v2)});
    }

    /** The CL-F15 v1 rules as merged in #306 (api 05f0893e), kept for comparison only. */
    static final class V1Replica implements LoginFailureTracker {

        private final InMemoryLoginThrottleStore store;

        V1Replica(InMemoryLoginThrottleStore store) {
            this.store = store;
        }

        /** v1 checked the waits without claiming them. */
        @Override
        public Duration admit(String email, String client, Instant now) {
            return retryAfter(email, client, now);
        }

        @Override
        public Duration retryAfter(String email, String client, Instant now) {
            return LoginThrottlePolicy.max(store.remaining("pw:" + email + ":" + client), store.remaining("aw:" + email));
        }

        @Override
        public LoginFailureOutcome recordFailure(String email, String client, Instant now) {
            long pair = store.increment("pf:" + email + ":" + client, Duration.ofHours(24));
            store.addToSet("cl:" + email, client, Duration.ofHours(24));
            long account = store.increment("af:" + email, Duration.ofHours(1));
            Duration pairDelay = LoginThrottlePolicy.pairDelay(pair);
            if (!pairDelay.isZero()) {
                store.hold("pw:" + email + ":" + client, pairDelay);
            }
            Duration accountDelay = account >= 50 ? Duration.ofSeconds(10) : Duration.ZERO;
            if (!accountDelay.isZero()) {
                store.hold("aw:" + email, accountDelay);
            }
            Duration delay = LoginThrottlePolicy.max(pairDelay, accountDelay);
            return new LoginFailureOutcome(pair, account, delay, delay.isZero() ? null : now.plus(delay));
        }

        @Override
        public void clearAfterSuccess(String email, String client) {
            store.delete(List.of("pf:" + email + ":" + client, "pw:" + email + ":" + client, "af:" + email, "aw:" + email));
        }

        @Override
        public void clearAfterPasswordReset(String email, String ignoredClient) {
            List<String> keys = new ArrayList<>();
            for (String client : store.setMembers("cl:" + email)) {
                keys.add("pf:" + email + ":" + client);
                keys.add("pw:" + email + ":" + client);
            }
            keys.addAll(List.of("cl:" + email, "af:" + email, "aw:" + email));
            store.delete(keys);
        }

        @Override
        public void forgetAccount(String email) {
            clearAfterPasswordReset(email, null);
        }
    }
}
