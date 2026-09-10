package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RegistryPropertiesTest {
    @Test
    void linkingAndGenerationDefaultOffWhileProvenancePublicationDefaultsOn() {
        RegistryProperties properties = new RegistryProperties();
        assertThat(properties.isCandidateGenerationEnabled()).isFalse();
        assertThat(properties.isReviewApiEnabled()).isFalse();
        assertThat(properties.isReviewedLinkingEnabled()).isFalse();
        assertThat(properties.isAutomaticLinkingEnabled()).isFalse();
        assertThat(properties.isProvenancePublicationEnabled()).isTrue();
        assertThat(properties.isProvenanceIngestWriteEnabled()).isTrue();
    }

    @Test
    void automaticLinkingCannotBeEnabled() {
        RegistryProperties properties = new RegistryProperties();
        assertThatThrownBy(() -> properties.setAutomaticLinkingEnabled(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prohibited");
        assertThat(properties.isAutomaticLinkingEnabled()).isFalse();
    }

    @Test
    void explicitFalseDisablesProvenancePublicationKillSwitch() {
        RegistryProperties properties = new RegistryProperties();
        properties.setProvenancePublicationEnabled(false);
        assertThat(properties.isProvenancePublicationEnabled()).isFalse();
        assertThat(properties.isProvenanceIngestWriteEnabled()).isTrue();
    }
}
