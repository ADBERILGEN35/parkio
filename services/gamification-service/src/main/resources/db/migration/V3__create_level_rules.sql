-- Level thresholds and the access policy each level grants. Seeded here so reward
-- values and access limits are data, not hardcoded business logic (ai-context/02).
-- `max_points` NULL means the top, open-ended level.
CREATE TABLE level_rules (
    level                  INTEGER     NOT NULL,
    min_points             BIGINT      NOT NULL,
    max_points             BIGINT,
    search_radius_meters   INTEGER     NOT NULL,
    result_limit           INTEGER     NOT NULL,
    daily_view_limit       INTEGER     NOT NULL,
    verified_spot_priority BOOLEAN     NOT NULL DEFAULT FALSE,
    notification_priority  BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_level_rules PRIMARY KEY (level)
);

INSERT INTO level_rules
    (level, min_points, max_points, search_radius_meters, result_limit, daily_view_limit,
     verified_spot_priority, notification_priority)
VALUES
    (1,    0,   99,   300,  3,  20,  FALSE, FALSE),
    (2,  100,  299,   500,  5,  40,  FALSE, FALSE),
    (3,  300,  699,  1000, 10,  75,  FALSE, FALSE),
    (4,  700, 1499,  1500, 15, 150,  TRUE,  FALSE),
    (5, 1500, NULL,  2500, 25, 300,  TRUE,  TRUE);
