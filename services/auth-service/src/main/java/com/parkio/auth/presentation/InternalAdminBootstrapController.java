package com.parkio.auth.presentation;

import com.parkio.auth.application.admin.AdminApplicationService;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.presentation.dto.admin.BootstrapSuperAdminRequest;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal/auth/admin")
public class InternalAdminBootstrapController {

    public static final String BOOTSTRAP_TOKEN_HEADER = "X-Parkio-Admin-Bootstrap-Token";

    private final AdminApplicationService adminService;
    private final boolean bootstrapEnabled;
    private final byte[] bootstrapToken;

    public InternalAdminBootstrapController(
            AdminApplicationService adminService,
            @Value("${parkio.admin.bootstrap.enabled:false}") boolean bootstrapEnabled,
            @Value("${parkio.admin.bootstrap.token:}") String bootstrapToken) {
        this.adminService = adminService;
        this.bootstrapEnabled = bootstrapEnabled;
        this.bootstrapToken = StringUtils.hasText(bootstrapToken)
                ? bootstrapToken.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
    }

    @PostMapping("/bootstrap-super-admin")
    public ResponseEntity<Void> bootstrapSuperAdmin(
            @RequestHeader(value = BOOTSTRAP_TOKEN_HEADER, required = false) String providedToken,
            @Valid @RequestBody BootstrapSuperAdminRequest request) {
        if (!bootstrapEnabled) {
            throw new AuthException(AuthErrorCode.BOOTSTRAP_DISABLED);
        }
        if (!tokenMatches(providedToken)) {
            throw new AuthException(AuthErrorCode.FORBIDDEN, "Invalid bootstrap token.");
        }
        adminService.bootstrapSuperAdmin(request.email());
        return ResponseEntity.noContent().build();
    }

    private boolean tokenMatches(String provided) {
        if (provided == null || bootstrapToken.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), bootstrapToken);
    }
}
