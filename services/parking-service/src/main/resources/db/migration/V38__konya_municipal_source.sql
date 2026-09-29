-- PROVIDER-KONYA-01: Seed Konya / Otopark Bilgileri municipal data source (inventory only).
-- Additive only. Does not alter İZUM / İSPARK / ANPARK / OSM / İZELMAN rows or community parking_spots.

INSERT INTO municipal_data_sources (
    id, source_key, publisher, dataset_name, canonical_url, access_type,
    license_identifier, license_text, attribution_text, expected_update_frequency,
    stale_after_seconds, aging_after_seconds, schema_version, fields_used,
    active, production_approved, created_at, updated_at
) VALUES (
    'a1111111-1111-4111-8111-111111111106',
    'konya-bb-otopark-bilgileri',
    'Konya Buyuksehir Belediyesi',
    'Otopark Bilgileri (CKAN datastore)',
    'https://acikveri.konya.bel.tr/tr/dataset/otopark-bilgileri',
    'OPEN_API',
    'CC-BY-3.0',
    'Creative Commons Attribution 3.0 (CC BY 3.0)',
    'Includes public sector information from Konya Buyuksehir Belediyesi Acik Veri Portali licensed under Creative Commons Attribution 3.0 (CC BY 3.0). Parkio is not affiliated with or endorsed by Konya Metropolitan Municipality.',
    'static',
    86400,
    43200,
    '2026-08-12.v1',
    'bolgeadi,bolgeadresi,bolgekapasite,peronadi,peronadres,peronkapasite,peronkoordinat,peronacilissaati,peronkapanissaati',
    TRUE,
    FALSE,
    TIMESTAMPTZ '2026-08-12T00:00:00Z',
    TIMESTAMPTZ '2026-08-12T00:00:00Z'
);
