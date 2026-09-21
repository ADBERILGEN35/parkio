package com.parkio.gateway.application.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WaitlistConsentTimestampPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-21T13:00:00Z");

    private WaitlistApplicationService service;

    @BeforeEach
    void setUp() {
        WaitlistProperties properties = new WaitlistProperties();
        properties.setHashSecret("unit-test-hash-secret-32chars-min!!");
        properties.setConsentMaxFutureSkew(Duration.ofMinutes(2));
        properties.setConsentMaxPastAge(Duration.ofDays(7));
        service = new WaitlistApplicationService(
                mock(WaitlistInterestRepository.class),
                mock(WaitlistHasher.class),
                mock(WaitlistRateLimiter.class),
                mock(WaitlistEmailSender.class),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsNormalAndSmallSkew() {
        assertThat(service.requireClientConsentTimestamp(NOW, NOW)).isEqualTo(NOW);
        assertThat(service.requireClientConsentTimestamp(NOW.minusSeconds(30), NOW)).isEqualTo(NOW.minusSeconds(30));
        assertThat(service.requireClientConsentTimestamp(NOW.plusSeconds(90), NOW)).isEqualTo(NOW.plusSeconds(90));
    }

    @Test
    void rejectsExcessiveFutureAndStalePast() {
        assertThatThrownBy(() -> service.requireClientConsentTimestamp(NOW.plus(Duration.ofMinutes(5)), NOW))
                .isInstanceOf(WaitlistConsentTimestampException.class);
        assertThatThrownBy(() -> service.requireClientConsentTimestamp(NOW.minus(Duration.ofDays(8)), NOW))
                .isInstanceOf(WaitlistConsentTimestampException.class);
        assertThatThrownBy(() -> service.requireClientConsentTimestamp(null, NOW))
                .isInstanceOf(WaitlistConsentTimestampException.class);
    }
}
