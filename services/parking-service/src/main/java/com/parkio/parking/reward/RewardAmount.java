package com.parkio.parking.reward;

/** Non-negative integer reward amount. */
public record RewardAmount(int value) {

    public RewardAmount {
        if (value < 0) {
            throw new IllegalArgumentException("RewardAmount must be non-negative");
        }
    }

    public static RewardAmount zero() {
        return new RewardAmount(0);
    }

    public boolean isZero() {
        return value == 0;
    }
}
