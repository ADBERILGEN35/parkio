package com.parkio.notification.infrastructure.smartreturn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

        SmartReturnSchedulerTickSummary summary = scheduler.sendMorningPrompts();

        assertThat(summary.enabled()).isTrue();
        assertThat(summary.eligibleUsers()).isEqualTo(1);
        assertThat(summary.promptedUsers()).isEqualTo(1);
        assertThat(summary.notificationsCreated()).isEqualTo(1);
        verify(notifications).createSmartReturnPrompt(user);
    }

    @Test
    void morningPromptWithNoClaimedCandidatesSendsNothing() {
        when(users.claimDuePrompts(LocalDate.of(2026, 6, 6), 100)).thenReturn(List.of());

        SmartReturnSchedulerTickSummary summary = scheduler.sendMorningPrompts();

        assertThat(summary.enabled()).isTrue();
        assertThat(summary.eligibleUsers()).isZero();
        assertThat(summary.notificationsCreated()).isZero();
        verify(notifications, never()).createSmartReturnPrompt(any());
    }

    @Test
    void featureFlagOffPreventsSchedulerWork() {
        SmartReturnScheduler disabled = new SmartReturnScheduler(users, parking, notifications,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry(),
                false, true, "UTC", 100);

        SmartReturnSchedulerTickSummary morningSummary = disabled.sendMorningPrompts();
        SmartReturnSchedulerTickSummary returnSummary = disabled.runReturnChecks();

        assertThat(morningSummary.enabled()).isFalse();
        assertThat(returnSummary.enabled()).isFalse();
        verify(users, never()).claimDuePrompts(LocalDate.of(2026, 6, 6), 100);
        verify(users, never()).claimDueReturnChecks(NOW, 100);
    }

    @Test
    void returnCheckWithNoSpotsCompletesWithoutNotification() {
        UUID user = UUID.randomUUID();
        SmartReturnUserClient.ReturnCheckCandidate candidate = candidate(user, false);
        when(users.claimDueReturnChecks(NOW, 100)).thenReturn(List.of(candidate));
        when(parking.searchNearby(user, 38.4237, 27.1428, 1000, 5)).thenReturn(List.of());

        SmartReturnSchedulerTickSummary summary = scheduler.runReturnChecks();

        assertThat(summary.enabled()).isTrue();
        assertThat(summary.eligibleUsers()).isEqualTo(1);
        assertThat(summary.returnChecksClaimed()).isEqualTo(1);
        assertThat(summary.noSpots()).isEqualTo(1);
        assertThat(summary.notificationsCreated()).isZero();
        verify(notifications, never()).createSmartReturnParkingAvailable(user, "Konak");
        verify(users).completeReturnCheck(user, false, NOW);
    }

    @Test
    void returnCheckUsesExpectedReturnAtMinusLeadMinutesClaimFromUserService() {
        when(users.claimDueReturnChecks(NOW, 100)).thenReturn(List.of());

        scheduler.runReturnChecks();

        verify(users).claimDueReturnChecks(NOW, 100);
        verifyNoAvailabilityNotification();
    }

    @Test
    void returnCheckWithRealSpotCreatesNotificationAndCompletesSent() {
        UUID user = UUID.randomUUID();
        SmartReturnUserClient.ReturnCheckCandidate candidate = candidate(user, true);
        when(users.claimDueReturnChecks(NOW, 100)).thenReturn(List.of(candidate));
        when(parking.searchNearby(user, 38.4237, 27.1428, 1000, 5))
                .thenReturn(List.of(new SmartReturnParkingClient.NearbySpot(
                        UUID.randomUUID(), "Konak", "AVAILABLE", NOW.plusSeconds(900))));

        SmartReturnSchedulerTickSummary summary = scheduler.runReturnChecks();

        assertThat(summary.enabled()).isTrue();
        assertThat(summary.eligibleUsers()).isEqualTo(1);
        assertThat(summary.returnChecksClaimed()).isEqualTo(1);
        assertThat(summary.claimRetries()).isEqualTo(1);
        assertThat(summary.noSpots()).isZero();
        assertThat(summary.notificationsCreated()).isEqualTo(1);
        verify(notifications).createSmartReturnParkingAvailable(user, "Konak");
        verify(users).completeReturnCheck(user, true, NOW);
    }

    private void verifyNoAvailabilityNotification() {
        verify(notifications, never()).createSmartReturnParkingAvailable(any(), any());
    }

    @Test
    void returnCheckProcessesEachClaimedCandidateOnce() {
        UUID user = UUID.randomUUID();
        SmartReturnUserClient.ReturnCheckCandidate candidate = candidate(user, false);
        when(users.claimDueReturnChecks(NOW, 100)).thenReturn(List.of(candidate), List.of());
        when(parking.searchNearby(user, 38.4237, 27.1428, 1000, 5))
                .thenReturn(List.of(new SmartReturnParkingClient.NearbySpot(
                        UUID.randomUUID(), "Konak", "AVAILABLE", NOW.plusSeconds(900))));

        scheduler.runReturnChecks();
        scheduler.runReturnChecks();

        verify(notifications, times(1)).createSmartReturnParkingAvailable(user, "Konak");
        verify(users, times(1)).completeReturnCheck(user, true, NOW);
    }

    private static SmartReturnUserClient.ReturnCheckCandidate candidate(UUID user, boolean claimRetried) {
        return new SmartReturnUserClient.ReturnCheckCandidate(
                user, 38.4237, 27.1428, "Saved home area", 1000, NOW.plusSeconds(900), claimRetried);
    }
}
