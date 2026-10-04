-- CL-F38c (owner decision B10e, 2026-10-03): duplicate detection is per owner and ignores
-- deleted files. The global UNIQUE (checksum) let any uploader learn that someone else had
-- already uploaded a file, and blocked re-uploading a file after deleting it. The partial index
-- keeps at most one live file per owner and checksum, the backstop for two uploads that pass the
-- application check concurrently. Existing rows satisfy it: checksums were globally unique.
ALTER TABLE media_files DROP CONSTRAINT uq_media_files_checksum;

CREATE UNIQUE INDEX uq_media_files_owner_checksum_live
    ON media_files (owner_user_id, checksum)
    WHERE status <> 'DELETED';
