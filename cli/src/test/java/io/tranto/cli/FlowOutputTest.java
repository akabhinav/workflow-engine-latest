package io.tranto.cli;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.StandaloneEngine;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.plugin.core.CorePlugins;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Flow outputs are rendered at termination from inputs and from task outputs. */
class FlowOutputTest {

    @Test
    void rendersOutputsFromInputsAndTaskOutputs() throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        Flow flow = new YamlFlowParser(new JacksonMapper(registry)).parse("""
            id: outs
            namespace: dev
            inputs:
              - id: who
                type: STRING
                defaults: alice
            outputs:
              - id: greeting
                value: "hello {{ inputs.who }}"
              - id: fromTask
                value: "{{ outputs.ret.value }}"
            tasks:
              - id: ret
                type: io.tranto.plugin.core.debug.Return
                format: "returned-value"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, Map.of(), Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(execution.getOutputs()).isNotNull();
            assertThat(execution.getOutputs().get("greeting")).isEqualTo("hello alice");
            assertThat(execution.getOutputs().get("fromTask")).isEqualTo("returned-value");
        }
    }
}
