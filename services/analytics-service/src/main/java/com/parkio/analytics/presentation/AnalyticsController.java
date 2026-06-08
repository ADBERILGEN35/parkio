package com.parkio.analytics.presentation;

import com.parkio.analytics.application.AnalyticsApplicationService;
import com.parkio.analytics.domain.exception.AnalyticsErrorCode;
import com.parkio.analytics.domain.exception.AnalyticsException;
import com.parkio.analytics.presentation.dto.DailySnapshotResponse;
import com.parkio.analytics.presentation.dto.MetricResponse;
import com.parkio.analytics.presentation.dto.OverviewResponse;
import com.parkio.analytics.presentation.dto.ParkingSnapshotResponse;
import com.parkio.analytics.presentation.dto.UserSnapshotResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Analytics API. Translates HTTP into application calls and domain objects into
 * response DTOs — JPA entities never cross this boundary.
 *
 * <p>Aggregate endpoints (overview/daily/parking/metrics) are not user-specific. The
 * personal endpoint ({@code /users/{userId}}) reads the gateway-injected
 * {@code X-User-Id}, fails closed if it's absent/invalid, and only lets a user view
 * their own analytics.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final AnalyticsApplicationService analyticsService;

    public AnalyticsController(AnalyticsApplicationService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    public OverviewResponse getOverview() {
        return OverviewResponse.from(analyticsService.getOverview());
    }

    @GetMapping("/daily")
    public List<DailySnapshotResponse> getDaily() {
        return analyticsService.getDailySnapshots().stream().map(DailySnapshotResponse::from).toList();
    }

    @GetMapping("/users/{userId}")
    public List<UserSnapshotResponse> getUserAnalytics(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader,
            @PathVariable("userId") UUID userId) {
        UUID authenticatedUserId = requireUserId(userIdHeader);
        if (!authenticatedUserId.equals(userId)) {
            throw new AnalyticsException(AnalyticsErrorCode.FORBIDDEN, "You may only view your own analytics.");
        }
        return analyticsService.getUserAnalytics(userId).stream().map(UserSnapshotResponse::from).toList();
    }

    @GetMapping("/parking")
    public List<ParkingSnapshotResponse> getParking() {
        return analyticsService.getParkingAnalytics().stream().map(ParkingSnapshotResponse::from).toList();
    }

    @GetMapping("/metrics")
    public List<MetricResponse> getMetrics() {
        return analyticsService.getMetrics().stream().map(MetricResponse::from).toList();
    }

    /** Resolves the authenticated user id from the header; fails closed if absent/invalid. */
    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new AnalyticsException(AnalyticsErrorCode.MISSING_USER_ID, "Missing authenticated user id.");
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new AnalyticsException(AnalyticsErrorCode.MISSING_USER_ID, "Invalid authenticated user id.");
        }
    }
}
