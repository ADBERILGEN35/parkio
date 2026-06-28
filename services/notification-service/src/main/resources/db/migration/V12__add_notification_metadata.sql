-- Frontend action/deeplink hints for owned notifications. Keep this privacy-safe:
-- Smart Return metadata must not include exact home address text or home coordinates.
ALTER TABLE notifications
    ADD COLUMN metadata TEXT NOT NULL DEFAULT '{}';
