package com.parkio.auth.presentation;

import com.parkio.auth.application.RegistrationInviteService;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.config.RegistrationProperties;
import com.parkio.auth.presentation.dto.CreateRegistrationInviteRequest;
import com.parkio.auth.presentation.dto.RegistrationInviteResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal/auth")
public class InternalRegistrationInviteController {

    public static final String OPERATOR_TOKEN_HEADER = "X-Parkio-Registration-Invite-Operator-Token";

    private final RegistrationInviteService registrationInviteService;
    private final RegistrationProperties registrationProperties;
    private final Clock clock;
    private final byte[] operatorToken;

    public InternalRegistrationInviteController(
            RegistrationInviteService registrationInviteService,
            RegistrationProperties registrationProperties,
            Clock clock) {
        this.registrationInviteService = registrationInviteService;
        this.registrationProperties = registrationProperties;
        this.clock = clock;
        this.operatorToken = StringUtils.hasText(registrationProperties.getInviteOperatorToken())
                ? registrationProperties.getInviteOperatorToken().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
    }

    @PostMapping("/registration-invites")
    public ResponseEntity<RegistrationInviteResponse> createInvite(
            @RequestHeader(value = OPERATOR_TOKEN_HEADER, required = false) String providedOperatorToken,
            @Valid @RequestBody(required = false) CreateRegistrationInviteRequest request) {
        if (!registrationProperties.isInviteCreationEnabled()) {
            throw new AuthException(AuthErrorCode.FORBIDDEN, "Registration invite creation is disabled.");
        }
        if (!tokenMatches(providedOperatorToken)) {
            throw new AuthException(AuthErrorCode.FORBIDDEN, "Invalid registration invite operator token.");
        }
        String createdBy = request != null && StringUtils.hasText(request.createdBy())
                ? request.createdBy()
                : "operator";
        String rawToken = registrationInviteService.createInvite(createdBy);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegistrationInviteResponse(rawToken, clock.instant().plus(registrationProperties.getInviteTtl())));
    }

    private boolean tokenMatches(String provided) {
        if (provided == null || operatorToken.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), operatorToken);
    }
}
