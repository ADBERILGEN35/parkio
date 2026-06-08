package com.parkio.aivalidation.application;

import com.parkio.aivalidation.application.event.MediaUploadedEvent;
import com.parkio.aivalidation.application.event.ParkingSpotCreatedEvent;
import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.application.port.InboxEventRepository;
import com.parkio.aivalidation.application.port.OutboxEventAppender;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import com.parkio.aivalidation.domain.event.AiValidationCompletedEvent;
import com.parkio.aivalidation.domain.exception.AiValidationErrorCode;
import com.parkio.aivalidation.domain.exception.AiValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advisory validation use cases: react to upstream events (idempotently) and serve
 * manual validation requests, persisting a result and emitting an advisory event.
 *
 * <p>ai-validation-service is an <strong>advisor</strong> (ai-context/02): it never
 * rejects a spot, bans a user, or mutates another service's data (ai-context/03). It
 * records a result and emits {@link AiValidationCompletedEvent} for moderation/parking
 * to consider. The validator is a deterministic placeholder — no real model is called.
 */
@Service
@Transactional
public class AiValidationApplicationService {

    private final AiValidationResultRepository results;
    private final InboxEventRepository inbox;
    private final OutboxEventAppender outbox;
    private final DeterministicAiValidator validator;
    private final Clock clock;

    public AiValidationApplicationService(AiValidationResultRepository results,
                                          InboxEventRepository inbox,
                                          OutboxEventAppender outbox,
                                          DeterministicAiValidator validator,
                                          Clock clock) {
        this.results = results;
        this.inbox = inbox;
        this.outbox = outbox;
        this.validator = validator;
        this.clock = clock;
    }

    // --- Event handlers (invoked directly for now; a Kafka consumer will call them) ---

    /** A newly uploaded media object is analysed for an advisory result. */
    public void handleMediaUploaded(MediaUploadedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        runValidation(event.mediaId(), null, null);
        markProcessed(event.eventId(), "MediaUploaded");
    }

    /** A newly created spot's photo is analysed; the result is linked to the spot. */
    public void handleParkingSpotCreated(ParkingSpotCreatedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        runValidation(event.mediaId(), event.parkingSpotId(), null);
        markProcessed(event.eventId(), "ParkingSpotCreated");
    }

    // --- Manual (moderator/admin) use case ---

    /** Runs a manual placeholder validation requested by a moderator/admin. */
    public AiValidationResult createManualValidation(UUID mediaId, UUID parkingSpotId, UUID requestedByUserId) {
        return runValidation(mediaId, parkingSpotId, requestedByUserId);
    }

    // --- Queries ---

    public AiValidationResult getById(UUID validationId) {
        return results.findById(validationId)
                .orElseThrow(() -> new AiValidationException(AiValidationErrorCode.VALIDATION_RESULT_NOT_FOUND));
    }

    public List<AiValidationResult> getByMediaId(UUID mediaId) {
        return results.findByMediaId(mediaId);
    }

    public List<AiValidationResult> getByParkingSpotId(UUID parkingSpotId) {
        return results.findByParkingSpotId(parkingSpotId);
    }

    // --- Internals ---

    private AiValidationResult runValidation(UUID mediaId, UUID parkingSpotId, UUID requestedByUserId) {
        Instant now = clock.instant();
        AiValidationResult result = results.save(
                validator.validate(mediaId, parkingSpotId, requestedByUserId, now));
        outbox.append(AiValidationCompletedEvent.of(result, now));
        return result;
    }

    private boolean alreadyProcessed(UUID eventId) {
        return inbox.existsByEventId(eventId);
    }

    private void markProcessed(UUID eventId, String eventType) {
        inbox.markProcessed(eventId, eventType, clock.instant());
    }
}
