CREATE TABLE waitlist_interest (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    email_hash VARCHAR(64) NOT NULL,
    consent_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    city VARCHAR(120),
    role VARCHAR(32),
    source VARCHAR(80) NOT NULL,
    ip_hash VARCHAR(64) NOT NULL,
    user_agent_hash VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_waitlist_interest_email_hash UNIQUE (email_hash),
    CONSTRAINT chk_waitlist_interest_role CHECK (role IS NULL OR role IN ('driver', 'tester', 'partner')),
    CONSTRAINT chk_waitlist_interest_source CHECK (source IN ('parkio.dev-landing'))
);

CREATE INDEX idx_waitlist_interest_created_at ON waitlist_interest (created_at);
