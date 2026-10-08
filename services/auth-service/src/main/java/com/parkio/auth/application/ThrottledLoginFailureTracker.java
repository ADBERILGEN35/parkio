package com.parkio.auth.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link LoginThrottlePolicy} on a {@link LoginThrottleStore}. Keys carry keyed digests
 * ({@link LoginThrottleKeys}: HMAC-SHA256 under a separately managed secret) of the normalized e-mail
 * and of the client key, never the raw values, and no password or token material. Every key is prefixed
 * with the id of the secret that produced its digests:
 *
 * <pre>
 * auth:login:v3:{kid}:pair:{email}:{client}:failures   counter, PAIR_WINDOW
 * auth:login:v3:{kid}:pair:{email}:{client}:wait       marker, the pair delay
 * auth:login:v3:{kid}:account:{email}:failures         counter, ACCOUNT_WINDOW
 * auth:login:v3:{kid}:account:{email}:wait             marker, the account delay (unknown clients only)
 * auth:login:v3:{kid}:account:{email}:clients          plain set of client digests that failed, PAIR_WINDOW
 * auth:login:v3:{kid}:known:{email}:{client}           marker, KNOWN_CLIENT_TTL after the client's last
 *                                                      login, completed reset or token refresh
 * </pre>
 *
 * <p>During a key rotation the entries written under the previous secret stay in force: admission checks
 * the previous key id's waits as well (without renewing them), a client known under the previous key id
 * is known, and a reset or an erasure clears both key ids. New entries are written under the current key
 * id only, so the previous one empties by expiry within {@link LoginThrottlePolicy#HMAC_KEY_OVERLAP}.
 *
 * <p>Admission ({@link #admit}) claims the running waits atomically before the password is checked; the
 * failure that follows sets the next wait from its own time. Below the first tier no wait applies, so
 * attempts that arrive together there are all evaluated: the overshoot at a tier crossing is bounded by
 * the number of attempts in flight.
 *
 * <p>A client becomes or stays known only after a successful authentication: a password login, a
 * completed password reset (possession of the mailbox) or a successful refresh-token rotation. Admission
 * and failures never write a known marker.
 */
@Component
public class ThrottledLoginFailureTracker implements LoginFailureTracker {

    static final String NAMESPACE = "auth:login:v3:";

    private final LoginThrottleStore store;
    private final LoginThrottleKeys keys;

    public ThrottledLoginFailureTracker(LoginThrottleStore store, LoginThrottleKeys keys) {
        this.store = store;
        this.keys = keys;
    }

    @Override
    public Duration admit(String normalizedEmail, String clientKey, Instant now) {
        Scope current = current(normalizedEmail, clientKey);
        Scope previous = previous(normalizedEmail, clientKey);
        Map<String, Duration> waits = new LinkedHashMap<>();
        waits.put(current.pairWait(), LoginThrottlePolicy.pairDelay(store.read(current.pairFailures())));
        if (previous != null) {
            waits.put(previous.pairWait(), Duration.ZERO); // checked, never renewed
        }
        if (!isKnown(clientKey, current, previous)) {
            waits.put(current.accountWait(), LoginThrottlePolicy.accountDelay(store.read(current.accountFailures())));
            if (previous != null) {
                waits.put(previous.accountWait(), Duration.ZERO);
            }
        }
        return store.tryHold(waits);
    }

    @Override
    public Duration retryAfter(String normalizedEmail, String clientKey, Instant now) {
        Scope current = current(normalizedEmail, clientKey);
        Scope previous = previous(normalizedEmail, clientKey);
        Duration pairWait = store.remaining(current.pairWait());
        Duration accountWait = store.remaining(current.accountWait());
        if (previous != null) {
            pairWait = LoginThrottlePolicy.max(pairWait, store.remaining(previous.pairWait()));
            accountWait = LoginThrottlePolicy.max(accountWait, store.remaining(previous.accountWait()));
        }
        if (isKnown(clientKey, current, previous)) {
            return pairWait;
        }
        return LoginThrottlePolicy.max(pairWait, accountWait);
    }

    @Override
    public LoginFailureOutcome recordFailure(String normalizedEmail, String clientKey, Instant now) {
        Scope current = current(normalizedEmail, clientKey);
        long pairFailures = store.increment(current.pairFailures(), LoginThrottlePolicy.PAIR_WINDOW);
        store.addToSet(current.clients(), current.client, LoginThrottlePolicy.PAIR_WINDOW);
        long accountFailures = store.increment(current.accountFailures(), LoginThrottlePolicy.ACCOUNT_WINDOW);

        Duration pairDelay = LoginThrottlePolicy.pairDelay(pairFailures);
        if (!pairDelay.isZero()) {
            store.hold(current.pairWait(), pairDelay);
        }
        Duration accountDelay = LoginThrottlePolicy.accountDelay(accountFailures);
        if (!accountDelay.isZero()) {
            store.hold(current.accountWait(), accountDelay);
        }
        Duration delay = isKnown(clientKey, current, previous(normalizedEmail, clientKey))
                ? pairDelay
                : LoginThrottlePolicy.max(pairDelay, accountDelay);
        return new LoginFailureOutcome(pairFailures, accountFailures, delay, delay.isZero() ? null : now.plus(delay));
    }

    @Override
    public void clearAfterSuccess(String normalizedEmail, String clientKey) {
        Scope current = current(normalizedEmail, clientKey);
        Scope previous = previous(normalizedEmail, clientKey);
        List<String> keysToDelete = new ArrayList<>(List.of(current.pairFailures(), current.pairWait()));
        if (previous != null) {
            keysToDelete.addAll(List.of(previous.pairFailures(), previous.pairWait()));
        }
        store.delete(keysToDelete);
        markKnown(current, clientKey);
        // The account counter stays: one successful login does not end an attack on the account, and
        // known clients are exempt from its delay anyway.
    }

    @Override
    public void clearAfterPasswordReset(String normalizedEmail, String resettingClientKey) {
        boolean identified = isIdentified(resettingClientKey);
        List<String> keysToDelete = new ArrayList<>();
        for (Scope scope : scopes(normalizedEmail, identified ? resettingClientKey : null)) {
            for (String client : store.setMembers(scope.clients())) {
                keysToDelete.add(scope.pairFailures(client));
                keysToDelete.add(scope.pairWait(client));
            }
            if (identified) {
                keysToDelete.add(scope.pairFailures());
                keysToDelete.add(scope.pairWait());
            }
            keysToDelete.add(scope.clients());
        }
        store.delete(keysToDelete);
        if (identified) {
            markKnown(current(normalizedEmail, resettingClientKey), resettingClientKey);
        }
    }

    @Override
    public void refreshKnownClient(String normalizedEmail, String clientKey) {
        markKnown(current(normalizedEmail, clientKey), clientKey);
    }

    @Override
    public void forgetAccount(String normalizedEmail) {
        List<String> keysToDelete = new ArrayList<>();
        for (Scope scope : scopes(normalizedEmail, null)) {
            for (String client : store.setMembers(scope.clients())) {
                keysToDelete.add(scope.pairFailures(client));
                keysToDelete.add(scope.pairWait(client));
            }
            keysToDelete.addAll(List.of(scope.clients(), scope.accountFailures(), scope.accountWait()));
            keysToDelete.addAll(store.keysWithPrefix(scope.knownPrefix()));
        }
        store.delete(keysToDelete);
    }

    private boolean isKnown(String clientKey, Scope current, Scope previous) {
        if (!isIdentified(clientKey)) {
            return false;
        }
        if (!store.remaining(current.known()).isZero()) {
            return true;
        }
        return previous != null && !store.remaining(previous.known()).isZero();
    }

    private void markKnown(Scope current, String clientKey) {
        if (isIdentified(clientKey)) {
            store.hold(current.known(), LoginThrottlePolicy.KNOWN_CLIENT_TTL);
        }
    }

    private static boolean isIdentified(String clientKey) {
        return clientKey != null && !clientKey.isBlank() && !UNKNOWN_CLIENT.equals(clientKey);
    }

    private Scope current(String normalizedEmail, String clientKey) {
        return new Scope(keys.kid(), keys.digest(normalizedEmail), clientKey == null ? null : keys.digest(clientKey));
    }

    private Scope previous(String normalizedEmail, String clientKey) {
        if (!keys.hasPrevious()) {
            return null;
        }
        return new Scope(keys.previousKid(), keys.previousDigest(normalizedEmail),
                clientKey == null ? null : keys.previousDigest(clientKey));
    }

    private List<Scope> scopes(String normalizedEmail, String clientKey) {
        List<Scope> scopes = new ArrayList<>(2);
        scopes.add(current(normalizedEmail, clientKey));
        Scope previous = previous(normalizedEmail, clientKey);
        if (previous != null) {
            scopes.add(previous);
        }
        return scopes;
    }

    /** The key names for one secret (key id) and one account (e-mail digest), optionally one client digest. */
    record Scope(String kid, String email, String client) {

        private String prefix() {
            return NAMESPACE + kid + ":";
        }

        String pairFailures() {
            return pairFailures(client);
        }

        String pairFailures(String clientDigest) {
            return prefix() + "pair:" + email + ":" + clientDigest + ":failures";
        }

        String pairWait() {
            return pairWait(client);
        }

        String pairWait(String clientDigest) {
            return prefix() + "pair:" + email + ":" + clientDigest + ":wait";
        }

        String accountFailures() {
            return prefix() + "account:" + email + ":failures";
        }

        String accountWait() {
            return prefix() + "account:" + email + ":wait";
        }

        String clients() {
            return prefix() + "account:" + email + ":clients";
        }

        String knownPrefix() {
            return prefix() + "known:" + email + ":";
        }

        String known() {
            return knownPrefix() + client;
        }
    }

    // Key-space helpers for tests and diagnostics: the names written under the current secret.

    public String prefix() {
        return NAMESPACE + keys.kid() + ":";
    }

    public String digest(String value) {
        return keys.digest(value);
    }

    public String pairFailuresKey(String emailDigest, String clientDigest) {
        return new Scope(keys.kid(), emailDigest, clientDigest).pairFailures();
    }

    public String accountFailuresKey(String emailDigest) {
        return new Scope(keys.kid(), emailDigest, null).accountFailures();
    }

    public String knownKey(String emailDigest, String clientDigest) {
        return new Scope(keys.kid(), emailDigest, clientDigest).known();
    }
}
