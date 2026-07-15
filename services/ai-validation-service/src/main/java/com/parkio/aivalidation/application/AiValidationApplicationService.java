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
 * <p>ai-validation-service remains an <strong>advisor</strong> toward moderation
 * (ai-context/02): it does not mutate parking rows directly. Parking-service consumes
 * {@link AiValidationCompletedEvent} and enforces the publication gate
 * (PENDING_VALIDATION → ACTIVE / PENDING_REVIEW / REJECTED). The validator is a
 * deterministic placeholder with a fail-closed {@link com.parkio.aivalidation.domain.ContentRiskClassifier}
 * — no real vision model is called until a production adapter is wired.
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
        if (!claimEvent(event.eventId(), "MediaUploaded")) {
            return;
        }
        runValidation(event.mediaId(), null, null);
    }

    /** A newly created spot's photo is analysed; the result is linked to the spot. */
    public void handleParkingSpotCreated(ParkingSpotCreatedEvent event) {
        if (!claimEvent(event.eventId(), "ParkingSpotCreated")) {
            return;
        }
        runValidation(event.mediaId(), event.parkingSpotId(), null);
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

    private boolean claimEvent(UUID eventId, String eventType) {
        return inbox.tryClaim(eventId, eventType, clock.instant());
    }
}
