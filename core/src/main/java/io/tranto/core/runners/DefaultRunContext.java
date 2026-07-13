package io.tranto.core.runners;

import io.tranto.core.exceptions.IllegalVariableEvaluationException;
import io.tranto.core.kv.KVStore;
import io.tranto.core.storages.StorageInterface;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's implementation of the SDK {@link RunContext}. Holds the variable map for one
 * task/trigger execution and delegates rendering to a shared {@link VariableRenderer}.
 *
 * <p>Immutable view: the variable map is copied defensively and never mutated after construction.
 * Storage, metrics, KV and secrets are added to this class in later phases (the SDK interface
 * grows additively with default methods so plugins keep compiling).</p>
 */
public class DefaultRunContext implements RunContext {

    private final VariableRenderer renderer;
    private final Map<String, Object> variables;
    private final Logger logger;
    private final StorageInterface storage;
    private final KVStore kv;

    public DefaultRunContext(final VariableRenderer renderer,
                             final Map<String, Object> variables,
                             final Logger logger) {
        this(renderer, variables, logger, null, null);
    }

    public DefaultRunContext(final VariableRenderer renderer,
                             final Map<String, Object> variables,
                             final Logger logger,
                             final StorageInterface storage,
                             final KVStore kv) {
        this.renderer = renderer;
        this.variables = variables == null ? Map.of() : Map.copyOf(variables);
        this.logger = logger;
        this.storage = storage;
        this.kv = kv;
    }

    @Override
    public String render(final String expression) throws IllegalVariableEvaluationException {
        return renderer.render(expression, variables);
    }

    @Override
    public Map<String, Object> render(final Map<String, Object> in) throws IllegalVariableEvaluationException {
        if (in == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), v instanceof String s ? render(s) : v);
        }
        return out;
    }

    @Override
    public List<String> render(final List<String> in) throws IllegalVariableEvaluationException {
        if (in == null) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>(in.size());
        for (String s : in) {
            out.add(render(s));
        }
        return out;
    }

    @Override
    public Map<String, Object> getVariables() {
        return variables;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public StorageInterface storage() {
        if (storage == null) {
            return RunContext.super.storage();
        }
        return storage;
    }

    @Override
    public KVStore kv() {
        if (kv == null) {
            return RunContext.super.kv();
        }
        return kv;
    }
}
