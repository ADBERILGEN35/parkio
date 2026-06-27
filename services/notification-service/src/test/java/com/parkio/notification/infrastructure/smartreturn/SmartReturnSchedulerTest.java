package com.parkio.notification.infrastructure.smartreturn;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.notification.application.NotificationApplicationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmartReturnSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-06-06T12:00:00Z");

    private SmartReturnUserClient users;
    private SmartReturnParkingClient parking;
    private NotificationApplicationService notifications;
    private SmartReturnScheduler scheduler;

    @BeforeEach
    void setUp() {
        users = mock(SmartReturnUserClient.class);
        parking = mock(SmartReturnParkingClient.class);
        notifications = mock(NotificationApplicationService.class);
        scheduler = new SmartReturnScheduler(users, parking, notifications,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(),
                true, true, "UTC", 100);
    }

    @Test
    void morningPromptClaimsAndCreatesPromptOnce() {
        UUID user = UUID.randomUUID();
        when(users.claimDuePrompts(LocalDate.of(2026, 6, 6), 100))
                .thenReturn(List.of(new SmartReturnUserClient.PromptCandidate(user)));

        scheduler.sendMorningPrompts();

        verify(notifications).createSmartReturnPrompt(user);
    }

    @Test
    void featureFlagOffPreventsSchedulerWork() {
        SmartReturnScheduler disabled = new SmartReturnScheduler(users, parking, notifications,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(),
                false, true, "UTC", 100);

        disabled.sendMorningPrompts();
        disabled.runReturnChecks();

        verify(users, never()).claimDuePrompts(LocalDate.of(2026, 6, 6), 100);
        verify(users, never()).claimDueReturnChecks(NOW, 100);
    }

    @Test
    void returnCheckWithNoSpotsCompletesWithoutNotification() {
        UUID user = UUID.randomUUID();
        SmartReturnUserClient.ReturnCheckCandidate candidate = candidate(user, false);
        when(users.claimDueReturnChecks(NOW, 100)).thenReturn(List.of(candidate));
        when(parking.searchNearby(user, 38.4237, 27.1428, 1000, 5)).thenReturn(List.of());

        scheduler.runReturnChecks();

        verify(notifications, never()).createSmartReturnParkingAvailable(user, "Konak");
        verify(users).completeReturnCheck(user, false, NOW);
    }

    @Test
    void returnCheckWithRealSpotCreatesNotificationAndCompletesSent() {
        UUID user = UUID.randomUUID();
        SmartReturnUserClient.ReturnCheckCandidate candidate = candidate(user, true);
        when(users.claimDueReturnChecks(NOW, 100)).thenReturn(List.of(candidate));
        when(parking.searchNearby(user, 38.4237, 27.1428, 1000, 5))
                .thenReturn(List.of(new SmartReturnParkingClient.NearbySpot(
                        UUID.randomUUID(), "Konak", "AVAILABLE", NOW.plusSeconds(900))));

        scheduler.runReturnChecks();

        verify(notifications).createSmartReturnParkingAvailable(user, "Konak");
        verify(users).completeReturnCheck(user, true, NOW);
    }

    private static SmartReturnUserClient.ReturnCheckCandidate candidate(UUID user, boolean claimRetried) {
        return new SmartReturnUserClient.ReturnCheckCandidate(
                user, 38.4237, 27.1428, "Saved home area", 1000, NOW.plusSeconds(900), claimRetried);
    }
}
