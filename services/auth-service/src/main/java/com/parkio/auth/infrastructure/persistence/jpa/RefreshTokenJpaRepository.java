package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    Optional<RefreshTokenEntity> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            SELECT token FROM RefreshTokenEntity token
             WHERE token.userId = :userId
               AND token.revoked = false
               AND token.expiresAt > :now
             ORDER BY token.familyStartedAt DESC
            """)
    List<RefreshTokenEntity> findActiveSessionsForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query("""
            SELECT COUNT(token) FROM RefreshTokenEntity token
             WHERE token.userId = :userId
               AND token.revoked = false
               AND token.expiresAt > :now
            """)
    long countActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query("""
            SELECT COUNT(token) FROM RefreshTokenEntity token
             WHERE token.revoked = false
               AND token.expiresAt > :now
            """)
    long countAllActive(@Param("now") Instant now);

    long countByReusedDetectedTrue();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
               set token.revoked = true,
                   token.revokedReason = :reason,
                   token.revokedAt = :revokedAt,
                   token.version = token.version + 1
             where token.tokenFamilyId = :tokenFamilyId
               and token.revoked = false
            """)
    int revokeActiveFamily(
            @Param("tokenFamilyId") UUID tokenFamilyId,
            @Param("reason") RefreshTokenRevocationReason reason,
            @Param("revokedAt") Instant revokedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
               set token.revoked = true,
                   token.revokedReason = :reason,
                   token.revokedAt = :revokedAt,
                   token.version = token.version + 1
             where token.userId = :userId
               and token.revoked = false
            """)
    int revokeAllActiveForUser(
            @Param("userId") UUID userId,
            @Param("reason") RefreshTokenRevocationReason reason,
            @Param("revokedAt") Instant revokedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshTokenEntity token
               set token.revoked = true,
                   token.revokedReason = :reason,
                   token.revokedAt = :revokedAt,
                   token.version = token.version + 1
             where token.id = :sessionId
               and token.userId = :userId
               and token.revoked = false
            """)
    int revokeById(
            @Param("sessionId") UUID sessionId,
            @Param("userId") UUID userId,
            @Param("reason") RefreshTokenRevocationReason reason,
            @Param("revokedAt") Instant revokedAt);
}
