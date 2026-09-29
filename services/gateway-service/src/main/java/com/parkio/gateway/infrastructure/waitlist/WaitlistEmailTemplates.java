package com.parkio.gateway.infrastructure.waitlist;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Branded TR/EN waitlist confirmation and withdrawal email bodies.
 * Locale is the subscriber preference (tr fallback); never inferred from domain.
 */
final class WaitlistEmailTemplates {

    static final String LOGO_URL = "https://parkio.dev/assets/parkio-logo.png";
    static final String BRAND_BLUE = "#0769fd";

    private WaitlistEmailTemplates() {}

    static boolean isTurkish(String locale) {
        return locale == null || !"en".equalsIgnoreCase(locale.trim());
    }

    static String normalizeLocale(String locale) {
        return isTurkish(locale) ? "tr" : "en";
    }

    static Copy confirmation(String locale, String confirmUrl, String withdrawUrl) {
        String lang = normalizeLocale(locale);
        if ("en".equals(lang)) {
            return new Copy(
                    "Confirm your Parkio waitlist subscription",
                    "Confirm your Parkio registration waitlist signup",
                    "Parkio waitlist confirmation",
                    "This email confirms your request to join the Parkio registration notification waitlist — not an application account.",
                    "Press Confirm on the page to stay on the list. Link previews do not confirm automatically.",
                    "Confirm waitlist subscription",
                    "Or open this confirmation page:",
                    "To leave the waitlist later:",
                    "If you did not request this, ignore this email.",
                    "Parkio · parkio.dev · Reply: info@parkio.dev",
                    confirmUrl,
                    withdrawUrl);
        }
        return new Copy(
                "Parkio bekleme listesi aboneliğinizi onaylayın",
                "Parkio kayıt bildirim listesini onaylayın",
                "Parkio bekleme listesi onayı",
                "Bu e-posta, Parkio kayıt bildirim bekleme listesine katılma isteğinizi onaylamak içindir — uygulama hesabı oluşturmaz.",
                "Listede kalmak için sayfadaki Onayla düğmesine basın. Bağlantı önizlemeleri otomatik onaylamaz.",
                "Bekleme listesi aboneliğini onayla",
                "Ya da bu onay sayfasını açın:",
                "Listeden daha sonra çıkmak için:",
                "Bu isteği siz yapmadıysanız bu e-postayı yok sayın.",
                "Parkio · parkio.dev · Yanıt: info@parkio.dev",
                confirmUrl,
                withdrawUrl);
    }

    static Copy withdrawal(String locale) {
        String lang = normalizeLocale(locale);
        if ("en".equals(lang)) {
            return new Copy(
                    "Your Parkio waitlist signup was removed",
                    "Your Parkio waitlist signup was removed",
                    "Waitlist withdrawal",
                    "Your email was removed from the Parkio registration notification waitlist. No application account was deleted.",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Parkio · parkio.dev · Reply: info@parkio.dev",
                    null,
                    null);
        }
        return new Copy(
                "Parkio bildirim listesi kaydınız silindi",
                "Parkio bildirim listesi kaydınız silindi",
                "Bekleme listesinden çıkış",
                "E-posta adresiniz Parkio kayıt bildirim bekleme listesinden silindi. Uygulama hesabı silinmedi.",
                null,
                null,
                null,
                null,
                null,
                "Parkio · parkio.dev · Yanıt: info@parkio.dev",
                null,
                null);
    }

    static String renderText(Copy copy) {
        StringBuilder text = new StringBuilder();
        text.append(copy.heading()).append("\n\n");
        text.append(copy.body()).append("\n\n");
        if (copy.confirmUrl() != null) {
            if (copy.instruction() != null) {
                text.append(copy.instruction()).append("\n\n");
            }
            text.append(copy.fallbackIntro()).append("\n");
            text.append(copy.confirmUrl()).append("\n\n");
        }
        if (copy.withdrawUrl() != null && copy.withdrawIntro() != null) {
            text.append(copy.withdrawIntro()).append("\n");
            text.append(copy.withdrawUrl()).append("\n\n");
        }
        if (copy.ignoreNote() != null) {
            text.append(copy.ignoreNote()).append("\n\n");
        }
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
        if (copy.confirmUrl() != null && copy.cta() != null) {
            if (copy.instruction() != null) {
                html.append("<p style=\"margin:0 0 18px;color:#52627b\">")
                        .append(escapeHtml(copy.instruction()))
                        .append("</p>");
            }
            html.append("<p style=\"margin:0 0 22px\">")
                    .append("<a href=\"")
                    .append(escapeHtml(copy.confirmUrl()))
                    .append("\" style=\"display:inline-block;background:")
                    .append(BRAND_BLUE)
                    .append(";color:#ffffff;text-decoration:none;padding:14px 22px;")
                    .append("border-radius:10px;font-weight:700\">")
                    .append(escapeHtml(copy.cta()))
                    .append("</a></p>");
            html.append("<p style=\"margin:0 0 16px;font-size:13px;color:#8490a3\">")
                    .append(escapeHtml(copy.fallbackIntro()))
                    .append("<br><a href=\"")
                    .append(escapeHtml(copy.confirmUrl()))
                    .append("\" style=\"color:")
                    .append(BRAND_BLUE)
                    .append(";word-break:break-all\">")
                    .append(escapeHtml(copy.confirmUrl()))
                    .append("</a></p>");
        }
        if (copy.withdrawUrl() != null && copy.withdrawIntro() != null) {
            html.append("<p style=\"margin:0 0 16px;font-size:13px;color:#8490a3\">")
                    .append(escapeHtml(copy.withdrawIntro()))
                    .append("<br><a href=\"")
                    .append(escapeHtml(copy.withdrawUrl()))
                    .append("\" style=\"color:")
                    .append(BRAND_BLUE)
                    .append(";word-break:break-all\">")
                    .append(escapeHtml(copy.withdrawUrl()))
                    .append("</a></p>");
        }
        if (copy.ignoreNote() != null) {
            html.append("<p style=\"margin:0 0 18px;font-size:13px;color:#8490a3\">")
                    .append(escapeHtml(copy.ignoreNote()))
                    .append("</p>");
        }
        html.append("<p style=\"margin:24px 0 0;padding-top:16px;border-top:1px solid #edf1f5;")
                .append("font-size:12px;color:#8490a3\">")
                .append(escapeHtml(copy.footer()))
                .append("</p>");
        html.append("</div></body></html>");
        return html.toString();
    }

    static String pageUrl(String base, String rawToken, String locale) {
        String separator = base.contains("?") ? "&" : "?";
        return base
                + separator
                + "token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8)
                + "&lang="
                + normalizeLocale(locale);
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
            String withdrawIntro,
            String ignoreNote,
            String footer,
            String confirmUrl,
            String withdrawUrl) {}
}
