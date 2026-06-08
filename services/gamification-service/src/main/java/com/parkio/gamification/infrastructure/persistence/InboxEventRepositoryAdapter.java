package com.parkio.gamification.infrastructure.persistence;

import com.parkio.gamification.application.port.InboxEventRepository;
import com.parkio.gamification.infrastructure.persistence.entity.InboxEventEntity;
import com.parkio.gamification.infrastructure.persistence.jpa.InboxEventJpaRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link InboxEventRepository} port to Spring Data JPA. */
@Component
public class InboxEventRepositoryAdapter implements InboxEventRepository {

    private final InboxEventJpaRepository jpa;

    public InboxEventRepositoryAdapter(InboxEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existsByEventId(UUID eventId) {
        return jpa.existsById(eventId);
    }

    @Override
    public void markProcessed(UUID eventId, String eventType, Instant processedAt) {
        jpa.save(new InboxEventEntity(eventId, eventType, processedAt));
    }
}
