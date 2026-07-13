package io.tranto.core.storages;

import io.tranto.core.jdbc.JdbcDatabase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC-backed {@link StorageInterface}: objects are BLOB rows in the shared {@code storage_objects}
 * table, addressed by {@code tranto:///key} URIs. Unlike {@link LocalStorage} (per-node filesystem),
 * an object written on one node is readable on every node — the shared-storage half of Landmine #3.
 * A production deployment would point at S3/GCS/Azure instead; the table backend keeps the
 * single-database dev/cluster setup fully self-contained.
 */
public class JdbcStorage implements StorageInterface {

    private static final String SCHEME = "tranto";

    private final JdbcDatabase database;

    public JdbcStorage(final JdbcDatabase database) {
        this.database = database;
    }

    @Override
    public InputStream get(final URI uri) throws IOException {
        String key = keyOf(uri);
        String sql = "SELECT data FROM storage_objects WHERE obj_key = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IOException("No object at " + uri);
                }
                return new ByteArrayInputStream(rs.getBytes("data"));
            }
        } catch (SQLException e) {
            throw new IOException("Failed to read " + uri, e);
        }
    }

    @Override
    public boolean exists(final URI uri) {
        String sql = "SELECT 1 FROM storage_objects WHERE obj_key = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, keyOf(uri));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to stat " + uri, e);
        }
    }

    @Override
    public URI put(final String key, final InputStream data) throws IOException {
        String normalized = normalize(key);
        byte[] bytes;
        try (data) {
            bytes = data.readAllBytes();
        }
        String sql = "MERGE INTO storage_objects (obj_key, data) VALUES (?, ?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized);
            statement.setBytes(2, bytes);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Failed to store " + key, e);
        }
        return uriFor(normalized);
    }

    @Override
    public boolean delete(final URI uri) throws IOException {
        String sql = "DELETE FROM storage_objects WHERE obj_key = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, keyOf(uri));
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IOException("Failed to delete " + uri, e);
        }
    }

    @Override
    public List<URI> list(final String prefix) throws IOException {
        String sql = "SELECT obj_key FROM storage_objects WHERE obj_key LIKE ? ORDER BY obj_key";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalize(prefix) + "%");
            try (ResultSet rs = statement.executeQuery()) {
                List<URI> uris = new ArrayList<>();
                while (rs.next()) {
                    uris.add(uriFor(rs.getString("obj_key")));
                }
                return uris;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to list " + prefix, e);
        }
    }

    @Override
    public long size(final URI uri) throws IOException {
        String sql = "SELECT LENGTH(data) AS len FROM storage_objects WHERE obj_key = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, keyOf(uri));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IOException("No object at " + uri);
                }
                return rs.getLong("len");
            }
        } catch (SQLException e) {
            throw new IOException("Failed to size " + uri, e);
        }
    }

    private static String keyOf(final URI uri) {
        String key = uri.getScheme() == null ? uri.getPath() : uri.getSchemeSpecificPart();
        return normalize(key);
    }

    private static String normalize(final String key) {
        String k = key == null ? "" : key.replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        return k;
    }

    private static URI uriFor(final String key) {
        return URI.create(SCHEME + ":///" + key);
    }
}
