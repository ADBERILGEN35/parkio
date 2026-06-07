-- Join table for the many-to-many between auth users and roles.
-- Foreign keys stay within auth-service's own database (no cross-service FKs).
CREATE TABLE auth_user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    CONSTRAINT pk_auth_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_aur_user FOREIGN KEY (user_id) REFERENCES auth_users (id) ON DELETE CASCADE,
    CONSTRAINT fk_aur_role FOREIGN KEY (role_id) REFERENCES roles (id)
);
