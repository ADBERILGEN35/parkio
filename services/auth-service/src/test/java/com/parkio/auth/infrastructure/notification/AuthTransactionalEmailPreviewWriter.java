package com.parkio.auth.infrastructure.notification;

import com.parkio.auth.domain.EmailLocale;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Writes synthetic TR/EN verification (+ password-reset) HTML/text previews for release review.
 * Uses a fixed synthetic token — not a live credential.
 */
class AuthTransactionalEmailPreviewWriter {

    private static final String SYNTHETIC_TOKEN = "preview-token-not-a-credential";
    private static final String VERIFY_BASE = "https://app.parkio.dev/verify-email";
    private static final String RESET_BASE = "https://app.parkio.dev/reset-password";

    @Test
    void writePreviews() throws Exception {
        Path repoRoot = Path.of("../..").toAbsolutePath().normalize();
        Path out = repoRoot.resolve("agent-tools/parkio-pr68-auth-verification-email-01/previews");
        Files.createDirectories(out);

        writeVerification(out, EmailLocale.TR);
        writeVerification(out, EmailLocale.EN);
        writePasswordReset(out, EmailLocale.TR);
        writePasswordReset(out, EmailLocale.EN);

        String url = AuthTransactionalEmailTemplates.pageUrl(VERIFY_BASE, SYNTHETIC_TOKEN, EmailLocale.EN);
        AuthTransactionalEmailTemplates.Copy copy =
                AuthTransactionalEmailTemplates.verification(EmailLocale.EN, url);
        String htmlBrokenLogo = AuthTransactionalEmailTemplates.renderHtml(copy)
                .replace(
                        AuthTransactionalEmailTemplates.LOGO_URL,
                        "https://parkio.dev/assets/missing-logo-preview.png");
        Files.writeString(
                out.resolve("verification-en-images-disabled.html"),
                htmlBrokenLogo,
                StandardCharsets.UTF_8);

        Files.writeString(
                out.resolve("README.txt"),
                """
                Synthetic previews for PR #68 auth verification email branding.
                Token in URLs is preview-token-not-a-credential (not live).
                Verification TTL stated in copy: 24 hours (PARKIO_EMAIL_VERIFICATION_TTL default PT24H).
                Password-reset TTL stated in copy: 1 hour (PARKIO_PASSWORD_RESET_TTL default PT1H).
                Distinct from waitlist confirmation: subjects/bodies say "application account".
                """,
                StandardCharsets.UTF_8);
    }

    private static void writeVerification(Path out, EmailLocale locale) throws Exception {
        String url = AuthTransactionalEmailTemplates.pageUrl(VERIFY_BASE, SYNTHETIC_TOKEN, locale);
        AuthTransactionalEmailTemplates.Copy copy =
                AuthTransactionalEmailTemplates.verification(locale, url);
        String code = locale.code();
        Files.writeString(
                out.resolve("verification-" + code + ".html"),
                AuthTransactionalEmailTemplates.renderHtml(copy),
                StandardCharsets.UTF_8);
        Files.writeString(
                out.resolve("verification-" + code + ".txt"),
                AuthTransactionalEmailTemplates.renderText(copy),
                StandardCharsets.UTF_8);
        Files.writeString(
                out.resolve("verification-" + code + "-meta.txt"),
                "subject=" + copy.subject() + "\nurl=" + url + "\n",
                StandardCharsets.UTF_8);
    }

    private static void writePasswordReset(Path out, EmailLocale locale) throws Exception {
        String url = AuthTransactionalEmailTemplates.pageUrl(RESET_BASE, SYNTHETIC_TOKEN, locale);
        AuthTransactionalEmailTemplates.Copy copy =
                AuthTransactionalEmailTemplates.passwordReset(locale, url);
        String code = locale.code();
        Files.writeString(
                out.resolve("password-reset-" + code + ".html"),
                AuthTransactionalEmailTemplates.renderHtml(copy),
                StandardCharsets.UTF_8);
        Files.writeString(
                out.resolve("password-reset-" + code + ".txt"),
                AuthTransactionalEmailTemplates.renderText(copy),
                StandardCharsets.UTF_8);
    }
}
