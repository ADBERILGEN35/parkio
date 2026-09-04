-- PROVIDER-KAYSERI-01: Seed Kayseri / Otoparklar municipal data source (inventory only).
-- Additive only. Does not alter İZUM / İSPARK / ANPARK / KONYA / OSM / İZELMAN rows or community parking_spots.

INSERT INTO municipal_data_sources (
    id, source_key, publisher, dataset_name, canonical_url, access_type,
    license_identifier, license_text, attribution_text, expected_update_frequency,
    stale_after_seconds, aging_after_seconds, schema_version, fields_used,
    active, production_approved, created_at, updated_at
) VALUES (
    'a1111111-1111-4111-8111-111111111107',
    'kayseri-bb-otoparklar',
    'Kayseri Buyuksehir Belediyesi',
    'Kayseri Otoparklar (GeoJSON)',
    'https://acikveri.kayseri.bel.tr/veri-seti/kayseri-otoparklar/35',
    'OPEN_API',
    'CC-BY-4.0',
    'Creative Commons Attribution 4.0 International (CC BY 4.0)',
    'Includes public sector information from Kayseri Buyuksehir Belediyesi Acik Veri Portali licensed under Attribution 4.0 International (CC BY 4.0). Parkio is not affiliated with or endorsed by Kayseri Metropolitan Municipality.',
    'static',
    86400,
    43200,
    '2026-08-12.v1',
    'CBNO,ADI,KISA_ADI,lat_DD,lon_DD,ILCE_CBNO,MAH_CBNO,KATEGORI,ALTKATEGOR,KAT_ID',
    TRUE,
    FALSE,
    TIMESTAMPTZ '2026-08-12T00:00:00Z',
    TIMESTAMPTZ '2026-08-12T00:00:00Z'
);
