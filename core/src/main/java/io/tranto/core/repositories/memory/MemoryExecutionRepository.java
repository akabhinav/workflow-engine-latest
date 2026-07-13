package io.tranto.core.repositories.memory;

import io.tranto.core.models.executions.Execution;
import io.tranto.core.repositories.ExecutionRepository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ExecutionRepository} keyed by execution id.
 */
public class MemoryExecutionRepository implements ExecutionRepository {

    private final ConcurrentHashMap<String, Execution> store = new ConcurrentHashMap<>();

    @Override
    public Execution save(final Execution execution) {
        store.put(execution.getId(), execution);
        return execution;
    }

    @Override
    public Optional<Execution> findById(final String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Execution> findAll() {
        return List.copyOf(store.values());
    }
}
