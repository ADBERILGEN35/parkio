package com.parkio.parking.fraud;

/** Eligibility result for normalized fraud evidence. */
public enum FraudEvidenceEligibility {
    ELIGIBLE,
    INELIGIBLE_NO_SUBJECT,
    INELIGIBLE_AMBIGUOUS_ATTRIBUTION,
    INELIGIBLE_SYSTEM_REDELIVERY,
    INELIGIBLE_UNSUPPORTED_ROLE,
    INELIGIBLE_PRIVACY_RESTRICTED,
    INELIGIBLE_INSUFFICIENT_HISTORY,
    DUPLICATE,
    POLICY_UNSUPPORTED
}
