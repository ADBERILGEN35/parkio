-- CL-F38a: last_confirmed_at was added in V17 as TIMESTAMP without a time zone, while the
-- entity maps it to Instant and parking-service writes it as a UTC wall clock
-- (hibernate.jdbc.time_zone: UTC). SQL that compares it with TIMESTAMPTZ values converts it
-- through the session time zone, so its meaning depended on that zone.
--
-- V17 back-filled ACTIVE rows with started_at (TIMESTAMPTZ) converted through the session
-- time zone of that migration. Where that zone was not UTC the back-filled values are off by
-- the zone offset. Repair exactly that signature first: a value equal to started_at in the
-- current session zone but not in UTC. This assumes this migration runs in the same session
-- zone as V17 did; with a UTC session (the service images' default) it changes no row.
UPDATE parking_sessions
SET last_confirmed_at = started_at AT TIME ZONE 'UTC'
WHERE last_confirmed_at = started_at AT TIME ZONE current_setting('TimeZone')
  AND last_confirmed_at <> started_at AT TIME ZONE 'UTC';

-- Every other value was written by the application as a UTC wall clock.
ALTER TABLE parking_sessions
    ALTER COLUMN last_confirmed_at TYPE TIMESTAMPTZ
    USING last_confirmed_at AT TIME ZONE 'UTC';
