package com.parkio.notification.infrastructure.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 */
@Configuration(proxyBeanMethods = false)
public class PushDeliverySchedulingConfig {

    /** The bean name that the push delivery worker passes to {@code @Scheduled(scheduler = ...)}. */
    public static final String PUSH_DELIVERY_SCHEDULER = "pushDeliveryTaskScheduler";

    static final int POOL_SIZE = 1;

    static final String THREAD_NAME_PREFIX = "push-delivery-";

    @Bean(name = PUSH_DELIVERY_SCHEDULER, defaultCandidate = false)
    public ThreadPoolTaskScheduler pushDeliveryTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(POOL_SIZE).threadNamePrefix(THREAD_NAME_PREFIX).build();
    }
}
