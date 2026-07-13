package io.tranto.core.repositories;

import io.tranto.core.models.executions.Execution;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for executions.
 */
public interface ExecutionRepository {

    /** Store (create or replace) an execution. */
    Execution save(Execution execution);

    /** Find an execution by id. */
    Optional<Execution> findById(String id);

    /** @return all executions (for the API's list view; production adds pagination + filters). */
    List<Execution> findAll();
}
