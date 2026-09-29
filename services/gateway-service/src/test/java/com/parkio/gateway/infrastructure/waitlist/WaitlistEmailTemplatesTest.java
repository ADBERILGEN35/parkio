package com.parkio.gateway.infrastructure.waitlist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WaitlistEmailTemplatesTest {

    @Test
    void confirmationTurkishIncludesBrandingAndLocaleLinks() {
        WaitlistEmailTemplates.Copy copy = WaitlistEmailTemplates.confirmation(
                "tr",
                "https://parkio.dev/waitlist/confirm/?token=abc&lang=tr",
                "https://parkio.dev/waitlist/unsubscribe/?token=xyz&lang=tr");
        String html = WaitlistEmailTemplates.renderHtml(copy);
        String text = WaitlistEmailTemplates.renderText(copy);

        assertThat(copy.subject()).contains("bekleme listesi");
        assertThat(html).contains(WaitlistEmailTemplates.LOGO_URL);
        assertThat(html).contains(WaitlistEmailTemplates.BRAND_BLUE);
        assertThat(html).contains("Bekleme listesi aboneliğini onayla");
        assertThat(html).contains("lang=tr");
        assertThat(html).doesNotContain("<script");
        assertThat(text).contains("yok sayın");
        assertThat(text).contains("token=abc");
    }

    @Test
    void confirmationEnglishFallsBackFromUnsupportedLocale() {
        WaitlistEmailTemplates.Copy copy = WaitlistEmailTemplates.confirmation(
                "en",
                "https://parkio.dev/waitlist/confirm/?token=abc&lang=en",
                "https://parkio.dev/waitlist/unsubscribe/?token=xyz&lang=en");
        assertThat(copy.subject()).contains("waitlist subscription");
        assertThat(WaitlistEmailTemplates.renderHtml(copy)).contains("Confirm waitlist subscription");
        assertThat(WaitlistEmailTemplates.normalizeLocale("de")).isEqualTo("tr");
        assertThat(WaitlistEmailTemplates.normalizeLocale(null)).isEqualTo("tr");
    }

    @Test
    void pageUrlAppendsTokenAndLangWithoutLoggingSecrets() {
        String url = WaitlistEmailTemplates.pageUrl("https://parkio.dev/waitlist/confirm/", "tok+en", "en");
        assertThat(url).isEqualTo("https://parkio.dev/waitlist/confirm/?token=tok%2Ben&lang=en");
    }

    @Test
    void escapeHtmlPreventsInjectionInDynamicSlots() {
        assertThat(WaitlistEmailTemplates.escapeHtml("<img src=x onerror=alert(1)>"))
                .isEqualTo("&lt;img src=x onerror=alert(1)&gt;");
    }
}
