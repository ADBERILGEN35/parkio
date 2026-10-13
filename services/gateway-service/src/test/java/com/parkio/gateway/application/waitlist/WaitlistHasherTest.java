package com.parkio.gateway.application.waitlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WaitlistHasherTest {

    private static final String CURRENT = "current-waitlist-hash-secret-32chars-min!!";
    private static final String PREVIOUS = "previous-waitlist-hash-secret-32chars-min!";

    @Test
    void hashUsesCurrentSecretOnly() {
        WaitlistProperties props = props(CURRENT, PREVIOUS);
        WaitlistHasher hasher = new WaitlistHasher(props);
        WaitlistHasher currentOnly = new WaitlistHasher(props(CURRENT, ""));
        assertEquals(currentOnly.hash("user@example.com"), hasher.hash("user@example.com"));
        assertNotEquals(
                new WaitlistHasher(props(PREVIOUS, "")).hash("user@example.com"),
                hasher.hash("user@example.com"));
    }

    @Test
    void hashCandidatesIncludePreviousWhenConfigured() {
        WaitlistHasher hasher = new WaitlistHasher(props(CURRENT, PREVIOUS));
        List<String> candidates = hasher.hashCandidates("token-value");
        assertEquals(2, candidates.size());
        assertEquals(hasher.hash("token-value"), candidates.get(0));
        assertEquals(new WaitlistHasher(props(PREVIOUS, "")).hash("token-value"), candidates.get(1));
    }

    @Test
    void hashCandidatesOmitPreviousWhenBlankOrEqual() {
        assertEquals(1, new WaitlistHasher(props(CURRENT, "")).hashCandidates("x").size());
        assertEquals(1, new WaitlistHasher(props(CURRENT, CURRENT)).hashCandidates("x").size());
    }

    @Test
    void previousSecretMustBeLongEnoughWhenSet() {
        assertThrows(IllegalStateException.class, () -> new WaitlistHasher(props(CURRENT, "too-short")));
    }

    @Test
    void currentSecretMustBeLongEnough() {
        assertThrows(IllegalStateException.class, () -> new WaitlistHasher(props("short", "")));
    }

    @Test
    void candidatesAreDeterministic() {
        WaitlistHasher hasher = new WaitlistHasher(props(CURRENT, PREVIOUS));
        assertTrue(hasher.hashCandidates("a").containsAll(hasher.hashCandidates("a")));
        assertEquals(hasher.hashCandidates("a"), hasher.hashCandidates("a"));
    }

    private static WaitlistProperties props(String current, String previous) {
        WaitlistProperties properties = new WaitlistProperties();
        properties.setHashSecret(current);
        properties.setHashSecretPrevious(previous);
        return properties;
    }
}
