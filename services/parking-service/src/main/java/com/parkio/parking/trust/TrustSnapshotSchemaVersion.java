package com.parkio.parking.trust;

/** Version of serialized trust ledger/snapshot payloads. */
public enum TrustSnapshotSchemaVersion {
    V1("trust-snapshot-v1");

    private final String value;

    TrustSnapshotSchemaVersion(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}

