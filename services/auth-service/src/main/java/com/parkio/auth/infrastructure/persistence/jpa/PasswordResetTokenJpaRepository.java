package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.infrastructure.persistence.entity.PasswordResetTokenEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenJpaRepository extends JpaRepository<PasswordResetTokenEntity, UUID> {

    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PasswordResetTokenEntity token
               set token.consumedAt = :consumedAt,
                   token.version = token.version + 1
             where token.userId = :userId
               and token.consumedAt is null
               and token.expiresAt > :consumedAt
            """)
    int consumeActiveForUser(@Param("userId") UUID userId, @Param("consumedAt") Instant consumedAt);
}
