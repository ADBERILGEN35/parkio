package com.parkio.parking.externalsource;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.IsparkOccupancyPublicationPolicy.OpenStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IsparkOccupancyPublicationPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"isOpen\":0}",
            "{\"isOpen\":false}",
            "{\"isOpen\":\"0\"}",
            "{\"isOpen\":\"false\"}",
            "{\"isOpen\":\"FALSE\"}"
    })
    void explicitClosedSuppressesIsparkOccupancy(String metadata) {
        assertThat(IsparkOccupancyPublicationPolicy.classifyStoredMetadata(metadata))
                .isEqualTo(OpenStatus.CLOSED);
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancy(
                MunicipalSourceIdentity.ISPARK, metadata)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"isOpen\":1}",
            "{\"isOpen\":true}",
            "{\"isOpen\":\"1\"}",
            "{\"isOpen\":\"true\"}",
            "{\"isOpen\":\"TRUE\"}"
    })
    void explicitOpenDoesNotSuppressIsparkOccupancy(String metadata) {
        assertThat(IsparkOccupancyPublicationPolicy.classifyStoredMetadata(metadata))
                .isEqualTo(OpenStatus.OPEN);
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancy(
                MunicipalSourceIdentity.ISPARK, metadata)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "{}", "{\"district\":\"AVCILAR\"}", "{\"isOpen\":null}"})
    void missingOrNullOpenStatusIsUnknownAndSuppressed(String metadata) {
        assertThat(IsparkOccupancyPublicationPolicy.classifyStoredMetadata(metadata))
                .isEqualTo(OpenStatus.UNKNOWN);
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancy(
                MunicipalSourceIdentity.ISPARK, metadata)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"isOpen\":2}",
            "{\"isOpen\":\"maybe\"}",
            "{\"isOpen\":\"open\"}",
            "{\"isOpen\":\"closed\"}",
            "not-json",
            "[0]"
    })
    void unrecognizedOpenStatusIsUnknownAndSuppressed(String metadata) {
        assertThat(IsparkOccupancyPublicationPolicy.classifyStoredMetadata(metadata))
                .isEqualTo(OpenStatus.UNKNOWN);
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancy(
                MunicipalSourceIdentity.ISPARK, metadata)).isTrue();
    }

    @Test
    void workHoursTextIsNeverUsedToInferClosure() {
        String openWithClosedHours = "{\"isOpen\":1,\"workHours\":\"Kapalı\"}";
        String closedWithHours = "{\"isOpen\":0,\"workHours\":\"24 Saat\"}";
        assertThat(IsparkOccupancyPublicationPolicy.classifyStoredMetadata(openWithClosedHours))
                .isEqualTo(OpenStatus.OPEN);
        assertThat(IsparkOccupancyPublicationPolicy.classifyStoredMetadata(closedWithHours))
                .isEqualTo(OpenStatus.CLOSED);
    }

    @Test
    void izumAndOsmPublishingKeysAreNeverSuppressed() {
        String closed = "{\"isOpen\":0}";
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancy(
                MunicipalSourceIdentity.IZUM, closed)).isFalse();
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancy(
                MunicipalSourceIdentity.OSM, closed)).isFalse();
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancyForLinkedSources(
                Set.of(MunicipalSourceIdentity.IZUM), closed)).isFalse();
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancyForLinkedSources(
                Set.of(MunicipalSourceIdentity.OSM), closed)).isFalse();
    }

    @Test
    void izumPlusIsparkStillWithholdsIsparkOccupancy() {
        Set<String> mixed = Set.of(MunicipalSourceIdentity.IZUM, MunicipalSourceIdentity.ISPARK);
        assertThat(IsparkOccupancyPublicationPolicy.mustWithholdIsparkOccupancy(
                mixed, "{\"isOpen\":0}")).isTrue();
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancyForLinkedSources(
                mixed, "{\"isOpen\":0}")).isFalse();
    }

    @Test
    void isparkOnlyLinkedSourcesHonorClosedMetadata() {
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancyForLinkedSources(
                Set.of(MunicipalSourceIdentity.ISPARK), "{\"isOpen\":0}")).isTrue();
        assertThat(IsparkOccupancyPublicationPolicy.suppressOccupancyForLinkedSources(
                Set.of(MunicipalSourceIdentity.ISPARK), "{\"isOpen\":1}")).isFalse();
    }
}
