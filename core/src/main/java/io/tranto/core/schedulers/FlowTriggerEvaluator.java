package io.tranto.core.schedulers;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.models.triggers.AbstractTrigger;
import io.tranto.core.models.triggers.FlowListener;
import io.tranto.core.repositories.FlowRepository;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reacts to finished executions by firing dependent flows. When any execution reaches a terminal
 * state, this scans registered flows for {@link FlowListener} triggers (e.g. {@code FlowTrigger})
 * that match the upstream namespace/flow/state, and starts an execution of each match — the
 * mechanism behind event-driven, flow-to-flow pipelines.
 *
 * <p>Evaluates against the SDK {@link FlowListener} contract, so the engine never depends on the
 * concrete trigger plugin. A flow is never triggered by its own executions (guards the obvious cycle).</p>
 */
public class FlowTriggerEvaluator {

    private final FlowRepository flowRepository;
    private final Consumer<Flow> startFlow;

    public FlowTriggerEvaluator(final FlowRepository flowRepository, final Consumer<Flow> startFlow) {
        this.flowRepository = flowRepository;
        this.startFlow = startFlow;
    }

    /** Called for every execution that reaches a terminal state. */
    public void onTerminal(final Execution execution) {
        StateType state = execution.getState().current();
        for (Flow flow : flowRepository.findAll()) {
            if (flow.getTriggers() == null) {
                continue;
            }
            // Never let a flow trigger on its own executions.
            if (flow.getId().equals(execution.getFlowId())
                && Objects.equals(flow.getNamespace(), execution.getNamespace())) {
                continue;
            }
            for (AbstractTrigger trigger : flow.getTriggers()) {
                if (!trigger.isDisabled()
                    && trigger instanceof FlowListener listener
                    && listener.matchesUpstream(execution.getNamespace(), execution.getFlowId(), state)) {
                    startFlow.accept(flow);
                    break; // one matching trigger is enough to start the flow once
                }
            }
        }
    }
}
