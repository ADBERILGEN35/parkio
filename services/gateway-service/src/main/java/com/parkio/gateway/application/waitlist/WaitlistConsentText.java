package com.parkio.gateway.application.waitlist;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Registry of the consent texts a waitlist subscriber can accept (CL-F18). A submission names the
 * version it showed; the gateway accepts only versions registered here and stores the id with the
 * row, so the wording behind every recorded consent is reproducible. The texts are the marketing
 * site's checkbox labels verbatim; the marketing validation pins the same SHA-256 values, so the
 * site and this registry cannot drift apart unnoticed. Registering a text here is not legal approval
 * of its wording.
 */
public final class WaitlistConsentText {

    /** The version the marketing site sends today. */
    public static final String CURRENT_VERSION = "waitlist-consent-v1";

    /** Rows recorded before server-side consent versioning, or by a path that sent no version. */
    public static final String LEGACY_UNVERSIONED = "legacy-unversioned";

    /** A compatibility-mode submission that carried no version while versions were not required. */
    public static final String UNVERSIONED_CLIENT = "unversioned-client";

    public static final String V1_TR =
            "Kayıtlar açıldığında Parkio’nun beni e-posta ile bilgilendirmesini kabul ediyorum. "
                    + "Onay için e-postamdaki bağlantıyı kullanacağım.";
    public static final String V1_EN =
            "I agree that Parkio may email me when registrations open. "
                    + "I will confirm via the link in my email.";

    private static final Map<String, Map<String, String>> TEXTS = Map.of(
            CURRENT_VERSION, Map.of("tr", V1_TR, "en", V1_EN));

    private static final Set<String> SENTINELS = Set.of(LEGACY_UNVERSIONED, UNVERSIONED_CLIENT);

    private WaitlistConsentText() {
    }

    /** True for a registered consent text version (never for the sentinels). */
    public static boolean isKnownVersion(String version) {
        return version != null && TEXTS.containsKey(version);
    }

    public static boolean isSentinel(String version) {
        return version != null && SENTINELS.contains(version);
    }

    /** The registered text of a version in a locale, or null. */
    public static String text(String version, String locale) {
        Map<String, String> byLocale = TEXTS.get(version);
        return byLocale == null ? null : byLocale.get(locale);
    }

    /** SHA-256 of the UTF-8 text, lower-case hex; the marketing validation pins the same values. */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
