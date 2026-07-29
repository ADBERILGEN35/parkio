package com.parkio.parking.fraud;

/** Attribution quality for fraud evidence. Stronger than trust attribution requirements. */
public enum FraudAttributionQuality {
    DIRECT,
    STRONG,
    PARTIAL,
    AMBIGUOUS,
    NONE
}
