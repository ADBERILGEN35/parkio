package com.parkio.parking.fraud;

/** Snapshot schema version for fraud replay. */
public enum FraudSnapshotSchemaVersion {
    V1("fraud-snapshot-v1");

    private final String value;

    FraudSnapshotSchemaVersion(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static FraudSnapshotSchemaVersion fromValue(String value) {
        for (FraudSnapshotSchemaVersion version : values()) {
            if (version.value.equals(value)) {
                return version;
            }
        }
        throw new UnsupportedFraudSchemaVersionException("Unsupported fraud snapshot schema: " + value);
    }
}
