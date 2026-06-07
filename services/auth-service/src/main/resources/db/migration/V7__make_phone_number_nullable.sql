-- Phone number becomes optional at registration. The column is now nullable;
-- the existing uq_auth_users_phone_number UNIQUE constraint is kept and, per
-- standard SQL, treats NULLs as distinct — so many users may have no phone
-- number while any present phone number remains unique.
ALTER TABLE auth_users ALTER COLUMN phone_number DROP NOT NULL;
