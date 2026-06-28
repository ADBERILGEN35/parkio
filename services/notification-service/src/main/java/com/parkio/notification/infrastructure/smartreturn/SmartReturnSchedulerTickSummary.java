package com.parkio.notification.infrastructure.smartreturn;

public record SmartReturnSchedulerTickSummary(
        boolean enabled,
        int eligibleUsers,
        int promptedUsers,
        int returnChecksClaimed,
        int claimRetries,
        int noSpots,
        int notificationsCreated,
        int failures) {

    public static SmartReturnSchedulerTickSummary disabled() {
        return new SmartReturnSchedulerTickSummary(false, 0, 0, 0, 0, 0, 0, 0);
    }
}
