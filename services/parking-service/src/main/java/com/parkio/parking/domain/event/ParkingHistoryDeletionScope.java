package com.parkio.parking.domain.event;

/** Identifies whether terminal history was removed as one session or as a bulk clear. */
public enum ParkingHistoryDeletionScope {
    SINGLE_TERMINAL_SESSION,
    ALL_TERMINAL_HISTORY
}