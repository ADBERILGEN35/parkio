-- DATA-WP-03B: band-level vehicle/parse identity and plan raw-hash for idempotency.
ALTER TABLE municipal_tariff_plans
    ADD COLUMN IF NOT EXISTS raw_record_hash VARCHAR(64);

ALTER TABLE municipal_tariff_rate_bands
    ADD COLUMN IF NOT EXISTS vehicle_class VARCHAR(32) NOT NULL DEFAULT 'ANY',
    ADD COLUMN IF NOT EXISTS parse_status VARCHAR(32) NOT NULL DEFAULT 'PARSED';

ALTER TABLE municipal_tariff_rate_bands
    DROP CONSTRAINT IF EXISTS municipal_tariff_rate_bands_vehicle_class_check;
ALTER TABLE municipal_tariff_rate_bands
    ADD CONSTRAINT municipal_tariff_rate_bands_vehicle_class_check
        CHECK (vehicle_class IN ('ANY', 'CAR', 'MOTORCYCLE', 'DISABLED'));

ALTER TABLE municipal_tariff_rate_bands
    DROP CONSTRAINT IF EXISTS municipal_tariff_rate_bands_parse_status_check;
ALTER TABLE municipal_tariff_rate_bands
    ADD CONSTRAINT municipal_tariff_rate_bands_parse_status_check
        CHECK (parse_status IN ('PARSED', 'TEXTUAL_FALLBACK', 'UNPARSEABLE'));
