package com.parkio.parking.domain;

/**
 * Progressive reminder stages for ACTIVE parking sessions.
 * NONE → first reminder (confirm-after) → second reminder (reminder-2) → auto-complete.
 */
public enum ParkingSessionReminderStage {
    NONE(0),
    FIRST(1),
    SECOND(2);

    private final int wireValue;

    ParkingSessionReminderStage(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public static ParkingSessionReminderStage fromWire(int value) {
        return switch (value) {
            case 0 -> NONE;
            case 1 -> FIRST;
            case 2 -> SECOND;
            default -> throw new IllegalArgumentException("Unknown reminder stage: " + value);
        };
    }

    public boolean hasReached(ParkingSessionReminderStage other) {
        return this.wireValue >= other.wireValue;
    }
}