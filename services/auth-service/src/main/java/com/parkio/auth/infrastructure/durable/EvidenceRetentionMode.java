package com.parkio.auth.infrastructure.durable;

/**
 * Retention mode for durable evidence object versions. Names match the recovery contract and the
 * S3 Object Lock / GCS Object Retention Lock XML vocabulary ({@code GOVERNANCE}/{@code Unlocked},
 * {@code COMPLIANCE}/{@code Locked}).
 */
enum EvidenceRetentionMode {
    GOVERNANCE,
    COMPLIANCE;

    static EvidenceRetentionMode parse(String value) {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
