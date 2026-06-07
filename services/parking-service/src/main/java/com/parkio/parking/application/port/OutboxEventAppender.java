package com.parkio.parking.application.port;

import com.parkio.parking.domain.event.ParkingEvent;

/**
 * Port for appending domain events to the transactional outbox. The implementation
 * must enlist in the caller's transaction so the event is persisted atomically with
 * the state change (ai-context/06).
 */
public interface OutboxEventAppender {

    void append(ParkingEvent event);
}
