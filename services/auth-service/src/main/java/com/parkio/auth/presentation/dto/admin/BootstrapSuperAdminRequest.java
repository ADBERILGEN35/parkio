package com.parkio.auth.presentation.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record BootstrapSuperAdminRequest(@NotBlank @Email String email) {
}
