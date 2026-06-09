-- Access URLs are never persisted: signed GET URLs are short-lived and generated
-- per authorized request (GET /api/v1/media/{mediaId}/access-url). The column was
-- always NULL; dropping it removes any chance of a stored, long-lived URL leaking
-- storage internals (ai-context/07).
ALTER TABLE media_files DROP COLUMN access_url;
