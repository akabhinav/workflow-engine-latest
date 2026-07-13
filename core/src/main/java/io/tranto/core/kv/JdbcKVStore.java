package io.tranto.core.kv;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tranto.core.jdbc.JdbcDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC-backed {@link KVStore}, scoped to one namespace, rows in the shared {@code kv_store} table.
 * Unlike {@link MemoryKVStore}, a value written on one node is immediately visible on every other
 * node — the fix for "a {@code kv.Set} on node A is invisible to node B" (Landmine #3). Values are
 * stored as JSON; expiry is a per-row {@code expires_at} evaluated lazily on read.
 */
public class JdbcKVStore implements KVStore {

    private final JdbcDatabase database;
    private final ObjectMapper mapper;
    private final String namespace;

    public JdbcKVStore(final JdbcDatabase database, final ObjectMapper mapper, final String namespace) {
        this.database = database;
        this.mapper = mapper;
        this.namespace = namespace == null ? "" : namespace;
    }

    @Override
    public Optional<Object> get(final String key) {
        String sql = "SELECT data, expires_at FROM kv_store WHERE namespace = ? AND kv_key = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp expiresAt = rs.getTimestamp("expires_at");
                if (expiresAt != null && Instant.now().isAfter(expiresAt.toInstant())) {
                    delete(key);
                    return Optional.empty();
                }
                return Optional.ofNullable(mapper.readValue(rs.getString("data"), Object.class));
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to read KV " + namespace + "/" + key, e);
        }
    }

    @Override
    public void put(final String key, final Object value) {
        put(key, value, null);
    }

    @Override
    public void put(final String key, final Object value, final Duration ttl) {
        Timestamp expiresAt = ttl == null ? null : Timestamp.from(Instant.now().plus(ttl));
        String sql = "MERGE INTO kv_store (namespace, kv_key, data, expires_at) VALUES (?, ?, ?, ?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            statement.setString(3, mapper.writeValueAsString(value));
            statement.setTimestamp(4, expiresAt);
            statement.executeUpdate();
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to write KV " + namespace + "/" + key, e);
        }
    }

    @Override
    public boolean delete(final String key) {
        String sql = "DELETE FROM kv_store WHERE namespace = ? AND kv_key = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete KV " + namespace + "/" + key, e);
        }
    }

    @Override
    public List<String> list() {
        String sql = "SELECT kv_key FROM kv_store WHERE namespace = ? AND (expires_at IS NULL OR expires_at > ?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            try (ResultSet rs = statement.executeQuery()) {
                List<String> keys = new ArrayList<>();
                while (rs.next()) {
                    keys.add(rs.getString("kv_key"));
                }
                return keys;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list KV namespace " + namespace, e);
        }
    }

    /** Hands out a JDBC {@link KVStore} per namespace, all sharing one database. */
    public static class Factory implements KVStoreFactory {
        private final JdbcDatabase database;
        private final ObjectMapper mapper;
        private final ConcurrentHashMap<String, KVStore> byNamespace = new ConcurrentHashMap<>();

        public Factory(final JdbcDatabase database, final ObjectMapper mapper) {
            this.database = database;
            this.mapper = mapper;
        }

        @Override
        public KVStore forNamespace(final String namespace) {
            return byNamespace.computeIfAbsent(namespace == null ? "" : namespace,
                ns -> new JdbcKVStore(database, mapper, ns));
        }
    }
}
