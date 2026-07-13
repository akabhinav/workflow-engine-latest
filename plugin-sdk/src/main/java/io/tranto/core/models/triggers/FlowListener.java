package io.tranto.core.models.triggers;

import io.tranto.core.models.execution.StateType;

/**
 * An event-based trigger that fires when <em>another</em> flow's execution reaches a terminal state.
 * The engine, on every terminal execution, asks each registered flow-listener trigger whether that
 * upstream execution matches — if so, it starts an execution of the listener's owning flow.
 *
 * <p>Kept in the SDK (like {@link Schedulable}) so the engine can evaluate it without depending on
 * the concrete plugin — preserving the stability wall.</p>
 */
public interface FlowListener {

    /**
     * @param namespace the upstream execution's flow namespace
     * @param flowId    the upstream execution's flow id
     * @param state     the upstream execution's terminal state
     * @return true if this trigger should fire in response
     */
    boolean matchesUpstream(String namespace, String flowId, StateType state);
}
