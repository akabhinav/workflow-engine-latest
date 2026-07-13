package io.tranto.plugin.core.trigger;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.triggers.AbstractTrigger;
import io.tranto.core.models.triggers.FlowListener;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Locale;

/**
 * Starts this flow when another flow finishes — the building block for event-driven pipelines
 * (flow B runs automatically after flow A succeeds). Match by upstream {@code namespace}/{@code flowId}
 * and the terminal {@code states} to react to (defaults to {@code SUCCESS}).
 *
 * <pre>
 * triggers:
 *   - id: after_ingest
 *     type: io.tranto.plugin.core.trigger.FlowTrigger
 *     namespace: data
 *     flowId: ingest
 *     states: [SUCCESS]
 * </pre>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Trigger this flow when another flow finishes")
public class FlowTrigger extends AbstractTrigger implements FlowListener {

    /** Upstream flow namespace to listen to (null = any). */
    @PluginProperty
    private String namespace;

    /** Upstream flow id to listen to (null = any). */
    @PluginProperty
    private String flowId;

    /** Terminal states to react to; defaults to {@code [SUCCESS]}. */
    @PluginProperty
    private List<String> states;

    @Override
    public boolean matchesUpstream(final String upstreamNamespace, final String upstreamFlowId,
                                   final StateType state) {
        if (namespace != null && !namespace.equals(upstreamNamespace)) {
            return false;
        }
        if (flowId != null && !flowId.equals(upstreamFlowId)) {
            return false;
        }
        List<String> wanted = (states == null || states.isEmpty()) ? List.of("SUCCESS") : states;
        return wanted.stream().anyMatch(s -> s.toUpperCase(Locale.ROOT).equals(state.name()));
    }
}
