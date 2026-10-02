package com.parkio.auth.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The database identity durable erasure evidence is pinned to:
 * {@code postgresql:<system_identifier>:<datname>}. {@code system_identifier} is set by initdb
 * and survives physical replication and failover, so it names the cluster lineage, not a DSN.
 * {@code pg_control_system()} is readable without superuser rights (PostgreSQL 16).
 */
public final class PostgresDatabaseIdentity {

    private PostgresDatabaseIdentity() {
    }

    public static String of(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT 'postgresql:' || system_identifier::text || ':' || current_database() FROM pg_control_system()",
                String.class);
    }
}
