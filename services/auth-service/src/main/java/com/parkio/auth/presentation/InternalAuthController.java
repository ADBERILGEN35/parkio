package com.parkio.auth.presentation;

import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.presentation.dto.SessionEpochResponse;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, service-to-service endpoint for the gateway's per-request access-token
 * revocation check. <strong>Not</strong> part of the public {@code /api/v1} surface:
 * the gateway routes only {@code /api/v1/**} externally, so {@code /internal/**} is
 * reachable only on the internal network and is additionally guarded by the shared
 * {@code X-Gateway-Auth} secret (ai-context/07 — only the gateway is public).
 *
 * <p>Returns only {@code userId} + the current {@code sessionEpoch}. The gateway
 * compares the epoch in the caller's access-token claim against this current value and
 * rejects the request if the token's epoch is stale (token revoked by logout-all,
 * reuse detection, suspension, ...). An unknown id yields {@code 404} (via
 * {@code USER_NOT_FOUND}), which the gateway treats as fail-closed.
 */
@Hidden
@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {

    private final AuthApplicationService authService;

    public InternalAuthController(AuthApplicationService authService) {
        this.authService = authService;
    }

    @GetMapping("/users/{userId}/session-epoch")
    public SessionEpochResponse sessionEpoch(@PathVariable("userId") UUID userId) {
        return new SessionEpochResponse(userId, authService.sessionEpoch(userId));
    }
}
