package com.parkio.gateway.application.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** CL-F18: what the gateway records as the subscriber's consent, in both modes. */
class WaitlistConsentPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-07T18:00:00Z");

    private static WaitlistApplicationService service(boolean consentRequired) {
        WaitlistProperties properties = new WaitlistProperties();
        properties.setHashSecret("unit-test-hash-secret-32chars-min!!");
        properties.setConsentRequired(consentRequired);
        return new WaitlistApplicationService(
                mock(WaitlistInterestRepository.class),
                mock(WaitlistHasher.class),
                mock(WaitlistRateLimiter.class),
                mock(WaitlistEmailSender.class),
                properties,
                mock(WaitlistOpsNotifier.class),
                TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void requiredModeNeedsConsentTrueAndARegisteredVersion() {
        WaitlistApplicationService service = service(true);
        assertThat(service.requireConsent(true, "waitlist-consent-v1")).isEqualTo("waitlist-consent-v1");
        assertThatThrownBy(() -> service.requireConsent(null, "waitlist-consent-v1"))
                .isInstanceOf(WaitlistConsentException.class).hasMessage("WAITLIST_CONSENT_REQUIRED");
        assertThatThrownBy(() -> service.requireConsent(false, "waitlist-consent-v1"))
                .isInstanceOf(WaitlistConsentException.class).hasMessage("WAITLIST_CONSENT_REQUIRED");
        assertThatThrownBy(() -> service.requireConsent(true, null))
                .isInstanceOf(WaitlistConsentException.class).hasMessage("WAITLIST_CONSENT_VERSION_INVALID");
        assertThatThrownBy(() -> service.requireConsent(true, "  "))
                .isInstanceOf(WaitlistConsentException.class).hasMessage("WAITLIST_CONSENT_VERSION_INVALID");
        assertThatThrownBy(() -> service.requireConsent(true, "waitlist-consent-v9"))
                .isInstanceOf(WaitlistConsentException.class).hasMessage("WAITLIST_CONSENT_VERSION_INVALID");
    }

    @Test
    void theSentinelsAreNeverAcceptedAsAClientVersion() {
        for (boolean required : new boolean[] {true, false}) {
            WaitlistApplicationService service = service(required);
            assertThatThrownBy(() -> service.requireConsent(true, WaitlistConsentText.LEGACY_UNVERSIONED))
                    .isInstanceOf(WaitlistConsentException.class).hasMessage("WAITLIST_CONSENT_VERSION_INVALID");
            assertThatThrownBy(() -> service.requireConsent(true, WaitlistConsentText.UNVERSIONED_CLIENT))
                    .isInstanceOf(WaitlistConsentException.class).hasMessage("WAITLIST_CONSENT_VERSION_INVALID");
        }
    }

    @Test
    void compatibilityModeAcceptsOldClientsAsUnversionedButNeverAnExplicitRefusal() {
        WaitlistApplicationService service = service(false);
        assertThat(service.requireConsent(true, "waitlist-consent-v1")).isEqualTo("waitlist-consent-v1");
        assertThat(service.requireConsent(null, null)).isEqualTo(WaitlistConsentText.UNVERSIONED_CLIENT);
        assertThat(service.requireConsent(true, null)).isEqualTo(WaitlistConsentText.UNVERSIONED_CLIENT);
        // A version without consent=true is not a recorded consent.
        assertThat(service.requireConsent(null, "waitlist-consent-v1")).isEqualTo(WaitlistConsentText.UNVERSIONED_CLIENT);
        assertThatThrownBy(() -> service.requireConsent(false, null))
                .isInstanceOf(WaitlistConsentException.class).hasMessage("WAITLIST_CONSENT_REQUIRED");
        assertThatThrownBy(() -> service.requireConsent(true, "not-a-version"))
                .isInstanceOf(WaitlistConsentException.class).hasMessage("WAITLIST_CONSENT_VERSION_INVALID");
    }
}
