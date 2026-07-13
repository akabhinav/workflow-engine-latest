package io.tranto.core.kv;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A namespace-scoped key/value store: durable state that outlives a single execution (counters,
 * cursors, cached lookups, cross-flow handoffs). Values are arbitrary JSON-serialisable objects,
 * optionally with a time-to-live.
 *
 * <p>Stable SDK surface — the engine provides the implementation (in-memory for the standalone
 * runtime, JDBC/object-store for distributed deployments); plugins reach it via
 * {@code runContext.kv()}.</p>
 */
public interface KVStore {

    /** @return the stored value for {@code key}, if present and not expired. */
    Optional<Object> get(String key);

    /** Store {@code value} under {@code key} with no expiry. */
    void put(String key, Object value);

    /** Store {@code value} under {@code key}, expiring after {@code ttl} (null = no expiry). */
    void put(String key, Object value, Duration ttl);

    /** Remove {@code key}. @return true if it existed. */
    boolean delete(String key);

    /** @return all live keys in this namespace. */
    List<String> list();
}
