package io.tranto.core.runners;

import io.tranto.core.exceptions.IllegalVariableEvaluationException;
import io.tranto.core.kv.KVStore;
import io.tranto.core.storages.StorageInterface;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * The runtime bridge handed to a task/trigger when it executes. It is the ONLY way a plugin
 * touches the engine: rendering expressions, logging, (later) storage, metrics, KV and secrets.
 *
 * <p>This is a stable SDK interface — the engine supplies the implementation
 * ({@code DefaultRunContext} in {@code tranto-core}). Methods are added over the phases
 * (storage, metrics, kv, secrets) but only additively, with default methods, so existing
 * plugins keep compiling.</p>
 */
public interface RunContext {

    /**
     * Render a Pebble expression against this context's variables.
     *
     * @param expression the template string (may contain {@code {{ ... }}} / {@code {% ... %}})
     * @return the rendered string
     */
    String render(String expression) throws IllegalVariableEvaluationException;

    /**
     * Render every string value of a map (keys and non-string values pass through).
     */
    Map<String, Object> render(Map<String, Object> in) throws IllegalVariableEvaluationException;

    /**
     * Render every string element of a list.
     */
    List<String> render(List<String> in) throws IllegalVariableEvaluationException;

    /**
     * @return the immutable variable map available to expressions (flow, execution, task,
     *         inputs, outputs, env, globals).
     */
    Map<String, Object> getVariables();

    /**
     * @return the logger a task should use; its lines are captured and streamed to the UI.
     */
    Logger logger();

    /**
     * @return the internal storage handle for reading/writing files and large payloads.
     * @throws UnsupportedOperationException if the runtime provides no storage (overridden by the engine)
     */
    default StorageInterface storage() {
        throw new UnsupportedOperationException("Storage is not available in this run context");
    }

    /**
     * @return the key/value store scoped to this run's flow namespace.
     * @throws UnsupportedOperationException if the runtime provides no KV store (overridden by the engine)
     */
    default KVStore kv() {
        throw new UnsupportedOperationException("KV store is not available in this run context");
    }
}
