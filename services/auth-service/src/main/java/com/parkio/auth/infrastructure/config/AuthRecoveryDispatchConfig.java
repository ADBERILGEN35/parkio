package com.parkio.auth.infrastructure.config;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Runs the public resend-verification and forgot-password work after the response, so neither
 * the response time nor the status shows whether an account exists (CL-F14.2). The work itself is
 * unchanged: token rotation and the e-mail still commit or roll back together.
 */
@Configuration
public class AuthRecoveryDispatchConfig {

    public static final String EXECUTOR = "authRecoveryDispatchExecutor";

    /**
     * Bounded. When the queue is full the request thread runs the task itself: the e-mail is still
     * sent, and only under that overload does the response time depend on the account again.
     * Shutdown waits for queued tasks, which run before the data source closes.
     */
    @Bean(name = EXECUTOR)
    ThreadPoolTaskExecutor authRecoveryDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("auth-recovery-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1_000);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(copyMdc());
        return executor;
    }

    /** Keeps the request's log context (trace id) on the background thread. */
    static TaskDecorator copyMdc() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                setContext(context);
                try {
                    task.run();
                } finally {
                    setContext(previous);
                }
            };
        };
    }

    private static void setContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
