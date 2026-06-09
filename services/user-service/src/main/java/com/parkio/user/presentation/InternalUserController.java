package com.parkio.user.presentation;

import com.parkio.user.application.UserApplicationService;
import com.parkio.user.presentation.dto.UserStatusResponse;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, service-to-service endpoint for the gateway's per-request account-status
 * enforcement. <strong>Not</strong> part of the public {@code /api/v1} surface: the
 * gateway routes only {@code /api/v1/**} externally, so {@code /internal/**} is
 * reachable only on the internal network (ai-context/07 — only the gateway is public;
 * downstream services must not be publicly exposed).
 *
 * <p>It returns only {@code userId} + {@code status} ({@link UserStatusResponse}) — no
 * private profile data. A missing profile yields {@code 404} (via the standard
 * {@link GlobalExceptionHandler} mapping of {@code PROFILE_NOT_FOUND}), which the
 * gateway treats as a non-active account (fail closed).
 */
@Hidden
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    private final UserApplicationService userService;

    public InternalUserController(UserApplicationService userService) {
        this.userService = userService;
    }

    @GetMapping("/{authUserId}/status")
    public UserStatusResponse getStatus(@PathVariable("authUserId") UUID authUserId) {
        return UserStatusResponse.from(userService.getAccountStatus(authUserId));
    }
}
