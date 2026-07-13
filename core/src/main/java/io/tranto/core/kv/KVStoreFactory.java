package io.tranto.core.kv;

/**
 * Hands out a {@link KVStore} scoped to a namespace. The standalone runtime uses an in-memory
 * factory ({@link MemoryKVStore.Factory}); the distributed runtime uses {@link JdbcKVStore.Factory}
 * so a value written on one node is visible on every node. {@link io.tranto.core.runners.RunContextFactory}
 * holds one of these behind this interface, so swapping the backend changes nothing for tasks.
 */
public interface KVStoreFactory {

    /** @return the KV store for {@code namespace}. */
    KVStore forNamespace(String namespace);
}
