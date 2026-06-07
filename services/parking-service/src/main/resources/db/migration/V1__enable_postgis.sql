-- parking-service stores spot locations as PostGIS geography for radius search.
-- The extension must exist before the spots table's geography column/index.
CREATE EXTENSION IF NOT EXISTS postgis;
