package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.RegistrationInviteService;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.config.RegistrationProperties;
import com.parkio.auth.presentation.dto.CreateRegistrationInviteRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InternalRegistrationInviteControllerTest {

    private static final String OPERATOR_TOKEN = "operator-secret";
    private static final Instant NOW = Instant.parse("2026-06-06T12:00:00Z");

    private final RegistrationInviteService registrationInviteService = mock(RegistrationInviteService.class);
    private final RegistrationProperties properties = new RegistrationProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void rejectsWhenInviteCreationDisabled() {
        properties.setInviteCreationEnabled(false);
        properties.setInviteOperatorToken(OPERATOR_TOKEN);
        InternalRegistrationInviteController controller = newController();

        assertThatThrownBy(() -> controller.createInvite(OPERATOR_TOKEN, null))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.FORBIDDEN);
        verifyNoInteractions(registrationInviteService);
    }

    @Test
    void rejectsMissingOperatorToken() {
        properties.setInviteCreationEnabled(true);
        properties.setInviteOperatorToken(OPERATOR_TOKEN);
        InternalRegistrationInviteController controller = newController();

        assertThatThrownBy(() -> controller.createInvite(null, null))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.FORBIDDEN);
        verifyNoInteractions(registrationInviteService);
    }

    @Test
    void createsInviteWhenEnabledAndTokenMatches() {
        properties.setInviteCreationEnabled(true);
        properties.setInviteOperatorToken(OPERATOR_TOKEN);
        properties.setInviteTtl(Duration.ofDays(7));
        when(registrationInviteService.createInvite("ops")).thenReturn("raw-invite-token");
        InternalRegistrationInviteController controller = newController();

        var response = controller.createInvite(OPERATOR_TOKEN, new CreateRegistrationInviteRequest("ops"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isEqualTo("raw-invite-token");
        assertThat(response.getBody().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        verify(registrationInviteService).createInvite("ops");
    }

    private InternalRegistrationInviteController newController() {
        return new InternalRegistrationInviteController(registrationInviteService, properties, clock);
    }
}
