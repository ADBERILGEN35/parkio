package com.parkio.parking.infrastructure.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.infrastructure.ispark.IsparkMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import com.parkio.parking.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #615: a municipal feed that never answers must not hold up the rest of parking-service. With the
 * 27 s IZUM read timeout such a run takes about 87 s, and a stall mid-body about 171 s. While every
 * {@code @Scheduled} job shared one scheduler thread, the outbox relay (1 s), the spot-expiry job and
 * the other feeds all waited that long.
 *
 * <p>Here the first IZUM sync blocks until the test releases it, like a fetch that is still waiting
 * for its first byte. While it is blocked, the outbox relay and the spot-expiry job (default
 * scheduler) and the ISPARK sync (another feed) must keep their cadence, and IZUM must not start a
 * second, overlapping run. The real job beans and the production scheduling configuration run; only
 * their collaborators are replaced. The context stays cached, so the test cancels its millisecond
 * schedules when it ends. Closing the context instead would drop the H2 schema that every cached test
 * context shares ({@code ddl-auto: create-drop}).
 */
@SpringBootTest(properties = {
        "parkio.municipal.enabled=true",
        "parkio.municipal.izum.enabled=true",
        "parkio.municipal.izum.scheduler-enabled=true",
        "parkio.municipal.izum.fixed-delay-ms=50",
        "parkio.municipal.ispark.enabled=true",
        "parkio.municipal.ispark.scheduler-enabled=true",
        "parkio.municipal.ispark.fixed-delay-ms=50",
        "parkio.kafka.relay.enabled=true",
        "parkio.kafka.relay.poll-interval-ms=20",
        "parkio.lifecycle.parking-expiry.enabled=true",
        "parkio.lifecycle.parking-expiry.fixed-delay-ms=20"
})
class MunicipalSyncSchedulerIsolationTest {

    private static final String IZUM = IzumMunicipalParkingAdapter.SOURCE_KEY;
    private static final String ISPARK = IsparkMunicipalParkingAdapter.SOURCE_KEY;
    /** Upper bound for every wait, so a regression fails the test instead of hanging the build. */
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final CountDownLatch IZUM_STALLED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_IZUM = new CountDownLatch(1);
    private static final Probe IZUM_SYNC = new Probe();
    private static final Probe ISPARK_SYNC = new Probe();
    private static final Probe RELAY_POLL = new Probe();
    private static final Probe SPOT_EXPIRY = new Probe();

    @TestBean
    MunicipalFacilitySyncService municipalFacilitySyncService;

    @TestBean
    OutboxEventJpaRepository outboxEventJpaRepository;

    @TestBean
    ParkingApplicationService parkingApplicationService;

    /** Its startup refresh reads source rows that the H2 test schema does not carry. */
    @MockitoBean
    MunicipalSourceMetrics municipalSourceMetrics;

    @Autowired
    ScheduledTaskHolder scheduledTasks;

    /** The first IZUM run stalls until released; every other run returns at once. */
    static MunicipalFacilitySyncService municipalFacilitySyncService() {
        return new MunicipalFacilitySyncService(List.of(), null, null, null, null, Clock.systemUTC()) {
            @Override
            public MunicipalSyncResult sync(String sourceKey) {
                Probe probe = IZUM.equals(sourceKey) ? IZUM_SYNC : ISPARK_SYNC;
                probe.enter();
                try {
                    if (IZUM.equals(sourceKey) && IZUM_STALLED.getCount() > 0) {
                        IZUM_STALLED.countDown();
                        RELEASE_IZUM.await(3 * WAIT.toMillis(), TimeUnit.MILLISECONDS);
                    }
                    return new MunicipalSyncResult(
                            MunicipalSyncRunStatus.SUCCESS, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, false);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } finally {
                    probe.exit();
                }
            }
        };
    }

    /** Every relay poll finds no pending row, so the relay only shows that it ran. */
    static OutboxEventJpaRepository outboxEventJpaRepository() {
        OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
        when(outbox.findUnpublishedBatchForUpdate(anyInt())).thenAnswer(invocation -> {
            RELAY_POLL.enter();
            RELAY_POLL.exit();
            return List.of();
        });
        return outbox;
    }

    static ParkingApplicationService parkingApplicationService() {
        ParkingApplicationService parking = mock(ParkingApplicationService.class);
        when(parking.expireElapsedSpots(anyInt())).thenAnswer(invocation -> {
            SPOT_EXPIRY.enter();
            SPOT_EXPIRY.exit();
            return 0;
        });
        return parking;
    }

    @Test
    void aStalledMunicipalFetchDelaysNeitherTheOutboxRelayNorTheOtherScheduledJobs() throws Exception {
        assertThat(IZUM_STALLED.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .as("the IZUM sync started and stalled").isTrue();
        try {
            int relayPolls = RELAY_POLL.calls();
            int spotExpiries = SPOT_EXPIRY.calls();
            int isparkSyncs = ISPARK_SYNC.calls();

            awaitCalls(RELAY_POLL, relayPolls + 5, "outbox relay polls while the IZUM fetch is stalled");
            awaitCalls(SPOT_EXPIRY, spotExpiries + 3, "spot-expiry runs while the IZUM fetch is stalled");
            awaitCalls(ISPARK_SYNC, isparkSyncs + 3, "ISPARK syncs while the IZUM fetch is stalled");
            assertThat(IZUM_SYNC.calls()).as("IZUM runs while its first run is stalled").isEqualTo(1);
        } finally {
            RELEASE_IZUM.countDown();
        }
        awaitCalls(IZUM_SYNC, 3, "IZUM runs after the stall ends");

        assertThat(IZUM_SYNC.threads()).as("IZUM sync threads").allMatch(name -> name.startsWith("municipal-sync-"));
        assertThat(ISPARK_SYNC.threads()).as("ISPARK sync threads")
                .allMatch(name -> name.startsWith("municipal-sync-"));
        assertThat(RELAY_POLL.threads()).as("outbox relay threads").allMatch(name -> name.startsWith("scheduling-"));
        assertThat(SPOT_EXPIRY.threads()).as("spot-expiry threads").allMatch(name -> name.startsWith("scheduling-"));
        assertThat(IZUM_SYNC.maxRunning()).as("concurrent IZUM runs").isEqualTo(1);
        assertThat(ISPARK_SYNC.maxRunning()).as("concurrent ISPARK runs").isEqualTo(1);
    }

    @AfterEach
    void cancelTheSchedules() {
        RELEASE_IZUM.countDown();
        scheduledTasks.getScheduledTasks().forEach(ScheduledTask::cancel);
    }

    private static void awaitCalls(Probe probe, int atLeast, String description) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (probe.calls() < atLeast && System.nanoTime() - deadline < 0) {
            Thread.sleep(10);
        }
        assertThat(probe.calls()).as(description).isGreaterThanOrEqualTo(atLeast);
    }

    /** Counts the calls of one job, the threads it ran on, and how many of its runs overlapped. */
    private static final class Probe {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger maxRunning = new AtomicInteger();
        private final Set<String> threads = ConcurrentHashMap.newKeySet();

        void enter() {
            calls.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
        }

        void exit() {
            running.decrementAndGet();
        }

        int calls() {
            return calls.get();
        }

        int maxRunning() {
            return maxRunning.get();
        }

        Set<String> threads() {
            return Set.copyOf(threads);
        }
    }
}
