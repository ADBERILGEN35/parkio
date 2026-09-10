package com.parkio.auth.presentation;

import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.application.result.AccountDeletionStatusView;
import com.parkio.auth.presentation.dto.AccountDeletionStatusResponse;
import com.parkio.auth.presentation.dto.DeleteAccountRequest;
import com.parkio.auth.presentation.openapi.StandardApiResponses;
import com.parkio.auth.shared.AuthPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Account", description = "Authenticated account deletion")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountErasureApplicationService erasure;

    public AccountController(AccountErasureApplicationService erasure) {
        this.erasure = erasure;
    }

    @Operation(summary = "Request account deletion", security = @SecurityRequirement(name = "bearer"))
    @DeleteMapping
    public AccountDeletionStatusResponse delete(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody DeleteAccountRequest request) {
        AccountDeletionStatusView view = erasure.requestDeletion(principal.userId(), request.password());
        return new AccountDeletionStatusResponse(view.erasureRequestId(), view.status());
    }

    @Operation(summary = "Account deletion status", security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/deletion-status")
    public ResponseEntity<AccountDeletionStatusResponse> status(
            @AuthenticationPrincipal AuthPrincipal principal) {
        AccountDeletionStatusView view = erasure.status(principal.userId());
        return ResponseEntity.ok(new AccountDeletionStatusResponse(view.erasureRequestId(), view.status()));
    }
}
