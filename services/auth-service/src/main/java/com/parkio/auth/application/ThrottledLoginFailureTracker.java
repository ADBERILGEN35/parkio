package com.parkio.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * {@link LoginThrottlePolicy} on a {@link LoginThrottleStore}. Keys carry SHA-256 digests of the
 * normalized e-mail and of the client key, never the raw values, and no password or token material.
 *
 * <pre>
 * auth:login:v2:pair:{email}:{client}:failures   counter, PAIR_WINDOW
 * auth:login:v2:pair:{email}:{client}:wait       marker, the pair delay
 * auth:login:v2:account:{email}:failures         counter, ACCOUNT_WINDOW
 * auth:login:v2:account:{email}:wait             marker, the account delay (unknown clients only)
 * auth:login:v2:account:{email}:clients          plain set of client digests that failed, PAIR_WINDOW
 * auth:login:v2:account:{email}:known            timed set of known client digests, KNOWN_CLIENT_TTL
 * </pre>
 *
 * The first five keys keep the names and types of the CL-F15 v1 tracker, so state written before an
 * upgrade (or after a rollback) stays readable; {@code known} is new and ignored by v1.
 */
@Component
public class ThrottledLoginFailureTracker implements LoginFailureTracker {

    static final String PREFIX = "auth:login:v2:";

    private final LoginThrottleStore store;

    public ThrottledLoginFailureTracker(LoginThrottleStore store) {
        this.store = store;
    }

    @Override
    public Duration retryAfter(String normalizedEmail, String clientKey, Instant now) {
        String email = digest(normalizedEmail);
        String client = digest(clientKey);
        Duration pairWait = store.remaining(pairWaitKey(email, client));
        if (isKnown(email, clientKey, client)) {
            return pairWait;
        }
        return LoginThrottlePolicy.max(pairWait, store.remaining(accountWaitKey(email)));
    }

    @Override
    public LoginFailureOutcome recordFailure(String normalizedEmail, String clientKey, Instant now) {
        String email = digest(normalizedEmail);
        String client = digest(clientKey);
        long pairFailures = store.increment(pairFailuresKey(email, client), LoginThrottlePolicy.PAIR_WINDOW);
        store.addToSet(clientsKey(email), client, LoginThrottlePolicy.PAIR_WINDOW);
        long accountFailures = store.increment(accountFailuresKey(email), LoginThrottlePolicy.ACCOUNT_WINDOW);

        Duration pairDelay = LoginThrottlePolicy.pairDelay(pairFailures);
        if (!pairDelay.isZero()) {
            store.hold(pairWaitKey(email, client), pairDelay);
        }
        Duration accountDelay = LoginThrottlePolicy.accountDelay(accountFailures);
        if (!accountDelay.isZero()) {
            store.hold(accountWaitKey(email), accountDelay);
        }
        Duration delay = isKnown(email, clientKey, client)
                ? pairDelay
                : LoginThrottlePolicy.max(pairDelay, accountDelay);
        return new LoginFailureOutcome(pairFailures, accountFailures, delay, delay.isZero() ? null : now.plus(delay));
    }

    @Override
    public void clearAfterSuccess(String normalizedEmail, String clientKey) {
        String email = digest(normalizedEmail);
        String client = digest(clientKey);
        store.delete(List.of(pairFailuresKey(email, client), pairWaitKey(email, client)));
        markKnown(email, clientKey, client);
        // The account counter stays: one successful login does not end an attack on the account, and
        // known clients are exempt from its delay anyway.
    }

    @Override
    public void clearAfterPasswordReset(String normalizedEmail, String resettingClientKey) {
        String email = digest(normalizedEmail);
        Set<String> clients = new LinkedHashSet<>(store.setMembers(clientsKey(email)));
        if (resettingClientKey != null && !resettingClientKey.isBlank()) {
            clients.add(digest(resettingClientKey));
        }
        List<String> keys = new ArrayList<>();
        for (String client : clients) {
            keys.add(pairFailuresKey(email, client));
            keys.add(pairWaitKey(email, client));
        }
        keys.add(clientsKey(email));
        store.delete(keys);
        if (resettingClientKey != null && !resettingClientKey.isBlank()) {
            markKnown(email, resettingClientKey, digest(resettingClientKey));
        }
    }

    @Override
    public void forgetAccount(String normalizedEmail) {
        String email = digest(normalizedEmail);
        List<String> keys = new ArrayList<>();
        for (String client : store.setMembers(clientsKey(email))) {
            keys.add(pairFailuresKey(email, client));
            keys.add(pairWaitKey(email, client));
        }
        keys.addAll(List.of(clientsKey(email), accountFailuresKey(email), accountWaitKey(email), knownKey(email)));
        store.delete(keys);
    }

    private boolean isKnown(String email, String clientKey, String client) {
        return isIdentified(clientKey) && store.isTimedMember(knownKey(email), client);
    }

    private void markKnown(String email, String clientKey, String client) {
        if (isIdentified(clientKey)) {
            store.markTimed(knownKey(email), client, LoginThrottlePolicy.KNOWN_CLIENT_TTL);
        }
    }

    private static boolean isIdentified(String clientKey) {
        return clientKey != null && !clientKey.isBlank() && !UNKNOWN_CLIENT.equals(clientKey);
    }

    static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String pairFailuresKey(String email, String client) {
        return PREFIX + "pair:" + email + ":" + client + ":failures";
    }

    static String pairWaitKey(String email, String client) {
        return PREFIX + "pair:" + email + ":" + client + ":wait";
    }

    static String accountFailuresKey(String email) {
        return PREFIX + "account:" + email + ":failures";
    }

    static String accountWaitKey(String email) {
        return PREFIX + "account:" + email + ":wait";
    }

    static String clientsKey(String email) {
        return PREFIX + "account:" + email + ":clients";
    }

    static String knownKey(String email) {
        return PREFIX + "account:" + email + ":known";
    }
}
