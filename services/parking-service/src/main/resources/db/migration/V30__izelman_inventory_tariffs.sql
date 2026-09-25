-- DATA-WP-03: historical İZELMAN inventory, roadside and tariff foundation.
ALTER TABLE municipal_data_sources
 ADD COLUMN IF NOT EXISTS source_content_at TIMESTAMPTZ,
 ADD COLUMN IF NOT EXISTS source_age_classification VARCHAR(32),
 ADD COLUMN IF NOT EXISTS complete_snapshot BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN IF NOT EXISTS family_key VARCHAR(64);
ALTER TABLE municipal_data_sources ADD CONSTRAINT ck_municipal_source_age CHECK
 (source_age_classification IS NULL OR source_age_classification IN ('CURRENT','AGING','HISTORICAL','UNKNOWN','UNAVAILABLE','INVALID'));

CREATE TABLE municipal_roadside_segments (
 id UUID PRIMARY KEY, display_name VARCHAR(512) NOT NULL, district VARCHAR(256), neighborhood VARCHAR(256),
 address_or_description VARCHAR(2048), opening_hours_json TEXT, latitude DOUBLE PRECISION, longitude DOUBLE PRECISION,
 location GEOGRAPHY(Point,4326), capacity_total INTEGER, geometry_kind VARCHAR(32) NOT NULL,
 payment_required BOOLEAN, source_content_at TIMESTAMPTZ, source_age_classification VARCHAR(32),
 publication_status VARCHAR(32) NOT NULL DEFAULT 'UNPUBLISHED', active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 CHECK(latitude IS NULL OR latitude BETWEEN -90 AND 90), CHECK(longitude IS NULL OR longitude BETWEEN -180 AND 180),
 CHECK(geometry_kind IN ('POINT','STREET_NAME_ONLY','UNKNOWN')), CHECK(capacity_total IS NULL OR capacity_total >= 0),
 CHECK(source_age_classification IS NULL OR source_age_classification IN ('CURRENT','AGING','HISTORICAL','UNKNOWN','UNAVAILABLE','INVALID')));
CREATE INDEX idx_municipal_roadside_location ON municipal_roadside_segments USING GIST(location);
CREATE FUNCTION municipal_roadside_segments_set_location() RETURNS trigger AS $$
BEGIN NEW.location := CASE WHEN NEW.latitude IS NULL OR NEW.longitude IS NULL THEN NULL ELSE
 ST_SetSRID(ST_MakePoint(NEW.longitude,NEW.latitude),4326)::geography END; RETURN NEW; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_municipal_roadside_segments_set_location BEFORE INSERT OR UPDATE OF latitude,longitude
 ON municipal_roadside_segments FOR EACH ROW EXECUTE FUNCTION municipal_roadside_segments_set_location();
CREATE TABLE municipal_roadside_source_links (
 id UUID PRIMARY KEY, segment_id UUID NOT NULL REFERENCES municipal_roadside_segments(id),
 source_id UUID NOT NULL REFERENCES municipal_data_sources(id), external_id VARCHAR(128) NOT NULL,
 raw_record_hash VARCHAR(128) NOT NULL, source_metadata_json TEXT, active BOOLEAN NOT NULL DEFAULT TRUE,
 first_seen_at TIMESTAMPTZ NOT NULL, last_seen_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, UNIQUE(source_id,external_id));

CREATE TABLE municipal_tariff_plans (
 id UUID PRIMARY KEY, source_id UUID NOT NULL REFERENCES municipal_data_sources(id), external_id VARCHAR(128) NOT NULL,
 plan_name VARCHAR(512) NOT NULL, currency CHAR(3) NOT NULL DEFAULT 'TRY', vehicle_class VARCHAR(32) NOT NULL DEFAULT 'CAR',
 valid_from DATE, valid_to DATE, published_at TIMESTAMPTZ, source_content_at TIMESTAMPTZ,
 source_age_classification VARCHAR(32), currentness VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN', original_text TEXT,
 active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, UNIQUE(source_id,external_id), CHECK(currentness IN ('CURRENT','HISTORICAL','UNKNOWN')),
 CHECK(original_text IS NULL OR length(original_text)<=8192),
 CHECK(source_age_classification IS NULL OR source_age_classification IN ('CURRENT','AGING','HISTORICAL','UNKNOWN','UNAVAILABLE','INVALID')));
CREATE TABLE municipal_tariff_rate_bands (
 id UUID PRIMARY KEY, plan_id UUID NOT NULL REFERENCES municipal_tariff_plans(id) ON DELETE CASCADE,
 band_order INTEGER NOT NULL, duration_from_minutes INTEGER, duration_to_minutes INTEGER,
 amount NUMERIC(12,2) NOT NULL, fee_kind VARCHAR(32) NOT NULL, day_category VARCHAR(32) NOT NULL DEFAULT 'ANY',
 time_band_label VARCHAR(128), UNIQUE(plan_id,band_order), CHECK(fee_kind IN ('FIXED','INCREMENTAL','SUBSCRIPTION','OTHER')));
