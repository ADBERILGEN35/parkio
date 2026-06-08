package com.parkio.analytics.application.result;

/** The platform KPI overview (lifetime totals). */
public record OverviewView(
        long totalParkingCreated,
        long totalParkingVerified,
        long totalParkingClaimed,
        long totalParkingRejected,
        long totalPointsEarned,
        long totalLevelUps,
        long totalNotificationsCreated) {
}
