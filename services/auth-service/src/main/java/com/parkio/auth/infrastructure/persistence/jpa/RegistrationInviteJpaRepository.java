package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.infrastructure.persistence.entity.RegistrationInviteEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistrationInviteJpaRepository extends JpaRepository<RegistrationInviteEntity, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RegistrationInviteEntity invite
               set invite.consumedAt = :consumedAt,
                   invite.version = invite.version + 1
             where invite.tokenHash = :tokenHash
               and invite.consumedAt is null
               and invite.expiresAt > :consumedAt
            """)
    int consumeIfValid(@Param("tokenHash") String tokenHash, @Param("consumedAt") Instant consumedAt);
}
