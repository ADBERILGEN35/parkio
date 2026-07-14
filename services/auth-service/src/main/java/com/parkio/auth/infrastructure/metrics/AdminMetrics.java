package com.parkio.auth.infrastructure.metrics;

import com.parkio.auth.domain.admin.AdminAuditAction;
import com.parkio.auth.domain.admin.AdminAuditResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality counters for administrative actions.
 */
@Component
public class AdminMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public AdminMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordAction(AdminAuditAction action, AdminAuditResult result) {
        String key = action.name() + ":" + result.name();
        counters.computeIfAbsent(key, ignored -> Counter.builder("admin_actions_total")
                        .description("Administrative actions performed")
                        .tag("action", action.name())
                        .tag("result", result.name())
                        .register(registry))
                .increment();
    }
}
