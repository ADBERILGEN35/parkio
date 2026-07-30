package com.parkio.parking.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class RegistryCandidatePublicApiRegressionTest {
    @Test
    void municipalFacilityResponseDoesNotExposeCandidateOrGenerationRunFields() {
        var publicFieldNames = Arrays.stream(MunicipalFacilityResponse.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase(Locale.ROOT))
                .toList();

        assertThat(publicFieldNames)
                .noneMatch(name -> name.contains("candidate")
                        || name.contains("linkcandidate")
                        || name.contains("generationrun"));
    }
}
