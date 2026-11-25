package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.infrastructure.lifecycle.AnparkMunicipalSyncJob;
import com.parkio.parking.infrastructure.lifecycle.IsparkMunicipalSyncJob;
import com.parkio.parking.infrastructure.lifecycle.IzumMunicipalSyncJob;
import com.parkio.parking.infrastructure.lifecycle.KayseriMunicipalSyncJob;
import com.parkio.parking.infrastructure.lifecycle.KonyaMunicipalSyncJob;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * #615: the municipal sync jobs, and only they, run on the municipal scheduler, which has a thread
 * for each of them. A sync job is a scheduled component that takes the {@link MunicipalFacilitySyncService}.
 * A new feed job that kept the default scheduler would again hold up the outbox relay; any other job
 * on the municipal scheduler would wait behind a stalled feed.
 */
class MunicipalSyncSchedulingConfigTest {

    private static final String BASE_PACKAGE = "com.parkio.parking";

    @Test
    void theMunicipalSyncJobsAndNoOtherJobsRunOnTheMunicipalScheduler() {
        Map<Class<?>, List<Scheduled>> scheduledJobs = scheduledComponents();
        Set<Class<?>> syncJobs = scheduledJobs.keySet().stream()
                .filter(MunicipalSyncSchedulingConfigTest::takesTheSyncService)
                .collect(Collectors.toSet());
        Set<Class<?>> onMunicipalScheduler = scheduledJobs.entrySet().stream()
                .filter(job -> job.getValue().stream().anyMatch(MunicipalSyncSchedulingConfigTest::onMunicipalScheduler))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        assertThat(syncJobs).containsExactlyInAnyOrder(
                IzumMunicipalSyncJob.class,
                IsparkMunicipalSyncJob.class,
                AnparkMunicipalSyncJob.class,
                KonyaMunicipalSyncJob.class,
                KayseriMunicipalSyncJob.class);
        assertThat(onMunicipalScheduler).as("jobs on the municipal scheduler").isEqualTo(syncJobs);
        syncJobs.forEach(job -> assertThat(scheduledJobs.get(job))
                .as("scheduled methods of %s", job.getSimpleName())
                .allMatch(MunicipalSyncSchedulingConfigTest::onMunicipalScheduler));
    }

    @Test
    void thePoolHasOneThreadForEachMunicipalSyncJob() {
        long syncJobs = scheduledComponents().keySet().stream()
                .filter(MunicipalSyncSchedulingConfigTest::takesTheSyncService)
                .count();
        assertThat(MunicipalSyncSchedulingConfig.POOL_SIZE).isEqualTo(syncJobs);
    }

    private static boolean onMunicipalScheduler(Scheduled scheduled) {
        return MunicipalSyncSchedulingConfig.MUNICIPAL_SYNC_SCHEDULER.equals(scheduled.scheduler());
    }

    private static boolean takesTheSyncService(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .anyMatch(MunicipalFacilitySyncService.class::isAssignableFrom);
    }

    /**
     * Every component class under {@value #BASE_PACKAGE} with its {@code @Scheduled} annotations,
     * whatever its {@code @ConditionalOnProperty} flags say.
     */
    private static Map<Class<?>, List<Scheduled>> scheduledComponents() {
        TypeFilter components = new AnnotationTypeFilter(Component.class);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                return components.match(reader, getMetadataReaderFactory());
            }
        };
        Map<Class<?>, List<Scheduled>> jobs = new HashMap<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = ClassUtils.resolveClassName(
                    candidate.getBeanClassName(), MunicipalSyncSchedulingConfigTest.class.getClassLoader());
            List<Scheduled> scheduled = Arrays.stream(type.getDeclaredMethods())
                    .map((Method method) -> method.getAnnotation(Scheduled.class))
                    .filter(annotation -> annotation != null)
                    .toList();
            if (!scheduled.isEmpty()) {
                jobs.put(type, scheduled);
            }
        }
        return jobs;
    }
}
