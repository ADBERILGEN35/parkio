package com.parkio.auth.infrastructure.persistence.mapper;

import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.Role;
import com.parkio.auth.infrastructure.persistence.entity.AuthUserEntity;
import com.parkio.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Translates between domain models and JPA entities. Keeps the mapping in one
 * place so the adapters stay thin and the domain stays persistence-agnostic.
 */
public final class AuthPersistenceMapper {

    private AuthPersistenceMapper() {
    }

    public static Role toDomain(RoleEntity entity) {
        return new Role(entity.getId(), entity.getName());
    }

    public static RoleEntity toEntity(Role role) {
        return new RoleEntity(role.id(), role.name());
    }

    public static AuthUser toDomain(AuthUserEntity entity) {
        Set<Role> roles = entity.getRoles().stream()
                .map(AuthPersistenceMapper::toDomain)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new AuthUser(
                entity.getId(),
                entity.getEmail(),
                entity.getPasswordHash(),
                entity.getStatus(),
                roles,
                entity.getCreatedAt(),
                entity.getVersion());
    }

    public static AuthUserEntity toEntity(AuthUser user) {
        Set<RoleEntity> roles = user.roles().stream()
                .map(AuthPersistenceMapper::toEntity)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new AuthUserEntity(
                user.id(),
                user.email(),
                user.passwordHash(),
                user.status(),
                roles,
                user.createdAt(),
                user.version());
    }

    public static RefreshToken toDomain(RefreshTokenEntity entity) {
        return new RefreshToken(
                entity.getId(),
                entity.getUserId(),
                entity.getTokenHash(),
                entity.getExpiresAt(),
                entity.getTokenFamilyId(),
                entity.getParentTokenId(),
                entity.isRevoked(),
                entity.isReusedDetected(),
                entity.getRevokedReason(),
                entity.getRevokedAt(),
                entity.getVersion());
    }

    public static RefreshTokenEntity toEntity(RefreshToken token) {
        return new RefreshTokenEntity(
                token.id(),
                token.userId(),
                token.tokenHash(),
                token.expiresAt(),
                token.tokenFamilyId(),
                token.parentTokenId(),
                token.isRevoked(),
                token.isReusedDetected(),
                token.revokedReason(),
                token.revokedAt(),
                token.version());
    }
}
