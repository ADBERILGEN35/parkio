package com.parkio.analytics.presentation;

import com.parkio.analytics.application.AnalyticsApplicationService;
import com.parkio.analytics.domain.exception.AnalyticsErrorCode;
import com.parkio.analytics.domain.exception.AnalyticsException;
import com.parkio.analytics.presentation.dto.DailySnapshotResponse;
import com.parkio.analytics.presentation.dto.MetricResponse;
import com.parkio.analytics.presentation.dto.OverviewResponse;
import com.parkio.analytics.presentation.dto.ParkingSnapshotResponse;
import com.parkio.analytics.presentation.dto.UserSnapshotResponse;
import com.parkio.analytics.presentation.openapi.StandardApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Analytics API. Translates HTTP into application calls and domain objects into
 * response DTOs — JPA entities never cross this boundary.
 *
 * <p>Aggregate endpoints (overview/daily/parking/metrics) are platform analytics and
 * are restricted to {@code ADMIN} via the gateway-injected {@code X-User-Roles} header
 * (separation of duties, ai-context/07 — moderators have no access). This controller
 * re-checks the role independently of the edge rule (defense in depth) and fails closed.
 * The personal endpoint ({@code /users/{userId}}) reads the gateway-injected
 * {@code X-User-Id}, fails closed if it's absent/invalid, and only lets a user view
 * their own analytics.
 */
@Tag(name = "Analytics", description = "Platform and user analytics snapshots")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROLES_HEADER = "X-User-Roles";
    private static final String ADMIN_ROLE = "ADMIN";

    private final AnalyticsApplicationService analyticsService;

    public AnalyticsController(AnalyticsApplicationService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Operation(summary = "Get platform overview")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/overview")
    public OverviewResponse getOverview(
            @RequestHeader(value = ROLES_HEADER, required = false) String roles) {
        requireAdmin(roles);
        return OverviewResponse.from(analyticsService.getOverview());
    }

    @Operation(summary = "Get daily snapshots")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/daily")
    public List<DailySnapshotResponse> getDaily(
            @RequestHeader(value = ROLES_HEADER, required = false) String roles) {
        requireAdmin(roles);
        return analyticsService.getDailySnapshots().stream().map(DailySnapshotResponse::from).toList();
    }

    @Operation(summary = "Get user analytics snapshots")
    @SecurityRequirement(name = "bearerAuth")
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

    @Operation(summary = "Get parking analytics snapshots")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/parking")
    public List<ParkingSnapshotResponse> getParking(
            @RequestHeader(value = ROLES_HEADER, required = false) String roles) {
        requireAdmin(roles);
        return analyticsService.getParkingAnalytics().stream().map(ParkingSnapshotResponse::from).toList();
    }

    @Operation(summary = "Get platform metrics")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/metrics")
    public List<MetricResponse> getMetrics(
            @RequestHeader(value = ROLES_HEADER, required = false) String roles) {
        requireAdmin(roles);
        return analyticsService.getMetrics().stream().map(MetricResponse::from).toList();
    }

    /** Requires the gateway-injected roles header to contain ADMIN; fails closed otherwise. */
    private static void requireAdmin(String rolesHeader) {
        Set<String> roles = rolesHeader == null || rolesHeader.isBlank()
                ? Set.of()
                : Arrays.stream(rolesHeader.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toSet());
        if (!roles.contains(ADMIN_ROLE)) {
            throw new AnalyticsException(AnalyticsErrorCode.FORBIDDEN, "Admin role required for platform analytics.");
        }
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
