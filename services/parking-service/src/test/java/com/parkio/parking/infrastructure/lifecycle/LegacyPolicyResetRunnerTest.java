package com.parkio.parking.infrastructure.lifecycle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.LegacyPolicyResetApplicationService;
import com.parkio.parking.application.LegacyPolicyResetApplicationService.LegacyPolicyResetReport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/** Wiring tests for the opt-in legacy policy reset runner. */
class LegacyPolicyResetRunnerTest {

    @Test
    void dryRunModeDelegatesToDryRun() {
        LegacyPolicyResetApplicationService service = mock(LegacyPolicyResetApplicationService.class);
        when(service.dryRun("2026-07-photo-policy-v3-recall", 100))
                .thenReturn(emptyReport(true));

        new LegacyPolicyResetRunner(service, true, "2026-07-photo-policy-v3-recall", 100)
                .run(mock(ApplicationArguments.class));

        verify(service).dryRun("2026-07-photo-policy-v3-recall", 100);
        verifyNoMoreInteractions(service);
    }

    @Test
    void executeModeDelegatesToExecute() {
        LegacyPolicyResetApplicationService service = mock(LegacyPolicyResetApplicationService.class);
        when(service.execute("2026-07-photo-policy-v3-recall", 50))
                .thenReturn(emptyReport(false));

        new LegacyPolicyResetRunner(service, false, "2026-07-photo-policy-v3-recall", 50)
                .run(mock(ApplicationArguments.class));

        verify(service).execute("2026-07-photo-policy-v3-recall", 50);
        verifyNoMoreInteractions(service);
    }

    private static LegacyPolicyResetReport emptyReport(boolean dryRun) {
        return new LegacyPolicyResetReport(
                dryRun,
                "2026-07-photo-policy-v3-recall",
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                Map.of(),
                0);
    }
}
