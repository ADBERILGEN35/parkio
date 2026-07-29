package com.parkio.parking.application.port;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.authority.AuthorityEligibilityReason;
import com.parkio.parking.decision.authority.AuthorityFallbackReason;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Duration;

/** Bounded observability for controlled Decision Engine authority. */
public interface DecisionAuthorityObserverPort {

    void recordConsidered(AuthorityEligibilityReason reason);

    void recordSelected();

    void recordApplied(PublicationDisposition disposition, ParkingSpotStatus appliedStatus);

    void recordFallback(AuthorityFallbackReason reason);

    void recordAuditFailure();

    void recordEngineFailure();

    void recordDuration(Duration duration);

    static DecisionAuthorityObserverPort noop() {
        return new DecisionAuthorityObserverPort() {
            @Override public void recordConsidered(AuthorityEligibilityReason reason) {}
            @Override public void recordSelected() {}
            @Override public void recordApplied(PublicationDisposition disposition, ParkingSpotStatus appliedStatus) {}
            @Override public void recordFallback(AuthorityFallbackReason reason) {}
            @Override public void recordAuditFailure() {}
            @Override public void recordEngineFailure() {}
            @Override public void recordDuration(Duration duration) {}
        };
    }
}