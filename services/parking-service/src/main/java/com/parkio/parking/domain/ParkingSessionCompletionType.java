package com.parkio.parking.domain;

/**
 * How a parking session reached a terminal COMPLETED (or CANCELLED) state.
 *
 * <p>{@code MANUAL} covers user-driven complete/cancel (including "I already left").
 * {@code AUTO} is reserved for scheduler-driven completion of forgotten sessions.
 * CANCELLED sessions are always {@code MANUAL}. ACTIVE sessions have no completion type.
 */
public enum ParkingSessionCompletionType {
    MANUAL,
    AUTO
}