package com.parkio.aivalidation.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code inbox_events} (processed-message dedupe). */
@Entity
@Table(name = "inbox_events")
public class InboxEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected InboxEventEntity() {
        // for JPA
    }

    public InboxEventEntity(UUID id, String eventType, Instant processedAt) {
        this.id = id;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
