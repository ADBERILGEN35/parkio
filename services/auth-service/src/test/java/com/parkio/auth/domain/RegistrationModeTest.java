package com.parkio.auth.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RegistrationModeTest {

    @Test
    void parseAcceptsCaseInsensitiveValues() {
        org.assertj.core.api.Assertions.assertThat(RegistrationMode.parse("closed")).isEqualTo(RegistrationMode.CLOSED);
        org.assertj.core.api.Assertions.assertThat(RegistrationMode.parse("INVITE")).isEqualTo(RegistrationMode.INVITE);
        org.assertj.core.api.Assertions.assertThat(RegistrationMode.parse(" open ")).isEqualTo(RegistrationMode.OPEN);
    }

    @Test
    void parseRejectsUnknownValues() {
        assertThatThrownBy(() -> RegistrationMode.parse("public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown parkio.registration.mode");
    }
}
