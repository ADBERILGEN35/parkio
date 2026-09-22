package com.parkio.auth.infrastructure.notification;

import com.parkio.auth.domain.EmailLocale;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Branded TR/EN transactional email bodies for auth-service.
 * Visual conventions match waitlist emails (logo, card, brand blue CTA, support footer)
 * without coupling to gateway-service — shared public logo URL only.
 */
final class AuthTransactionalEmailTemplates {

    static final String LOGO_URL = "https://parkio.dev/assets/parkio-logo.png";
    static final String BRAND_BLUE = "#0769fd";

    private AuthTransactionalEmailTemplates() {}

    static Copy verification(EmailLocale locale, String verifyUrl) {
        // TTL copy must match parkio.security.email-verification.token-ttl default PT24H.
        if (locale == EmailLocale.EN) {
            return new Copy(
                    "Verify your Parkio application account",
                    "Verify your Parkio application account",
                    "Application account verification",
                    "This email verifies the email address for your Parkio application account — not a waitlist signup.",
                    "Open the page and complete verification to activate sign-in. This link expires in 24 hours. Link previews do not verify automatically.",
                    "Verify application account",
                    "Or open this verification page:",
                    "If you did not create a Parkio application account, ignore this email.",
                    "Parkio · parkio.dev · Reply: info@parkio.dev",
                    verifyUrl);
        }
        return new Copy(
                "Parkio uygulama hesabınızı doğrulayın",
                "Parkio uygulama hesabınızı doğrulayın",
                "Uygulama hesabı doğrulama",
                "Bu e-posta, Parkio uygulama hesabınızın e-posta adresini doğrular — bekleme listesi kaydı değildir.",
                "Giriş yapabilmek için sayfadaki doğrulamayı tamamlayın. Bu bağlantının süresi 24 saat içinde dolar. Bağlantı önizlemeleri otomatik doğrulamaz.",
                "Uygulama hesabını doğrula",
                "Ya da bu doğrulama sayfasını açın:",
                "Parkio uygulama hesabı oluşturmadıysanız bu e-postayı yok sayın.",
                "Parkio · parkio.dev · Yanıt: info@parkio.dev",
                verifyUrl);
    }

    static Copy passwordReset(EmailLocale locale, String resetUrl) {
        // TTL copy must match parkio.security.password-reset.token-ttl default PT1H.
        if (locale == EmailLocale.EN) {
            return new Copy(
                    "Reset your Parkio password",
                    "Reset your Parkio password",
                    "Password reset",
                    "We received a request to reset the password for your Parkio application account.",
                    "Choose a new password on the page. This link expires in 1 hour for your security.",
                    "Reset password",
                    "Or open this password reset page:",
                    "If you did not request a password reset, ignore this email.",
                    "Parkio · parkio.dev · Reply: info@parkio.dev",
                    resetUrl);
        }
        return new Copy(
                "Parkio şifrenizi sıfırlayın",
                "Parkio şifrenizi sıfırlayın",
                "Şifre sıfırlama",
                "Parkio uygulama hesabınız için bir şifre sıfırlama isteği aldık.",
                "Sayfada yeni bir şifre seçin. Güvenliğiniz için bu bağlantının süresi 1 saat içinde dolar.",
                "Şifreyi sıfırla",
                "Ya da bu şifre sıfırlama sayfasını açın:",
                "Şifre sıfırlama talebinde bulunmadıysanız bu e-postayı yok sayın.",
                "Parkio · parkio.dev · Yanıt: info@parkio.dev",
                resetUrl);
    }

    static String renderText(Copy copy) {
        StringBuilder text = new StringBuilder();
        text.append(copy.heading()).append("\n\n");
        text.append(copy.body()).append("\n\n");
        if (copy.instruction() != null) {
            text.append(copy.instruction()).append("\n\n");
        }
        if (copy.actionUrl() != null) {
            text.append(copy.fallbackIntro()).append("\n");
            text.append(copy.actionUrl()).append("\n\n");
        }
        text.append(copy.ignoreNote()).append("\n\n");
        text.append(copy.footer());
        return text.toString();
    }

    static String renderHtml(Copy copy) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><body style=\"margin:0;padding:0;background:#f4f7fb;\">");
        html.append("<div style=\"font-family:Segoe UI,Helvetica,Arial,sans-serif;line-height:1.55;color:#0b1730;")
                .append("max-width:560px;margin:0 auto;padding:28px 20px;background:#ffffff;\">");
        html.append("<div style=\"margin:0 0 20px\">")
                .append("<img src=\"")
                .append(escapeHtml(LOGO_URL))
                .append("\" alt=\"Parkio\" width=\"48\" height=\"48\" ")
                .append("style=\"display:block;border:0;border-radius:12px;\">")
                .append("</div>");
        html.append("<p style=\"margin:0 0 8px;font-size:12px;font-weight:700;letter-spacing:.04em;")
                .append("text-transform:uppercase;color:")
                .append(BRAND_BLUE)
                .append("\">")
                .append(escapeHtml(copy.preheader()))
                .append("</p>");
        html.append("<h1 style=\"font-size:22px;line-height:1.25;margin:0 0 14px;color:#0b1730\">")
                .append(escapeHtml(copy.heading()))
                .append("</h1>");
        html.append("<p style=\"margin:0 0 16px;color:#52627b\">")
                .append(escapeHtml(copy.body()))
                .append("</p>");
        if (copy.actionUrl() != null && copy.cta() != null) {
            if (copy.instruction() != null) {
                html.append("<p style=\"margin:0 0 18px;color:#52627b\">")
                        .append(escapeHtml(copy.instruction()))
                        .append("</p>");
            }
            html.append("<p style=\"margin:0 0 22px\">")
                    .append("<a href=\"")
                    .append(escapeHtml(copy.actionUrl()))
                    .append("\" style=\"display:inline-block;background:")
                    .append(BRAND_BLUE)
                    .append(";color:#ffffff;text-decoration:none;padding:14px 22px;")
                    .append("border-radius:10px;font-weight:700\">")
                    .append(escapeHtml(copy.cta()))
                    .append("</a></p>");
            html.append("<p style=\"margin:0 0 16px;font-size:13px;color:#8490a3\">")
                    .append(escapeHtml(copy.fallbackIntro()))
                    .append("<br><a href=\"")
                    .append(escapeHtml(copy.actionUrl()))
                    .append("\" style=\"color:")
                    .append(BRAND_BLUE)
                    .append(";word-break:break-all\">")
                    .append(escapeHtml(copy.actionUrl()))
                    .append("</a></p>");
        }
        html.append("<p style=\"margin:0 0 18px;font-size:13px;color:#8490a3\">")
                .append(escapeHtml(copy.ignoreNote()))
                .append("</p>");
        html.append("<p style=\"margin:24px 0 0;padding-top:16px;border-top:1px solid #edf1f5;")
                .append("font-size:12px;color:#8490a3\">")
                .append(escapeHtml(copy.footer()))
                .append("</p>");
        html.append("</div></body></html>");
        return html.toString();
    }

    /** Appends {@code token} and {@code lang} so the SPA can match registration locale. */
    static String pageUrl(String base, String rawToken, EmailLocale locale) {
        String separator = base.contains("?") ? "&" : "?";
        return base
                + separator
                + "token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8)
                + "&lang="
                + locale.code();
    }

    static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    record Copy(
            String subject,
            String heading,
            String preheader,
            String body,
            String instruction,
            String cta,
            String fallbackIntro,
            String ignoreNote,
            String footer,
            String actionUrl) {}
}
