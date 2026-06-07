package com.parkio.media.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImageContentTypeDetectorTest {

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] out = new byte[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    @Test
    void detectsJpeg() {
        byte[] jpeg = concat(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, new byte[]{0x00, 0x10});
        assertThat(ImageContentTypeDetector.detect(jpeg)).contains("image/jpeg");
    }

    @Test
    void detectsPng() {
        byte[] png = concat(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, new byte[]{1, 2});
        assertThat(ImageContentTypeDetector.detect(png)).contains("image/png");
    }

    @Test
    void detectsWebp() {
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1, 2};
        assertThat(ImageContentTypeDetector.detect(webp)).contains("image/webp");
    }

    @Test
    void returnsEmptyForNonImageBytes() {
        assertThat(ImageContentTypeDetector.detect(new byte[]{1, 2, 3, 4})).isEmpty();
    }

    @Test
    void returnsEmptyForTruncatedSignature() {
        // JPEG needs 3 magic bytes; two is not enough.
        assertThat(ImageContentTypeDetector.detect(new byte[]{(byte) 0xFF, (byte) 0xD8})).isEmpty();
    }

    @Test
    void returnsEmptyForNullOrEmpty() {
        assertThat(ImageContentTypeDetector.detect(null)).isEmpty();
        assertThat(ImageContentTypeDetector.detect(new byte[0])).isEmpty();
    }
}
