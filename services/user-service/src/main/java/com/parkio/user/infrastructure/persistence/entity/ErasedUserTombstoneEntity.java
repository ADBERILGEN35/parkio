package com.parkio.user.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "erased_user_tombstones")
public class ErasedUserTombstoneEntity {

    @Id
    @Column(name = "auth_user_id", nullable = false)
    private UUID authUserId;

    @Column(name = "erased_at", nullable = false)
    private Instant erasedAt;

    protected ErasedUserTombstoneEntity() {
    }

    public ErasedUserTombstoneEntity(UUID authUserId, Instant erasedAt) {
        this.authUserId = authUserId;
        this.erasedAt = erasedAt;
    }

    public UUID getAuthUserId() {
        return authUserId;
    }
}
