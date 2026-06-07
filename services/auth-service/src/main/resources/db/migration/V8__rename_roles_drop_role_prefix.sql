-- Standardize role names to the unprefixed form (USER / MODERATOR / ADMIN),
-- matching the RoleName domain enum and the JWT `roles` claim. The Spring
-- Security "ROLE_" prefix is applied only when building authorities, not stored.
-- Role ids are unchanged, so auth_user_roles links remain valid.
UPDATE roles SET name = 'USER'      WHERE name = 'ROLE_USER';
UPDATE roles SET name = 'MODERATOR' WHERE name = 'ROLE_MODERATOR';
UPDATE roles SET name = 'ADMIN'     WHERE name = 'ROLE_ADMIN';
