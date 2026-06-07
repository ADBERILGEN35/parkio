package com.parkio.user.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Inbound event DTO mirroring the payload shape published by auth-service's
 * outbox ({@code UserRegistered}). Duplicated locally on purpose — services
 * never share a domain/contract module (ai-context/01). When a Kafka consumer
 * is wired, it will deserialize the payload into this record and hand it to
 * {@code UserApplicationService.handleUserRegistered}.
 */
public record UserRegisteredEvent(
        UUID eventId,
        UUID userId,
        String email,
        Instant occurredAt) {

    public static final String TYPE = "UserRegistered";
}
