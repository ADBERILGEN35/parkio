-- CL-F17 / PRIV-002: the retention cleanup deletes the oldest search and view logs in bounded
-- batches ordered by created_at. The existing indexes lead with searcher_user_id / spot_id, so
-- each table gets a created_at index for the cleanup's range scan. No rows change here.
CREATE INDEX IF NOT EXISTS idx_parking_spot_search_logs_created_at
    ON parking_spot_search_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_parking_spot_view_logs_created_at
    ON parking_spot_view_logs (created_at);
