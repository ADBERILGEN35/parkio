package com.parkio.auth.infrastructure.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Test trust documents (synthetic keys only): the JSON an operator would mount for the
 * object-lock store, written to a temporary file, and the database identity of a disposable
 * PostgreSQL container read the way the service reads it.
 */
public final class TrustDocuments {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TrustDocuments() {
    }

    /** {@code postgresql:<system_identifier>:<datname>} of {@code postgres}. */
    public static String identity(PostgreSQLContainer<?> postgres) {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT 'postgresql:' || system_identifier::text || ':' || current_database() FROM pg_control_system()")) {
            row.next();
            return row.getString(1);
        } catch (SQLException ex) {
            throw new IllegalStateException("could not read the database identity", ex);
        }
    }

    public static byte[] json(String databaseIdentity, TrustedKey... keys) {
        ObjectNode root = JSON.createObjectNode();
        root.put("format", EvidenceTrust.FORMAT);
        root.put("version", EvidenceTrust.VERSION);
        root.put("databaseIdentity", databaseIdentity);
        ArrayNode list = root.putArray("keys");
        for (TrustedKey key : keys) {
            ObjectNode entry = list.addObject();
            entry.put("keyId", key.keyId());
            entry.put("producerId", key.producerId());
            entry.put("notBefore", key.notBefore().toString());
            if (key.notAfter() != null) {
                entry.put("notAfter", key.notAfter().toString());
            }
            entry.put("retired", key.retired());
            entry.put("keyHex", HexFormat.of().formatHex(key.key()));
        }
        try {
            return JSON.writeValueAsBytes(root);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Writes the trust document to a temporary file and returns its path. */
    public static Path write(String databaseIdentity, TrustedKey... keys) {
        try {
            Path file = Files.createTempFile("parkio-erasure-trust-", ".json");
            file.toFile().deleteOnExit();
            Files.write(file, json(databaseIdentity, keys));
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
