-- U12 / CX-F07: the gamification aggregate version behind each projected value. An older
-- snapshot that arrives later (reordering, redelivery, DLT redrive) must not overwrite a newer
-- one, and points, level and trust score each follow their own version, because a
-- UserLevelChanged event and a later PointsEarned event can arrive in either order.
-- NULL means no versioned snapshot has been applied to that value yet (rows from before U12).
ALTER TABLE user_trust_profiles
    ADD COLUMN points_version BIGINT,
    ADD COLUMN level_version  BIGINT,
    ADD COLUMN trust_version  BIGINT;
