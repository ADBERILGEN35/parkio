package com.parkio.aivalidation.domain;

/**
 * Advisory risk signals the validator may detect. These flag risk for a human/moderation
 * review; they never auto-reject (ai-context/02).
 */
public enum AiRiskType {
    NO_PARKING_SIGN,
    GARAGE_ENTRANCE,
    BUS_STOP,
    PEDESTRIAN_CROSSING,
    FIRE_HYDRANT,
    SIDEWALK,
    TRAFFIC_FLOW_BLOCKING,
    PRIVATE_PROPERTY,
    LOW_IMAGE_QUALITY,
    NOT_A_PARKING_SPOT,
    UNKNOWN;

    /**
     * A "legal/placement" risk that should raise the result to at least WARNING.
     * {@code LOW_IMAGE_QUALITY} is a quality concern and {@code NOT_A_PARKING_SPOT}
     * is handled as a hard FAILED, so neither counts here.
     */
    public boolean isLegalRisk() {
        return switch (this) {
            case NO_PARKING_SIGN, GARAGE_ENTRANCE, BUS_STOP, PEDESTRIAN_CROSSING,
                 FIRE_HYDRANT, SIDEWALK, TRAFFIC_FLOW_BLOCKING, PRIVATE_PROPERTY -> true;
            case LOW_IMAGE_QUALITY, NOT_A_PARKING_SPOT, UNKNOWN -> false;
        };
    }
}
