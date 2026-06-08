-- Penalty point values (positive magnitudes, applied as deductions) keyed by a
-- stable rule key. PARKING_REJECTED_OWNER is wired now; the fake/spam penalties are
-- seeded for future moderation flows but not yet wired.
CREATE TABLE penalty_rules (
    rule_key    VARCHAR(64)  NOT NULL,
    source_type VARCHAR(48)  NOT NULL,
    points      INTEGER      NOT NULL,
    description VARCHAR(255),
    CONSTRAINT pk_penalty_rules PRIMARY KEY (rule_key)
);

INSERT INTO penalty_rules (rule_key, source_type, points, description) VALUES
    ('PARKING_REJECTED_OWNER', 'PENALTY_ILLEGAL_RISK', 25, 'Owner penalty for an illegal/risky/fake spot'),
    ('PENALTY_FAKE',           'PENALTY_FAKE',         25, 'Penalty for a fake submission (not yet wired)'),
    ('PENALTY_SPAM',           'PENALTY_SPAM',         15, 'Penalty for spam behaviour (not yet wired)');
