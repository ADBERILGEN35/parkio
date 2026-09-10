package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.auth.domain.RegistrationMode;
import com.parkio.auth.infrastructure.config.RegistrationProperties;
import org.junit.jupiter.api.Test;

class RegistrationModeControllerTest {
    @Test
    void exposesOnlyTheSafeRegistrationMode() {
        RegistrationProperties properties = new RegistrationProperties();
        properties.setMode(RegistrationMode.CLOSED);

        var response = new RegistrationModeController(properties).get();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().mode()).isEqualTo(RegistrationMode.CLOSED);
        assertThat(response.getHeaders().getCacheControl()).contains("public", "max-age=30");
        assertThat(response.getBody().getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("mode");
    }
}
