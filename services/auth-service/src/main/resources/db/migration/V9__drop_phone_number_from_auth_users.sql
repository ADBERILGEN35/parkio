-- Phone number is profile data, owned by user-service (ai-context/03). It was
-- never used for authentication, so it is removed from auth-service entirely;
-- email is the sole login identifier. Dropping the column also drops the
-- dependent uq_auth_users_phone_number UNIQUE constraint.
ALTER TABLE auth_users DROP COLUMN phone_number;
