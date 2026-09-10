-- PROVIDER-ISTANBUL-01: Seed İstanbul / İSPARK municipal data source.
-- Additive only. Does not alter İZUM / OSM / İZELMAN rows or community parking_spots.

INSERT INTO municipal_data_sources (
    id, source_key, publisher, dataset_name, canonical_url, access_type,
    license_identifier, license_text, attribution_text, expected_update_frequency,
    stale_after_seconds, aging_after_seconds, schema_version, fields_used,
    active, production_approved, created_at, updated_at
) VALUES (
    'a1111111-1111-4111-8111-111111111104',
    'istanbul-ispark-parks',
    'Istanbul Buyuksehir Belediyesi / ISPARK',
    'ISPARK Otopark Listesi Web Servisi',
    'https://api.ibb.gov.tr/ispark/Park',
    'OPEN_API',
    'IBB-ACIK-VERI',
    'Istanbul Buyuksehir Belediyesi Acik Veri Lisansi (attribution required; as-is disclaimer)',
    'Includes public sector information from Istanbul Buyuksehir Belediyesi Acik Veri Portali (ISPARK). Parkio is not affiliated with or endorsed by Istanbul Metropolitan Municipality or ISPARK A.S. Attribution required under IBB Acik Veri Licence.',
    'near-real-time',
    900,
    300,
    '2026-08-07.v1',
    'parkID,parkName,lat,lng,capacity,emptyCapacity,workHours,parkType,freeTime,district,isOpen',
    TRUE,
    FALSE,
    TIMESTAMPTZ '2026-08-07T00:00:00Z',
    TIMESTAMPTZ '2026-08-07T00:00:00Z'
);
