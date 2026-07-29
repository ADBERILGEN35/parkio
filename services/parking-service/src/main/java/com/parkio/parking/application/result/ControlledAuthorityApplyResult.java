package com.parkio.parking.application.result;

import com.parkio.parking.decision.authority.AuthorityEligibilityReason;
import com.parkio.parking.decision.authority.AuthorityFallbackReason;
import java.util.Objects;
import java.util.Optional;

/** Outcome of controlled-authority processing for one AI validation event. */
public final class ControlledAuthorityApplyResult {

    private final AiValidationApplyOutcome applyOutcome;
    private final boolean authorityApplied;
    private final AuthorityEligibilityReason eligibilityReason;
    private final AuthorityFallbackReason fallbackReason;

    private ControlledAuthorityApplyResult(
            AiValidationApplyOutcome applyOutcome,
            boolean authorityApplied,
            AuthorityEligibilityReason eligibilityReason,
            AuthorityFallbackReason fallbackReason) {
        this.applyOutcome = Objects.requireNonNull(applyOutcome, "applyOutcome");
        this.authorityApplied = authorityApplied;
        this.eligibilityReason = Objects.requireNonNull(eligibilityReason, "eligibilityReason");
        this.fallbackReason = fallbackReason;
    }

    public static ControlledAuthorityApplyResult authorityApplied(
            AiValidationApplyOutcome outcome, AuthorityEligibilityReason reason) {
        return new ControlledAuthorityApplyResult(outcome, true, reason, null);
    }

    public static ControlledAuthorityApplyResult legacy(
            AiValidationApplyOutcome outcome,
            AuthorityEligibilityReason eligibilityReason,
            AuthorityFallbackReason fallbackReason) {
        return new ControlledAuthorityApplyResult(outcome, false, eligibilityReason, fallbackReason);
    }

    public AiValidationApplyOutcome applyOutcome() {
        return applyOutcome;
    }

    public boolean authorityApplied() {
        return authorityApplied;
    }

    public AuthorityEligibilityReason eligibilityReason() {
        return eligibilityReason;
    }

    public Optional<AuthorityFallbackReason> fallbackReason() {
        return Optional.ofNullable(fallbackReason);
    }
}