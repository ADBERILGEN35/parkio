package com.parkio.auth.presentation.dto;

import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.Role;
import java.util.List;
import java.util.UUID;

/**
 * Public view of an authentication account. Deliberately omits
 * {@code passwordHash} and any token material.
 */
public record UserResponse(
        UUID id,
        String email,
        String status,
        List<String> roles) {

    public static UserResponse from(AuthUser user) {
        List<String> roles = user.roles().stream()
                .map(Role::name)
                .map(Enum::name)
                .toList();
        return new UserResponse(
                user.id(),
                user.email(),
                user.status().name(),
                roles);
    }
}
