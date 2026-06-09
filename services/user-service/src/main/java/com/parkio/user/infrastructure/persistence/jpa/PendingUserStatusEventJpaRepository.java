package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.PendingUserStatusEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingUserStatusEventJpaRepository
        extends JpaRepository<PendingUserStatusEventEntity, UUID> {

    List<PendingUserStatusEventEntity> findByAuthUserId(UUID authUserId);

    void deleteByAuthUserId(UUID authUserId);
}
