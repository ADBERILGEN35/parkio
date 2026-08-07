package com.parkio.parking.externalsource.provider;

/**
 * Stable machine identity for an organisation/system that supplies parking data.
 * Display labels and raw ETL source keys are separate concepts.
 */
public enum ParkingDataProviderId {
    IZUM,
    OPENSTREETMAP,
    IZELMAN,
    /** Test-only; never production-enabled. */
    FAKE_TEST
}
