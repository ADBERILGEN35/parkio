package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.PendingUserStatusEventRepository;
import com.parkio.user.domain.PendingUserStatusEvent;
import com.parkio.user.infrastructure.persistence.jpa.PendingUserStatusEventJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.UserPersistenceMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link PendingUserStatusEventRepository} port to Spring Data JPA. */
@Component
public class PendingUserStatusEventRepositoryAdapter implements PendingUserStatusEventRepository {

    private final PendingUserStatusEventJpaRepository jpa;

    public PendingUserStatusEventRepositoryAdapter(PendingUserStatusEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(PendingUserStatusEvent event) {
        // PK is the moderation eventId: a redelivered event overwrites the
        // identical row instead of failing (idempotent).
        jpa.save(UserPersistenceMapper.toEntity(event));
    }

    @Override
    public List<PendingUserStatusEvent> findByAuthUserId(UUID authUserId) {
        return jpa.findByAuthUserId(authUserId).stream()
                .map(UserPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteByAuthUserId(UUID authUserId) {
        jpa.deleteByAuthUserId(authUserId);
    }
}
