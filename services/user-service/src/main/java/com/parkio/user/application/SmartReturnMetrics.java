package com.parkio.user.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class SmartReturnMetrics {

    private final Counter enabled;
    private final MeterRegistry registry;

    public SmartReturnMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.enabled = Counter.builder("parkio.smart_return.enabled_total").register(registry);
    }

    public void recordEnabled() {
        enabled.increment();
    }

    public void recordPromptAnswer(String answer) {
        registry.counter("parkio.smart_return.morning_prompt_answers_total", "answer", answer).increment();
    }
}
