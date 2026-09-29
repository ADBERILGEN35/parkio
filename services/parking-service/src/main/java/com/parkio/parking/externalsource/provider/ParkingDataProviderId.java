package com.parkio.parking.externalsource.provider;

/**
 * Stable machine identity for an organisation/system that supplies parking data.
 * Display labels and raw ETL source keys are separate concepts.
 */
public enum ParkingDataProviderId {
    IZUM,
    /** İstanbul Büyükşehir Belediyesi / İSPARK (machine id: ISPARK). */
    ISPARK,
    /** Ankara Büyükşehir Belediyesi / ANPARK (machine id: ANPARK). Inventory only. */
    ANPARK,
    /** Konya Büyükşehir Belediyesi / Otopark Bilgileri (machine id: KONYA). Inventory only. */
    KONYA,
    /** Kayseri Büyükşehir Belediyesi / Otoparklar (machine id: KAYSERI). Inventory only. */
    KAYSERI,
    OPENSTREETMAP,
    IZELMAN,
    /** Test-only; never production-enabled. */
    FAKE_TEST
}
