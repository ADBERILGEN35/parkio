package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LoginThrottlePolicyTest {

    @Test
    void pairTiersAreProgressive() {
        assertThat(LoginThrottlePolicy.pairDelay(1)).isEqualTo(Duration.ZERO);
        assertThat(LoginThrottlePolicy.pairDelay(4)).isEqualTo(Duration.ZERO);
        assertThat(LoginThrottlePolicy.pairDelay(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(LoginThrottlePolicy.pairDelay(9)).isEqualTo(Duration.ofSeconds(30));
        assertThat(LoginThrottlePolicy.pairDelay(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(LoginThrottlePolicy.pairDelay(20)).isEqualTo(Duration.ofHours(1));
        assertThat(LoginThrottlePolicy.pairDelay(500)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void accountTiersEscalateAndStayBounded() {
        assertThat(LoginThrottlePolicy.accountDelay(LoginThrottlePolicy.ACCOUNT_FIRST_CAP - 1)).isEqualTo(Duration.ZERO);
        assertThat(LoginThrottlePolicy.accountDelay(LoginThrottlePolicy.ACCOUNT_FIRST_CAP))
                .isEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        assertThat(LoginThrottlePolicy.accountDelay(LoginThrottlePolicy.ACCOUNT_SECOND_CAP - 1))
                .isEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        assertThat(LoginThrottlePolicy.accountDelay(LoginThrottlePolicy.ACCOUNT_SECOND_CAP))
                .isEqualTo(LoginThrottlePolicy.ACCOUNT_SECOND_DELAY);
        assertThat(LoginThrottlePolicy.accountDelay(LoginThrottlePolicy.ACCOUNT_THIRD_CAP))
                .isEqualTo(LoginThrottlePolicy.ACCOUNT_THIRD_DELAY);
        assertThat(LoginThrottlePolicy.accountDelay(100_000)).isEqualTo(LoginThrottlePolicy.ACCOUNT_THIRD_DELAY);
        // The longest account wait is shorter than the account window, so the window restarts with every
        // failure of a sustained attack and the tier holds; it ends 1 h after the attack stops.
        assertThat(LoginThrottlePolicy.ACCOUNT_THIRD_DELAY).isLessThan(LoginThrottlePolicy.ACCOUNT_WINDOW);
    }

    /**
     * One client alone cannot trip the first account cap: inside the account window its own pair
     * delays allow at most 5 + 5 + 10 failures before each further one costs an hour.
     */
    @Test
    void aSingleClientCannotReachTheAccountCapWithinTheAccountWindow() {
        long failures = 0;
        Duration elapsed = Duration.ZERO;
        while (elapsed.compareTo(LoginThrottlePolicy.ACCOUNT_WINDOW) < 0) {
            failures++;
            elapsed = elapsed.plus(LoginThrottlePolicy.pairDelay(failures));
        }
        assertThat(failures).isLessThan(LoginThrottlePolicy.ACCOUNT_FIRST_CAP);
    }

    @Test
    void maxPicksTheLongerDelay() {
        assertThat(LoginThrottlePolicy.max(Duration.ofSeconds(3), Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(10));
        assertThat(LoginThrottlePolicy.max(Duration.ofSeconds(10), Duration.ZERO)).isEqualTo(Duration.ofSeconds(10));
    }
}
