package com.parkio.auth.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.auth.domain.EmailLocale;
import org.junit.jupiter.api.Test;

class AuthTransactionalEmailTemplatesTest {

    @Test
    void verificationTurkishIncludesBrandingAndApplicationAccountWording() {
        String url = AuthTransactionalEmailTemplates.pageUrl(
                "https://app.parkio.dev/verify-email", "tok+en", EmailLocale.TR);
        AuthTransactionalEmailTemplates.Copy copy =
                AuthTransactionalEmailTemplates.verification(EmailLocale.TR, url);
        String html = AuthTransactionalEmailTemplates.renderHtml(copy);
        String text = AuthTransactionalEmailTemplates.renderText(copy);

        assertThat(copy.subject()).contains("uygulama hesab");
        assertThat(copy.body()).contains("bekleme listesi kaydı değildir");
        assertThat(copy.instruction()).contains("24 saat");
        assertThat(html).contains(AuthTransactionalEmailTemplates.LOGO_URL);
        assertThat(html).contains(AuthTransactionalEmailTemplates.BRAND_BLUE);
        assertThat(html).contains("Uygulama hesabını doğrula");
        assertThat(html).contains("lang=tr");
        assertThat(html).doesNotContain("<script");
        assertThat(html).doesNotContain("waitlist");
        assertThat(text).contains("yok sayın");
        assertThat(text).contains("token=tok%2Ben");
        assertThat(text).contains("Parkio · parkio.dev");
    }

    @Test
    void verificationEnglishDistinguishesFromWaitlist() {
        String url = AuthTransactionalEmailTemplates.pageUrl(
                "https://app.parkio.dev/verify-email", "abc", EmailLocale.EN);
        AuthTransactionalEmailTemplates.Copy copy =
                AuthTransactionalEmailTemplates.verification(EmailLocale.EN, url);
        String html = AuthTransactionalEmailTemplates.renderHtml(copy);

        assertThat(copy.subject()).contains("application account");
        assertThat(copy.body()).contains("not a waitlist signup");
        assertThat(copy.instruction()).contains("24 hours");
        assertThat(html).contains("Verify application account");
        assertThat(html).contains("lang=en");
        assertThat(html).contains("Reply: info@parkio.dev");
    }

    @Test
    void passwordResetStatesOneHourExpiryFromPolicy() {
        AuthTransactionalEmailTemplates.Copy en =
                AuthTransactionalEmailTemplates.passwordReset(
                        EmailLocale.EN, "https://app.parkio.dev/reset-password?token=x&lang=en");
        AuthTransactionalEmailTemplates.Copy tr =
                AuthTransactionalEmailTemplates.passwordReset(
                        EmailLocale.TR, "https://app.parkio.dev/reset-password?token=x&lang=tr");
        assertThat(en.instruction()).contains("1 hour");
        assertThat(tr.instruction()).contains("1 saat");
    }

    @Test
    void pageUrlAppendsTokenAndLang() {
        String url = AuthTransactionalEmailTemplates.pageUrl(
                "https://app.parkio.dev/verify-email", "tok+en", EmailLocale.EN);
        assertThat(url).isEqualTo("https://app.parkio.dev/verify-email?token=tok%2Ben&lang=en");
    }

    @Test
    void escapeHtmlPreventsInjectionInDynamicSlots() {
        assertThat(AuthTransactionalEmailTemplates.escapeHtml("<img src=x onerror=alert(1)>"))
                .isEqualTo("&lt;img src=x onerror=alert(1)&gt;");
    }
}
