package com.parkio.gateway.application.waitlist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The registered consent texts are the marketing checkbox labels verbatim. The SHA-256 values here
 * are also pinned by web/marketing/waitlist.consent-version.test.mjs against i18n.js, so the site
 * and the registry cannot drift apart unnoticed (CL-F18).
 */
class WaitlistConsentTextTest {

    @Test
    void v1TextsMatchTheMarketingLabels() {
        assertThat(WaitlistConsentText.sha256(WaitlistConsentText.V1_TR))
                .isEqualTo("20f3d2355fd83a52b6753c1a86fbfe8afc929e3e916cb0b34e9add4d6294824c");
        assertThat(WaitlistConsentText.sha256(WaitlistConsentText.V1_EN))
                .isEqualTo("eed8440ccbfa389bd22e915b39ce5de1fbf27ef01b9158368c7fa8b0e2fd0a75");
        assertThat(WaitlistConsentText.text(WaitlistConsentText.CURRENT_VERSION, "tr"))
                .isEqualTo(WaitlistConsentText.V1_TR);
        assertThat(WaitlistConsentText.text(WaitlistConsentText.CURRENT_VERSION, "en"))
                .isEqualTo(WaitlistConsentText.V1_EN);
    }

    @Test
    void onlyRegisteredVersionsAreKnownAndSentinelsNeverAre() {
        assertThat(WaitlistConsentText.isKnownVersion("waitlist-consent-v1")).isTrue();
        assertThat(WaitlistConsentText.isKnownVersion("waitlist-consent-v2")).isFalse();
        assertThat(WaitlistConsentText.isKnownVersion(WaitlistConsentText.LEGACY_UNVERSIONED)).isFalse();
        assertThat(WaitlistConsentText.isKnownVersion(WaitlistConsentText.UNVERSIONED_CLIENT)).isFalse();
        assertThat(WaitlistConsentText.isKnownVersion(null)).isFalse();
        assertThat(WaitlistConsentText.isSentinel(WaitlistConsentText.LEGACY_UNVERSIONED)).isTrue();
        assertThat(WaitlistConsentText.isSentinel("waitlist-consent-v1")).isFalse();
        assertThat(WaitlistConsentText.text("waitlist-consent-v2", "tr")).isNull();
    }
}
