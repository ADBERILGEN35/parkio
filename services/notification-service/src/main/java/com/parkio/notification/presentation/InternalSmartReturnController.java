package com.parkio.notification.presentation;

import com.parkio.notification.infrastructure.smartreturn.SmartReturnScheduler;
import com.parkio.notification.infrastructure.smartreturn.SmartReturnSchedulerTickSummary;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal/notifications/smart-return")
@ConditionalOnProperty(name = "parkio.smart-return.scheduler.test-hooks.enabled", havingValue = "true")
public class InternalSmartReturnController {

    private final SmartReturnScheduler scheduler;

    public InternalSmartReturnController(SmartReturnScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @PostMapping("/trigger-morning-prompt")
    public ResponseEntity<SmartReturnSchedulerTickSummary> triggerMorningPrompt() {
        return ResponseEntity.accepted().body(scheduler.sendMorningPrompts());
    }

    @PostMapping("/trigger-return-check")
    public ResponseEntity<SmartReturnSchedulerTickSummary> triggerReturnCheck() {
        return ResponseEntity.accepted().body(scheduler.runReturnChecks());
    }
}
