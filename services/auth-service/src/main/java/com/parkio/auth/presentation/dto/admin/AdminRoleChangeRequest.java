package com.parkio.auth.presentation.dto.admin;

import com.parkio.auth.domain.RoleName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminRoleChangeRequest(
        @NotNull RoleName role,
        @NotNull RoleAction action,
        String reason) {

    public enum RoleAction {
        GRANT,
        REVOKE
    }
}
