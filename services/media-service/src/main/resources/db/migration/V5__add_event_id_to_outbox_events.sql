-- Promote the domain event's eventId to a first-class column so the (future) Kafka
-- relay reads the dedup key without parsing the JSON payload (ai-context/06).
-- Forward-only: existing rows are backfilled from payload.eventId; a partial unique
-- index guards against accidental double-publish. The relay is NOT implemented here.
ALTER TABLE outbox_events ADD COLUMN event_id UUID;

UPDATE outbox_events
   SET event_id = (payload::jsonb ->> 'eventId')::uuid
 WHERE event_id IS NULL;

CREATE UNIQUE INDEX uq_outbox_events_event_id ON outbox_events (event_id) WHERE event_id IS NOT NULL;
