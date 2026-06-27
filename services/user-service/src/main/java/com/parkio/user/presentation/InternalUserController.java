package com.parkio.user.presentation;

import com.parkio.user.application.UserApplicationService;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.presentation.dto.SmartReturnCheckCandidateResponse;
import com.parkio.user.presentation.dto.SmartReturnPromptCandidateResponse;
import com.parkio.user.presentation.dto.UserStatusResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final boolean smartReturnEnabled;

    public InternalUserController(UserApplicationService userService,
                                  @Value("${parkio.smart-return.enabled:false}") boolean smartReturnEnabled) {
        this.userService = userService;
        this.smartReturnEnabled = smartReturnEnabled;
    }

    @GetMapping("/{authUserId}/status")
    public UserStatusResponse getStatus(@PathVariable("authUserId") UUID authUserId) {
        return UserStatusResponse.from(userService.getAccountStatus(authUserId));
    }

    @PostMapping("/smart-return/due-prompts")
    public List<SmartReturnPromptCandidateResponse> claimDueSmartReturnPrompts(
            @RequestParam("promptDate") LocalDate promptDate,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        requireSmartReturnEnabled();
        return userService.claimDueSmartReturnPrompts(promptDate, limit).stream()
                .map(SmartReturnPromptCandidateResponse::from)
                .toList();
    }

    @PostMapping("/smart-return/due-return-checks")
    public List<SmartReturnCheckCandidateResponse> claimDueSmartReturnChecks(
            @RequestParam("now") Instant now,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        requireSmartReturnEnabled();
        return userService.claimDueSmartReturnChecks(now, limit).stream()
                .map(SmartReturnCheckCandidateResponse::from)
                .toList();
    }

    @PostMapping("/smart-return/{authUserId}/notification-sent")
    public void markSmartReturnNotificationSent(@PathVariable("authUserId") UUID authUserId,
                                                @RequestParam("sentAt") Instant sentAt) {
        requireSmartReturnEnabled();
        userService.markSmartReturnNotificationSent(authUserId, sentAt);
    }

    @PostMapping("/smart-return/{authUserId}/return-check-completed")
    public void completeSmartReturnCheck(@PathVariable("authUserId") UUID authUserId,
                                         @RequestParam("notificationSent") boolean notificationSent,
                                         @RequestParam("completedAt") Instant completedAt) {
        requireSmartReturnEnabled();
        userService.completeSmartReturnCheck(authUserId, notificationSent, completedAt);
    }

    private void requireSmartReturnEnabled() {
        if (!smartReturnEnabled) {
            throw new UserException(UserErrorCode.SMART_RETURN_DISABLED);
        }
    }
}
