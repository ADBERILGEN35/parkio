package com.parkio.analytics.presentation.dto;

import com.parkio.analytics.application.result.OverviewView;

/** Platform KPI overview (lifetime totals). */
public record OverviewResponse(
        long totalParkingCreated,
        long totalParkingVerified,
        long totalParkingClaimed,
        long totalParkingRejected,
        long totalPointsEarned,
        long totalLevelUps,
        long totalNotificationsCreated) {

    public static OverviewResponse from(OverviewView v) {
        return new OverviewResponse(v.totalParkingCreated(), v.totalParkingVerified(), v.totalParkingClaimed(),
                v.totalParkingRejected(), v.totalPointsEarned(), v.totalLevelUps(), v.totalNotificationsCreated());
    }
}
