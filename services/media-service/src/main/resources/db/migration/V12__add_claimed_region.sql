-- Normalized claimed parking region on the uploaded image (x/y/width/height in [0,1]).
ALTER TABLE media_files
    ADD COLUMN claimed_region_x DOUBLE PRECISION,
    ADD COLUMN claimed_region_y DOUBLE PRECISION,
    ADD COLUMN claimed_region_width DOUBLE PRECISION,
    ADD COLUMN claimed_region_height DOUBLE PRECISION;

ALTER TABLE media_files
    ADD CONSTRAINT media_files_claimed_region_x_range
        CHECK (claimed_region_x IS NULL OR (claimed_region_x >= 0 AND claimed_region_x <= 1)),
    ADD CONSTRAINT media_files_claimed_region_y_range
        CHECK (claimed_region_y IS NULL OR (claimed_region_y >= 0 AND claimed_region_y <= 1)),
    ADD CONSTRAINT media_files_claimed_region_width_range
        CHECK (claimed_region_width IS NULL OR (claimed_region_width > 0 AND claimed_region_width <= 1)),
    ADD CONSTRAINT media_files_claimed_region_height_range
        CHECK (claimed_region_height IS NULL OR (claimed_region_height > 0 AND claimed_region_height <= 1)),
    ADD CONSTRAINT media_files_claimed_region_bounds
        CHECK (
            claimed_region_x IS NULL
            OR (
                claimed_region_x + claimed_region_width <= 1.0000001
                AND claimed_region_y + claimed_region_height <= 1.0000001
            )
        ),
    ADD CONSTRAINT media_files_claimed_region_all_or_none
        CHECK (
            (claimed_region_x IS NULL AND claimed_region_y IS NULL
                AND claimed_region_width IS NULL AND claimed_region_height IS NULL)
            OR (claimed_region_x IS NOT NULL AND claimed_region_y IS NOT NULL
                AND claimed_region_width IS NOT NULL AND claimed_region_height IS NOT NULL)
        );