CREATE TABLE municipal_tariff_assignments (
 id UUID PRIMARY KEY, plan_id UUID NOT NULL REFERENCES municipal_tariff_plans(id) ON DELETE CASCADE,
 target_kind VARCHAR(32) NOT NULL CHECK(target_kind IN ('FACILITY','ROADSIDE')), target_id UUID NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL, UNIQUE(plan_id,target_kind,target_id));
CREATE TABLE municipal_izelman_import_runs (
 id UUID PRIMARY KEY, sync_run_id UUID NOT NULL REFERENCES municipal_source_sync_runs(id),
 source_id UUID NOT NULL REFERENCES municipal_data_sources(id), data_type VARCHAR(32) NOT NULL,
 input_filename VARCHAR(512) NOT NULL, input_sha256 VARCHAR(64) NOT NULL, encoding VARCHAR(32) NOT NULL,
 delimiter CHAR(1) NOT NULL, schema_fingerprint VARCHAR(64) NOT NULL, source_content_at TIMESTAMPTZ,
 source_age_classification VARCHAR(32), dry_run BOOLEAN NOT NULL DEFAULT FALSE, quality_report_json TEXT,
 created_at TIMESTAMPTZ NOT NULL, CHECK(data_type IN ('FACILITY','ROADSIDE','TARIFF')));

INSERT INTO municipal_data_sources
(id,source_key,publisher,dataset_name,canonical_url,access_type,license_identifier,license_text,attribution_text,
 expected_update_frequency,stale_after_seconds,aging_after_seconds,schema_version,fields_used,active,production_approved,
 created_at,updated_at,source_content_at,source_age_classification,complete_snapshot,family_key) VALUES
('33333333-3333-4333-8333-333333333301','izelman-open-parking-facilities','İZELMAN A.Ş.','Open parking facilities','https://acikveri.bizizmir.com/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi','OPEN_DATA_FILE','IZMIR-OPEN-DATA','İzmir Metropolitan Municipality Open Data License','İzmir Metropolitan Municipality / İZELMAN A.Ş.','historical',63072000,15552000,'izelman-csv-v1','inventory',TRUE,FALSE,now(),now(),'2022-11-28T00:00:00Z','HISTORICAL',TRUE,'izelman'),
('33333333-3333-4333-8333-333333333302','izelman-closed-parking-facilities','İZELMAN A.Ş.','Closed parking facilities','https://acikveri.bizizmir.com/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi','OPEN_DATA_FILE','IZMIR-OPEN-DATA','İzmir Metropolitan Municipality Open Data License','İzmir Metropolitan Municipality / İZELMAN A.Ş.','historical',63072000,15552000,'izelman-csv-v1','inventory',TRUE,FALSE,now(),now(),'2022-11-25T00:00:00Z','HISTORICAL',TRUE,'izelman'),
('33333333-3333-4333-8333-333333333303','izelman-barrier-parking-facilities','İZELMAN A.Ş.','Barrier parking facilities','https://acikveri.bizizmir.com/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi','OPEN_DATA_FILE','IZMIR-OPEN-DATA','İzmir Metropolitan Municipality Open Data License','İzmir Metropolitan Municipality / İZELMAN A.Ş.','historical',63072000,15552000,'izelman-csv-v1','inventory',TRUE,FALSE,now(),now(),'2022-11-28T00:00:00Z','HISTORICAL',TRUE,'izelman'),
('33333333-3333-4333-8333-333333333304','izelman-roadside-parking','İZELMAN A.Ş.','Roadside parking','https://acikveri.bizizmir.com/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi','OPEN_DATA_FILE','IZMIR-OPEN-DATA','İzmir Metropolitan Municipality Open Data License','İzmir Metropolitan Municipality / İZELMAN A.Ş.','historical',63072000,15552000,'izelman-csv-v1','roadside',TRUE,FALSE,now(),now(),'2022-11-25T00:00:00Z','HISTORICAL',TRUE,'izelman'),
('33333333-3333-4333-8333-333333333305','izelman-parking-tariffs','İZELMAN A.Ş.','Parking tariffs','https://acikveri.bizizmir.com/dataset/otopark-ucretleri','OPEN_DATA_FILE','IZMIR-OPEN-DATA','İzmir Metropolitan Municipality Open Data License','İzmir Metropolitan Municipality / İZELMAN A.Ş.','aging',63072000,15552000,'izelman-tariff-csv-v1','tariffs',TRUE,FALSE,now(),now(),'2024-09-02T00:00:00Z','AGING',TRUE,'izelman')
ON CONFLICT(source_key) DO NOTHING;
