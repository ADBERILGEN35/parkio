-- Authorization roles. Seeded with the fixed Parkio role set; names map to the
-- RoleName enum in the domain. New users receive ROLE_USER on registration.
CREATE TABLE roles (
    id   UUID        NOT NULL,
    name VARCHAR(64) NOT NULL,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_name UNIQUE (name)
);

INSERT INTO roles (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'ROLE_USER'),
    ('00000000-0000-0000-0000-000000000002', 'ROLE_MODERATOR'),
    ('00000000-0000-0000-0000-000000000003', 'ROLE_ADMIN');
