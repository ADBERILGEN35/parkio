package com.parkio.notification.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The scheduler of the push delivery worker (#641). Every other {@code @Scheduled} job, the outbox relay among them,
 * keeps Spring Boot's default {@code taskScheduler} (one {@code scheduling-} thread). A delivery tick sends its batch
 * one attempt after another, so while Expo does not answer every send waits out the read timeout: about 8 minutes
 * with the defaults (100 sends x 5 s), about 100 minutes with the 60 s read-timeout limit. On the shared thread every
 * other job waited that long.
 *
 * <p>One thread is enough: the worker is the only job on it, and it never overlaps itself, because its fixed delay
 * counts from the end of the previous tick.
 *
 * <p>The bean is not a default candidate. Only the job that names it runs on it, and Spring Boot still creates its
 * default scheduler, because its bean conditions ignore beans that are not default candidates.
 *
 * <p><b>Shutdown (#659).</b> A tick that is still sending when the lifecycle stop phase ends is interrupted by
 * {@code destroy()} ({@code shutdownNow()}), and it records the send in flight as a terminal unknown outcome. The
 * scheduler then waits up to {@link #SHUTDOWN_COMMIT_WAIT} for that tick to commit. It is destroyed before the JPA
 * EntityManagerFactory and, with it, the DataSource, whose shutdown aborts the connections still in use. Without the
 * wait, the context closed them first, the outcome rolled back, and the attempt was sent again after the restart.
 */
@Configuration(proxyBeanMethods = false)
public class PushDeliverySchedulingConfig {

    /** The bean name that the push delivery worker passes to {@code @Scheduled(scheduler = ...)}. */
    public static final String PUSH_DELIVERY_SCHEDULER = "pushDeliveryTaskScheduler";

    static final int POOL_SIZE = 1;

    static final String THREAD_NAME_PREFIX = "push-delivery-";

    /**
     * How long {@code destroy()} waits for an interrupted tick to commit. The commit takes milliseconds. A stalled
     * tick therefore holds shutdown for at most about 35 s: the 30 s lifecycle stop phase, then this wait. That fits
     * inside the 45 s {@code stop_grace_period} that Compose gives the service. It is fixed: no property changes it,
     * and Spring Boot's {@code spring.task.scheduling.shutdown.*} settings apply only to the default scheduler.
     */
    static final Duration SHUTDOWN_COMMIT_WAIT = Duration.ofSeconds(5);

    @Bean(name = PUSH_DELIVERY_SCHEDULER, defaultCandidate = false)
    @DependsOn("entityManagerFactory")
    public ThreadPoolTaskScheduler pushDeliveryTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(POOL_SIZE)
                .threadNamePrefix(THREAD_NAME_PREFIX)
                // Interrupt the tick rather than let it finish its batch, then wait for its commit.
                .awaitTermination(false)
                .awaitTerminationPeriod(SHUTDOWN_COMMIT_WAIT)
                .build();
    }
}
