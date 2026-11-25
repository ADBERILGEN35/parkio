package com.parkio.notification.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.notification.infrastructure.delivery.PushDeliveryWorker;
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
 * #641: the push delivery worker, and only it, runs on the push delivery scheduler. A delivery tick back on the
 * default scheduler would again hold up the outbox relay while Expo does not answer; any other job on the push
 * delivery scheduler would wait behind that tick.
 */
class PushDeliverySchedulingConfigTest {

    private static final String BASE_PACKAGE = "com.parkio.notification";

    @Test
    void thePushDeliveryWorkerAndNoOtherJobRunsOnThePushDeliveryScheduler() {
        Map<Class<?>, List<Scheduled>> scheduledJobs = scheduledComponents();
        Set<Class<?>> onPushDeliveryScheduler = scheduledJobs.entrySet().stream()
                .filter(job -> job.getValue().stream()
                        .anyMatch(PushDeliverySchedulingConfigTest::onPushDeliveryScheduler))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        assertThat(scheduledJobs).containsKey(PushDeliveryWorker.class);
        assertThat(onPushDeliveryScheduler).as("jobs on the push delivery scheduler")
                .containsExactly(PushDeliveryWorker.class);
        assertThat(scheduledJobs.get(PushDeliveryWorker.class)).as("scheduled methods of the push delivery worker")
                .allMatch(PushDeliverySchedulingConfigTest::onPushDeliveryScheduler);
    }

    private static boolean onPushDeliveryScheduler(Scheduled scheduled) {
        return PushDeliverySchedulingConfig.PUSH_DELIVERY_SCHEDULER.equals(scheduled.scheduler());
    }

    /**
     * Every component class under {@value #BASE_PACKAGE} with its {@code @Scheduled} annotations, whatever its
     * {@code @ConditionalOnProperty} flags say.
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
                    candidate.getBeanClassName(), PushDeliverySchedulingConfigTest.class.getClassLoader());
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
