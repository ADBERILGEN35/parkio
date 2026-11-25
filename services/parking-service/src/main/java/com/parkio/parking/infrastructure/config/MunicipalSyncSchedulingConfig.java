package com.parkio.parking.infrastructure.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The scheduler of the municipal feed syncs (#615). Every other {@code @Scheduled} job, the outbox
 * relay among them, keeps Spring Boot's default {@code taskScheduler} (one {@code scheduling-} thread).
 * A feed run can hold its thread for minutes: about 87 s with the IZUM bounds when the feed never
 * answers, about 171 s when it stalls mid-body. On the shared thread every other job waited that long.
 *
 * <p>The pool has one thread per municipal sync job, so a stalled feed delays no other feed either.
 * A source still never overlaps itself: each job runs with a fixed delay, which counts from the end of
 * its previous run, and the run lease skips a sync while another one of the same source is running.
 *
 * <p>The bean is not a default candidate. Only the jobs that name it run on it, and Spring Boot still
 * creates its default scheduler, because its bean conditions ignore beans that are not default
 * candidates.
 */
@Configuration(proxyBeanMethods = false)
public class MunicipalSyncSchedulingConfig {

    /** The bean name that the municipal sync jobs pass to {@code @Scheduled(scheduler = ...)}. */
    public static final String MUNICIPAL_SYNC_SCHEDULER = "municipalSyncTaskScheduler";

    /** One thread for each municipal sync job: IZUM, ISPARK, ANPARK, Konya and Kayseri. */
    static final int POOL_SIZE = 5;

    static final String THREAD_NAME_PREFIX = "municipal-sync-";

    @Bean(name = MUNICIPAL_SYNC_SCHEDULER, defaultCandidate = false)
    public ThreadPoolTaskScheduler municipalSyncTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(POOL_SIZE).threadNamePrefix(THREAD_NAME_PREFIX).build();
    }
}
