-- PROVIDER-ANKARA-01: Seed Ankara / ANPARK municipal data source (inventory only).
-- Additive only. Does not alter İZUM / İSPARK / OSM / İZELMAN rows or community parking_spots.
-- LEGAL REVIEW REQUIRED before production_approved / production enablement.

INSERT INTO municipal_data_sources (
    id, source_key, publisher, dataset_name, canonical_url, access_type,
    license_identifier, license_text, attribution_text, expected_update_frequency,
    stale_after_seconds, aging_after_seconds, schema_version, fields_used,
    active, production_approved, created_at, updated_at
) VALUES (
    'a1111111-1111-4111-8111-111111111105',
    'ankara-anpark-parks',
    'Ankara Buyuksehir Belediyesi / ANPARK',
    'ANPARK Otopark Listesi (wp-json/anpark/v1/parks)',
    'https://www.anpark.com.tr/wp-json/anpark/v1/parks',
    'OPEN_API',
    'LEGAL-REVIEW-REQUIRED',
    'LEGAL REVIEW REQUIRED. Public website JSON feed is not confirmed as licensed open data. Do not enable production until reuse terms are reviewed.',
    'Includes facility inventory from Ankara Buyuksehir Belediyesi / ANPARK (BELTAŞ A.S.). Parkio is not affiliated with or endorsed by Ankara Metropolitan Municipality, BELTAŞ A.S., or ANPARK. LEGAL REVIEW REQUIRED for redistribution.',
    'periodic',
    21600,
    7200,
    '2026-08-12.v1',
    'id,name,type,district,lat,lng,capacity,schedule,address,active',
    TRUE,
    FALSE,
    TIMESTAMPTZ '2026-08-12T00:00:00Z',
    TIMESTAMPTZ '2026-08-12T00:00:00Z'
);
