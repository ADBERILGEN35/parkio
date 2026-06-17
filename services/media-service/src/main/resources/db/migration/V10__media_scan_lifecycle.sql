-- Sprint 4: malware-scan media lifecycle.
--
-- The media status vocabulary changed: a file is only servable once it is READY,
-- which now requires a clean malware scan. The pre-scan states map as follows:
--   UPLOADED  -> PENDING_SCAN   (awaiting/undergoing the scan; not servable)
--   VALIDATED -> READY          (previously the servable state)
-- REJECTED and DELETED are unchanged. This is a data-only normalization; the
-- column type (VARCHAR) is unchanged and Hibernate validates the mapping only.
UPDATE media_files SET status = 'READY'        WHERE status = 'VALIDATED';
UPDATE media_files SET status = 'PENDING_SCAN' WHERE status = 'UPLOADED';

-- Supports the media_pending_scan_count gauge and any "stuck PENDING_SCAN" sweeps.
CREATE INDEX IF NOT EXISTS idx_media_files_status ON media_files (status);
