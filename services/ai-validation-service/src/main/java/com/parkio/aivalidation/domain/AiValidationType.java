package com.parkio.aivalidation.domain;

/** The kinds of advisory checks the validator runs over a submission. */
public enum AiValidationType {
    PARKING_SPACE_VISIBILITY,
    EMPTY_SPACE_DETECTION,
    VEHICLE_FIT_ESTIMATION,
    LEGAL_RISK_DETECTION,
    IMAGE_QUALITY,
    DUPLICATE_RISK
}
