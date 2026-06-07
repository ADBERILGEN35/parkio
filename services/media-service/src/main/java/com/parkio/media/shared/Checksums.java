package com.parkio.media.shared;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Small cross-cutting helper for content checksums. Pure JDK, no framework. */
public final class Checksums {

    private Checksums() {
    }

    /** Lower-case hex SHA-256 of the given content. */
    public static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; this cannot happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
