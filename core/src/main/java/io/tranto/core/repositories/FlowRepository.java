package io.tranto.core.repositories;

import io.tranto.core.models.flows.Flow;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for flow definitions. The JDBC implementation stores the flow as a JSON
 * blob with generated columns; the in-memory implementation backs {@code server local}/tests.
 */
public interface FlowRepository {

    /** Store (create or replace) a flow. */
    Flow save(Flow flow);

    /** Find a flow by its natural key. Revision {@code null} means "latest". */
    Optional<Flow> findById(String tenantId, String namespace, String id, Integer revision);

    /** @return every registered flow (used to evaluate flow triggers and by the API's list view). */
    List<Flow> findAll();
}
