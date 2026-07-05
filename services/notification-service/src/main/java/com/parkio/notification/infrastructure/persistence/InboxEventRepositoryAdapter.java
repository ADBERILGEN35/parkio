package com.parkio.notification.infrastructure.persistence;

import com.parkio.notification.application.port.InboxEventRepository;
import com.parkio.notification.infrastructure.persistence.jpa.InboxEventJpaRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Adapts the {@link InboxEventRepository} port to Spring Data JPA. */
@Component
public class InboxEventRepositoryAdapter implements InboxEventRepository {

    private final InboxEventJpaRepository jpa;

    public InboxEventRepositoryAdapter(InboxEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public boolean tryClaim(UUID eventId, String eventType, Instant processedAt) {
        return jpa.insertIfAbsent(eventId, eventType, processedAt) > 0;
    }
}
