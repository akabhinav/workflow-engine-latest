package io.tranto.core.repositories.memory;

import io.tranto.core.models.flows.Flow;
import io.tranto.core.repositories.FlowRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link FlowRepository}. Keyed by {@code tenant|namespace|id} and keeps only the last
 * saved flow per key (revision-latest); enough for {@code server local} and tests.
 */
public class MemoryFlowRepository implements FlowRepository {

    private final ConcurrentHashMap<String, Flow> store = new ConcurrentHashMap<>();

    @Override
    public Flow save(final Flow flow) {
        store.put(key(flow.getTenantId(), flow.getNamespace(), flow.getId()), flow);
        return flow;
    }

    @Override
    public Optional<Flow> findById(final String tenantId, final String namespace,
                                   final String id, final Integer revision) {
        return Optional.ofNullable(store.get(key(tenantId, namespace, id)));
    }

    @Override
    public List<Flow> findAll() {
        return List.copyOf(store.values());
    }

    private static String key(final String tenantId, final String namespace, final String id) {
        return (tenantId == null ? "" : tenantId) + "|" + namespace + "|" + id;
    }
}
