package com.parkio.parking.decision.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorityCanarySelectorTest {

    @Test
    void sameIdentityAlwaysGetsSameBucket() {
        UUID spot = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID eval = UUID.fromString("22222222-2222-2222-2222-222222222222");
        int first = AuthorityCanarySelector.bucket(spot, eval);
        for (int i = 0; i < 50; i++) {
            assertThat(AuthorityCanarySelector.bucket(spot, eval)).isEqualTo(first);
        }
        assertThat(first).isBetween(0, 9999);
    }

    @Test
    void zeroPercentSelectsNobody() {
        UUID spot = UUID.randomUUID();
        UUID eval = UUID.randomUUID();
        assertThat(AuthorityCanarySelector.isSelected(spot, eval, 0)).isFalse();
    }

    @Test
    void oneHundredPercentSelectsEveryone() {
        for (int i = 0; i < 20; i++) {
            assertThat(AuthorityCanarySelector.isSelected(UUID.randomUUID(), UUID.randomUUID(), 100)).isTrue();
        }
    }

    @Test
    void percentageBoundariesUseBasisPoints() {
        assertThat(AuthorityCanarySelector.isSelected(0, 1)).isTrue();
        assertThat(AuthorityCanarySelector.isSelected(99, 1)).isTrue();
        assertThat(AuthorityCanarySelector.isSelected(100, 1)).isFalse();
        assertThat(AuthorityCanarySelector.isSelected(9999, 100)).isTrue();
    }

    @Test
    void rejectsInvalidPercentage() {
        assertThatThrownBy(() -> AuthorityCanarySelector.requirePercentage(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthorityCanarySelector.requirePercentage(101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void algorithmVersionIsExplicitInMaterial() {
        assertThat(AuthorityAlgorithmVersion.V1).isEqualTo("authority-canary-v1");
        assertThat(AuthorityAlgorithmVersion.isKnown("authority-canary-v1")).isTrue();
        assertThat(AuthorityAlgorithmVersion.isKnown("other")).isFalse();
    }

    @Test
    void orderOfCallsDoesNotAffectBucket() {
        UUID a = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID b = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        int ab = AuthorityCanarySelector.bucket(a, b);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            seen.add(AuthorityCanarySelector.bucket(a, b));
        }
        assertThat(seen).containsExactly(ab);
    }
}