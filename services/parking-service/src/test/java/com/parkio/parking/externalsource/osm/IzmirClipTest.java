package com.parkio.parking.externalsource.osm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IzmirClipTest {
    @AfterEach
    void reset() {
        IzmirClip.resetToAdminEnvelope();
    }

    @Test
    void adminEnvelopeCoversBergamaAndExcludesIstanbul() {
        assertThat(IzmirClip.CLIP_VERSION).isEqualTo("izmir-admin-izbb-2024-10-18-v1");
        assertThat(IzmirClip.contains(39.12, 27.18)).isTrue();
        assertThat(IzmirClip.contains(38.4192, 27.1285)).isTrue();
        assertThat(IzmirClip.contains(41.0, 28.0)).isFalse();
    }

    @Test
    void foldDistrictNamesAreTurkishAware() {
        assertThat(IzmirBoundaryAssetValidator.foldDistrictName("Çiğli")).isEqualTo("CIGLI");
        assertThat(IzmirBoundaryAssetValidator.foldDistrictName("ÖDEMİŞ")).isEqualTo("ODEMIS");
        assertThat(IzmirBoundaryAssetValidator.foldDistrictName("Karşıyaka")).isEqualTo("KARSIYAKA");
    }
}
