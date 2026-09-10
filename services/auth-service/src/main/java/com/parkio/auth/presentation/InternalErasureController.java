package com.parkio.auth.presentation;

import io.swagger.v3.oas.annotations.Hidden;
import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.domain.event.UserErasureAcknowledgedEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal/erasure")
public class InternalErasureController {

    private final AccountErasureApplicationService erasure;

    public InternalErasureController(AccountErasureApplicationService erasure) {
        this.erasure = erasure;
    }

    public record AckRequest(
            UUID eventId,
            UUID erasureRequestId,
            UUID authUserId,
            String serviceName,
            String status,
            Instant occurredAt) {
    }

    @PostMapping("/acks")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ack(@RequestBody AckRequest body) {
        Instant occurred = body.occurredAt() == null ? Instant.now() : body.occurredAt();
        UUID eventId = body.eventId() == null ? UUID.randomUUID() : body.eventId();
        erasure.handleAcknowledgement(new UserErasureAcknowledgedEvent(
                eventId,
                body.erasureRequestId(),
                body.authUserId(),
                body.serviceName(),
                body.status(),
                occurred));
    }

    @PostMapping("/replay")
    public ReplayResponse replay() {
        return new ReplayResponse(erasure.replayTombstones());
    }

    public record ReplayResponse(int replayed) {
    }
}
