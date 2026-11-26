-- Synthetic PA-06 community spots for disposable CI only.
-- Inserts TWO visible spots near PublicExplore default center so the
-- k-anonymity threshold (<3) continues to withhold communitySpotCountInScope as JSON null.
-- Does not use production data.
-- Column set mirrors ModeAPostgisSpatialParityIT insert.

CREATE EXTENSION IF NOT EXISTS postgis;

DELETE FROM parking_spots
WHERE id IN (
  '11111111-1111-1111-1111-111111111101'::uuid,
  '11111111-1111-1111-1111-111111111102'::uuid
);

INSERT INTO parking_spots (
  id, owner_user_id, media_id, latitude, longitude, address_text, description,
  suitable_vehicle_types, parking_context, legal_status, status,
  confidence_score, verification_count, filled_report_count, expires_at,
  moderation_deadline_at, activated_at, moderation_decided_at, version
) VALUES
(
  '11111111-1111-1111-1111-111111111101'::uuid,
  '22222222-2222-2222-2222-222222222201'::uuid,
  '33333333-3333-3333-3333-333333333301'::uuid,
  38.4238, 27.1429, 'ci-seed-1', 'pa06',
  'SEDAN', 'STREET_PARKING', 'LEGAL', 'ACTIVE',
  1.0, 0, 0, now() + interval '2 hours',
  now(), now(), now(), 0
),
(
  '11111111-1111-1111-1111-111111111102'::uuid,
  '22222222-2222-2222-2222-222222222201'::uuid,
  '33333333-3333-3333-3333-333333333302'::uuid,
  38.4239, 27.1430, 'ci-seed-2', 'pa06',
  'SEDAN', 'STREET_PARKING', 'LEGAL', 'ACTIVE',
  1.0, 0, 0, now() + interval '2 hours',
  now(), now(), now(), 0
);

SELECT count(*) AS seeded_visible_near_center
FROM parking_spots
WHERE status IN ('ACTIVE', 'VERIFIED')
  AND legal_status <> 'ILLEGAL_OR_RISKY'
  AND expires_at > now()
  AND ST_DWithin(
    location,
    ST_SetSRID(ST_MakePoint(27.1428, 38.4237), 4326)::geography,
    5000
  );
