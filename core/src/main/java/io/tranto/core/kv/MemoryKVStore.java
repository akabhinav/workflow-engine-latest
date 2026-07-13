package io.tranto.core.kv;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link KVStore} with per-key expiry, scoped to one namespace. Backing store for the
 * standalone runtime; the distributed runtime swaps in a JDBC-backed implementation behind the same
 * interface. Namespaces are isolated by handing out a distinct instance per namespace (see
 * {@link Factory}).
 */
public class MemoryKVStore implements KVStore {

    private record Entry(Object value, Instant expiresAt) {
        boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Object> get(final String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired()) {
            store.remove(key, entry);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.value());
    }

    @Override
    public void put(final String key, final Object value) {
        put(key, value, null);
    }

    @Override
    public void put(final String key, final Object value, final Duration ttl) {
        Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        store.put(key, new Entry(value, expiresAt));
    }

    @Override
    public boolean delete(final String key) {
        return store.remove(key) != null;
    }

    @Override
    public List<String> list() {
        store.entrySet().removeIf(e -> e.getValue().isExpired());
        return List.copyOf(store.keySet());
    }

    /** Hands out a stable {@link KVStore} per namespace (created on first use). */
    public static class Factory implements KVStoreFactory {
        private final Map<String, KVStore> byNamespace = new ConcurrentHashMap<>();

        /** @return the KV store for {@code namespace} (created on first request). */
        public KVStore forNamespace(final String namespace) {
            return byNamespace.computeIfAbsent(namespace == null ? "" : namespace, k -> new MemoryKVStore());
        }
    }
}
