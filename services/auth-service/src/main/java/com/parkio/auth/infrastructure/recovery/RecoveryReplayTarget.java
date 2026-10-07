package com.parkio.auth.infrastructure.recovery;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * What the preflight reads from the restored auth database before any Spring context exists: its
 * identity and the erasures it marks {@code DURABLY_RECORDED}. Both reads use ONE plain JDBC
 * connection in ONE read-only transaction, rolled back on {@link #close()} (PR #295 review N12):
 * the anchor is read from the very database whose identity was checked. Nothing is migrated,
 * written or locked beyond the reads.
 */
interface RecoveryReplayTarget extends AutoCloseable {

    /** The connected database's identity, {@code postgresql:<system_identifier>:<datname>}. */
    String identity();

    /** Every user with a {@code DURABLY_RECORDED} erasure request, whatever the request status. */
    List<UUID> durablyRecordedUsers();

    @Override
    default void close() {
    }

    String IDENTITY = "SELECT 'postgresql:' || system_identifier::text || ':' || current_database()"
            + " FROM pg_control_system()";
    String DURABLY_RECORDED_USERS =
            "SELECT DISTINCT auth_user_id FROM erasure_requests WHERE durable_recording_status = 'DURABLY_RECORDED'";

    /** The target named by the datasource settings, read over one short-lived connection. */
    static RecoveryReplayTarget jdbc(String url, String username, String password) {
        return new RecoveryReplayTarget() {
            private Connection connection;

            @Override
            public String identity() {
                List<String> rows = query(IDENTITY, result -> result.getString(1));
                return rows.size() == 1 ? rows.get(0) : null;
            }

            @Override
            public List<UUID> durablyRecordedUsers() {
                return query(DURABLY_RECORDED_USERS, result -> result.getObject(1, UUID.class));
            }

            private <T> List<T> query(String sql, Row<T> row) {
                try (PreparedStatement statement = connection().prepareStatement(sql)) {
                    statement.setQueryTimeout(60);
                    List<T> rows = new ArrayList<>();
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            rows.add(row.read(result));
                        }
                    }
                    return rows;
                } catch (SQLException ex) {
                    throw new IllegalStateException("target read failed: " + ex.getSQLState(), ex);
                }
            }

            private Connection connection() throws SQLException {
                if (connection == null) {
                    Properties properties = new Properties();
                    if (username != null) {
                        properties.setProperty("user", username);
                    }
                    if (password != null) {
                        properties.setProperty("password", password);
                    }
                    properties.setProperty("connectTimeout", "10");
                    properties.setProperty("socketTimeout", "60");
                    properties.setProperty("ApplicationName", "parkio-recovery-replay-preflight");
                    connection = DriverManager.getConnection(url, properties);
                    connection.setReadOnly(true);
                    connection.setAutoCommit(false);
                }
                return connection;
            }

            @Override
            public void close() {
                if (connection == null) {
                    return;
                }
                try (Connection open = connection) {
                    open.rollback();
                } catch (SQLException ignored) {
                    // nothing was written; closing is enough
                }
            }
        };
    }

    @FunctionalInterface
    interface Row<T> {
        T read(ResultSet result) throws SQLException;
    }
}
