package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

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
}
