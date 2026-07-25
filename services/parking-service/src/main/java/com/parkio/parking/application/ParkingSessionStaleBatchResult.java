package com.parkio.parking.application;

/**
 * One page of stale-session scheduler work.
 *
 * @param examined candidate rows loaded for this page (0 means the page was empty)
 * @param succeeded successful completions / reminders / deletions in this page
 */
public record ParkingSessionStaleBatchResult(int examined, int succeeded) {

    public static ParkingSessionStaleBatchResult empty() {
        return new ParkingSessionStaleBatchResult(0, 0);
    }

    /** True when there is no further work for this drain (empty page or no progress). */
    public boolean exhausted() {
        return examined == 0 || succeeded == 0;
    }
}