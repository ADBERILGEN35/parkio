-- Trust-score adjustments (signed deltas) keyed by a stable rule key, mirroring the
-- reward/penalty rule tables: values are data, not code (ai-context/02). Positive
-- deltas reward reliable contributions; negative deltas penalise rejections and
-- admin-applied trust penalties.
CREATE TABLE trust_rules (
    rule_key    VARCHAR(64)  NOT NULL,
    delta       INTEGER      NOT NULL,
    description VARCHAR(255),
    CONSTRAINT pk_trust_rules PRIMARY KEY (rule_key)
);

INSERT INTO trust_rules (rule_key, delta, description) VALUES
    ('TRUST_SPOT_VERIFIED_OWNER',  2,   'Owner trust gain when a spot is verified available'),
    ('TRUST_SPOT_CLAIMED_OWNER',   1,   'Owner trust gain when a spot is successfully claimed'),
    ('TRUST_SPOT_REJECTED_OWNER',  -10, 'Owner trust loss when a moderator rejects a spot'),
    ('TRUST_MODERATION_PENALTY',   -15, 'Admin-applied REDUCE_TRUST moderation penalty');
