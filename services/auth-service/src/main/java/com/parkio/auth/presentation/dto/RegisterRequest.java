package com.parkio.auth.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration request. Validation happens here at the edge; the application
 * layer receives an already-validated command. Email is the sole login
 * identifier — auth-service holds no profile data such as a phone number
 * (ai-context/03).
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 12, max = 100, message = "must be at least 12 characters") String password) {
}
