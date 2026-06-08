-- Reward point values keyed by a stable rule key (event + recipient role). The
-- handlers select a rule key; the point VALUE comes from this table, not code
-- (ai-context/02). Seeded with the canonical Parkio rewards.
CREATE TABLE reward_rules (
    rule_key    VARCHAR(64)  NOT NULL,
    source_type VARCHAR(48)  NOT NULL,
    points      INTEGER      NOT NULL,
    description VARCHAR(255),
    CONSTRAINT pk_reward_rules PRIMARY KEY (rule_key)
);

INSERT INTO reward_rules (rule_key, source_type, points, description) VALUES
    ('PARKING_UPLOAD_OWNER',      'PARKING_UPLOAD',   5,  'Owner reward for submitting a spot'),
    ('PARKING_VERIFIED_OWNER',    'PARKING_VERIFIED', 20, 'Owner reward when a spot is verified available'),
    ('PARKING_VERIFIED_VERIFIER', 'PARKING_VERIFIED', 5,  'Verifier reward for confirming a spot available'),
    ('PARKING_CLAIMED_OWNER',     'PARKING_CLAIMED',  30, 'Owner reward when their spot is claimed'),
    ('PARKING_CLAIMED_CLAIMER',   'PARKING_CLAIMED',  10, 'Claimer reward for successfully claiming a spot');
