package com.parkio.gateway.infrastructure.waitlist.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.parkio.gateway.application.waitlist.SubmitWaitlistCommand;
import com.parkio.gateway.application.waitlist.WaitlistApplicationService;
import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import com.parkio.gateway.infrastructure.security.JwtTokenValidator;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;

/** Default configuration: integration off, no exporter/scheduler, no outbox rows. */
@SpringBootTest(properties = "parkio.waitlist.ops-notifications.export-dir=/nonexistent-should-not-be-used")
@ActiveProfiles("test")
class WaitlistOpsNotificationDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WaitlistApplicationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private WaitlistRateLimiter rateLimiter;

    @MockBean
    private WaitlistEmailSender emailSender;

    @MockBean
    private JwtTokenValidator tokenValidator;

    @Test
    void disabledByDefaultConfirmationWorksAndNothingIsRecorded() {
        assertThat(context.getBeansOfType(WaitlistOpsNotificationExporter.class)).isEmpty();
        assertThat(context.getBean(WaitlistOpsNotificationProperties.class).isEnabled()).isFalse();

        jdbcTemplate.update("DELETE FROM waitlist_ops_notification_outbox");
        jdbcTemplate.update("DELETE FROM waitlist_interest");
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
        AtomicReference<String> token = new AtomicReference<>();
        doAnswer(invocation -> {
            token.set(invocation.getArgument(1));
            return null;
        }).when(emailSender).sendConfirmation(anyString(), anyString(), any(), anyString());

        service.submit(new SubmitWaitlistCommand(
                "disabled.synthetic@example.test", Instant.now(), null, null, null, "parkio.dev-landing", "tr",
                "198.51.100.24", null)).block();
        service.confirm(token.get()).block();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM waitlist_interest", String.class))
                .isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_ops_notification_outbox", Integer.class)).isZero();
    }
}
