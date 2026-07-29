package com.parkio.parking.fraud;

/** Non-enforcing analytical fraud disposition. */
public enum FraudDisposition {
    NO_SIGNAL,
    OBSERVE,
    REVIEW_CANDIDATE,
    ELEVATED_RISK,
    INSUFFICIENT_EVIDENCE,
    POLICY_UNSUPPORTED
}
