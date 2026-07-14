package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.parkio.auth.application.admin.AdminApplicationService;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.presentation.dto.admin.BootstrapSuperAdminRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Security contract of the one-time SUPER_ADMIN bootstrap endpoint: disabled by
 * default, fail-closed on missing/wrong/unconfigured token, and delegating to the
 * application service only when both the flag and the exact token match.
 */
class InternalAdminBootstrapControllerTest {

    private static final String TOKEN = "s3cret-bootstrap-token";

    private final AdminApplicationService adminService = mock(AdminApplicationService.class);
    private final BootstrapSuperAdminRequest request = new BootstrapSuperAdminRequest("founder@parkio.dev");

    @Test
    void rejectsWhenDisabledEvenWithCorrectToken() {
        InternalAdminBootstrapController controller =
                new InternalAdminBootstrapController(adminService, false, TOKEN);

        assertThatThrownBy(() -> controller.bootstrapSuperAdmin(TOKEN, request))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.BOOTSTRAP_DISABLED);
        verifyNoInteractions(adminService);
    }

    @Test
    void rejectsMissingToken() {
        InternalAdminBootstrapController controller =
                new InternalAdminBootstrapController(adminService, true, TOKEN);

        assertThatThrownBy(() -> controller.bootstrapSuperAdmin(null, request))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.FORBIDDEN);
        verifyNoInteractions(adminService);
    }

    @Test
    void rejectsWrongToken() {
        InternalAdminBootstrapController controller =
                new InternalAdminBootstrapController(adminService, true, TOKEN);

        assertThatThrownBy(() -> controller.bootstrapSuperAdmin("wrong-token", request))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.FORBIDDEN);
        verifyNoInteractions(adminService);
    }

    @Test
    void rejectsAnyTokenWhenNoneConfigured() {
        InternalAdminBootstrapController controller =
                new InternalAdminBootstrapController(adminService, true, "");

        assertThatThrownBy(() -> controller.bootstrapSuperAdmin("", request))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.FORBIDDEN);
        verifyNoInteractions(adminService);
    }

    @Test
    void delegatesWhenEnabledAndTokenMatches() {
        InternalAdminBootstrapController controller =
                new InternalAdminBootstrapController(adminService, true, TOKEN);

        var response = controller.bootstrapSuperAdmin(TOKEN, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(adminService).bootstrapSuperAdmin("founder@parkio.dev");
    }
}
