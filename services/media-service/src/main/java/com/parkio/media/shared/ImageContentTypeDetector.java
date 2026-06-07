package com.parkio.media.shared;

import java.util.Optional;

/**
 * Detects an image's real content type from its leading "magic" bytes, limited to
 * the types media-service accepts. Used to confirm that uploaded bytes match the
 * client-declared {@code Content-Type} rather than trusting the header. Pure JDK,
 * no framework.
 */
public final class ImageContentTypeDetector {

    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private ImageContentTypeDetector() {
    }

    /** The detected content type, or empty if the bytes are not a recognised image. */
    public static Optional<String> detect(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }
        if (isJpeg(content)) {
            return Optional.of("image/jpeg");
        }
        if (isPng(content)) {
            return Optional.of("image/png");
        }
        if (isWebp(content)) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean isJpeg(byte[] b) {
        // SOI marker: FF D8 FF
        return b.length >= 3
                && (b[0] & 0xFF) == 0xFF
                && (b[1] & 0xFF) == 0xD8
                && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] b) {
        if (b.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (b[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWebp(byte[] b) {
        // RIFF container with a "WEBP" form type: "RIFF" <4-byte size> "WEBP"
        return b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }
}
