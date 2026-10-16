package com.parkio.gateway.application.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import reactor.core.publisher.Mono;

/**
 * Z-05: delivery outcomes stay truthful — only a provider failure releases the claim and
 * returns {@code WAITLIST_EMAIL_DELIVERY_FAILED}.
 */
class WaitlistApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    private WaitlistInterestRepository repository;
    private WaitlistEmailSender emailSender;
    private WaitlistRateLimiter rateLimiter;
    private WaitlistApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(WaitlistInterestRepository.class);
        emailSender = mock(WaitlistEmailSender.class);
        rateLimiter = mock(WaitlistRateLimiter.class);
        WaitlistProperties properties = new WaitlistProperties();
        properties.setHashSecret("unit-test-hash-secret-32chars-min!!");
        properties.setAdmissionsEnabled(true);
        properties.setConsentRequired(true);
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
        when(repository.findByEmailHash(anyString())).thenReturn(java.util.Optional.empty());
        service = new WaitlistApplicationService(
                repository,
                new WaitlistHasher(properties),
                rateLimiter,
                emailSender,
                properties,
                mock(WaitlistOpsNotifier.class),
                TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void markVerificationSentFailureDoesNotReleaseClaimOrReportDeliveryFailure() {
        when(repository.insertPendingIfAbsent(any())).thenReturn(true);
        doNothing().when(emailSender).sendConfirmation(anyString(), anyString(), anyString(), anyString());
        when(repository.markVerificationSent(anyString(), anyLong(), any(), anyInt()))
                .thenThrow(new RuntimeException("mark failed"));

        assertThatThrownBy(() -> service.submit(validSubmit()).block())
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(WaitlistEmailDeliveryException.class)
                .hasMessage("mark failed");

        verify(repository, never()).releaseFailedVerificationClaim(anyString(), anyLong());
    }

    @Test
    void releaseFailureIsSuppressedAndDeliveryFailureCodeIsPreserved() {
        when(repository.insertPendingIfAbsent(any())).thenReturn(true);
        doThrow(new RuntimeException("provider down"))
                .when(emailSender)
                .sendConfirmation(anyString(), anyString(), anyString(), anyString());
        when(repository.releaseFailedVerificationClaim(anyString(), eq(0L)))
                .thenThrow(new RuntimeException("release failed"));

        assertThatThrownBy(() -> service.submit(validSubmit()).block())
                .isInstanceOf(WaitlistEmailDeliveryException.class)
                .hasMessage("WAITLIST_EMAIL_DELIVERY_FAILED")
                .satisfies(ex -> {
                    assertThat(ex.getCause()).hasMessage("provider down");
                    assertThat(ex.getCause().getSuppressed())
                            .singleElement()
                            .extracting(Throwable::getMessage)
                            .isEqualTo("release failed");
                });

        verify(repository, never()).markVerificationSent(anyString(), anyLong(), any(), anyInt());
    }

    private static SubmitWaitlistCommand validSubmit() {
        return new SubmitWaitlistCommand(
                "driver@parkio.dev",
                NOW.minusSeconds(30),
                true,
                WaitlistConsentText.CURRENT_VERSION,
                null,
                null,
                null,
                "unit-test",
                "en",
                "127.0.0.1",
                "unit-test-agent");
    }
}
