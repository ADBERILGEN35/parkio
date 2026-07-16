package com.parkio.aivalidation.infrastructure.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VisionImageRenditionTest {

    @Test
    void downscalesHighResolutionPhoneImageUnderByteCap() {
        byte[] source = VisionTestImages.jpeg(4000, 3000); // 12MP class
        assertThat(source.length).isGreaterThan(100_000);

        VisionImageRendition.Result result = VisionImageRendition.prepare(
                source, "image/jpeg", 40_000_000L, 8000, 1536, 0.85f, 1_500_000L);

        assertThat(result.bytes().length).isLessThanOrEqualTo(1_500_000);
        assertThat(Math.max(result.width(), result.height())).isLessThanOrEqualTo(1536);
        assertThat(result.originalWidth()).isEqualTo(4000);
        assertThat(result.originalHeight()).isEqualTo(3000);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.bytes()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(result.bytes()[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void smallImageIsNotUpscaled() {
        byte[] source = VisionTestImages.jpeg(640, 480);
        VisionImageRendition.Result result = VisionImageRendition.prepare(
                source, "image/jpeg", 40_000_000L, 8000, 1536, 0.85f, 1_500_000L);
        assertThat(result.width()).isEqualTo(640);
        assertThat(result.height()).isEqualTo(480);
    }

    @Test
    void decompressionBombDimensionsAreRejected() {
        // Encode a small JPEG then ask decoder limits that the fixture would exceed after
        // claiming huge dimensions — use a real large canvas under a tight pixel budget.
        byte[] source = VisionTestImages.jpeg(2000, 2000);
        assertThatThrownBy(() -> VisionImageRendition.prepare(
                source, "image/jpeg", 1_000_000L, 8000, 1536, 0.85f, 1_500_000L))
                .isInstanceOf(MediaContentException.class)
                .extracting(ex -> ((MediaContentException) ex).reason())
                .isEqualTo(MediaContentException.Reason.TOO_LARGE);
    }

    @Test
    void emptyPayloadIsRejected() {
        assertThatThrownBy(() -> VisionImageRendition.prepare(
                new byte[0], "image/jpeg", 40_000_000L, 8000, 1536, 0.85f, 1_500_000L))
                .isInstanceOf(MediaContentException.class);
    }

    @Test
    void unsupportedPayloadIsRejected() {
        assertThatThrownBy(() -> VisionImageRendition.prepare(
                new byte[] {1, 2, 3, 4}, "image/gif", 40_000_000L, 8000, 1536, 0.85f, 1_500_000L))
                .isInstanceOf(MediaContentException.class);
    }
}
