package com.parkio.auth.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Refuses to start with invite creation enabled and no real operator token (#287 review I3): an empty
 * token, an example-env placeholder, or one shorter than {@value InviteOperatorTokenPolicy#MIN_LENGTH}
 * characters ({@link InviteOperatorTokenPolicy}). The message names the setting and the reason, never
 * the value.
 */
@Configuration
@EnableConfigurationProperties(RegistrationProperties.class)
public class InviteOperatorTokenStartupCheck {

    private final RegistrationProperties registrationProperties;

    public InviteOperatorTokenStartupCheck(RegistrationProperties registrationProperties) {
        this.registrationProperties = registrationProperties;
    }

    @PostConstruct
    void refuseInviteCreationWithoutRealOperatorToken() {
        if (!registrationProperties.isInviteCreationEnabled()) {
            return;
        }
        InviteOperatorTokenPolicy.refusal(registrationProperties.getInviteOperatorToken()).ifPresent(reason -> {
            throw new IllegalStateException("PARKIO_REGISTRATION_INVITE_CREATION_ENABLED=true requires a real "
                    + "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN (status " + reason + "): at least "
                    + InviteOperatorTokenPolicy.MIN_LENGTH + " characters and no example-env placeholder. "
                    + "Refusing to start.");
        });
    }
}
