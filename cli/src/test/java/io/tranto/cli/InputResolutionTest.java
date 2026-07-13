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

/** Typed inputs: defaults applied, values coerced to their declared type, required ones enforced. */
class InputResolutionTest {

    private Flow parse() throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new YamlFlowParser(new JacksonMapper(registry)).parse("""
            id: greet
            namespace: dev
            inputs:
              - id: name
                type: STRING
                defaults: world
              - id: count
                type: INT
            tasks:
              - id: echo
                type: io.tranto.plugin.core.debug.Return
                format: "{{ inputs.name }}-{{ inputs.count }}"
            """);
    }

    @Test
    void appliesDefaultsAndCoercesTypes() throws Exception {
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(parse(), Map.of("count", "3"), Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(execution.getInputs().get("name")).isEqualTo("world");   // default applied
            assertThat(execution.getInputs().get("count")).isEqualTo(3L);       // coerced STRING -> INT
            // The rendered value confirms the coerced inputs flowed into templating.
            Object outputs = execution.findTaskRunByTaskId("echo").get().getOutputs();
            assertThat(outputs.toString()).contains("world-3");
        }
    }

    @Test
    void failsWhenRequiredInputMissing() throws Exception {
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(parse(), Map.of(), Duration.ofSeconds(15));
            assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
            assertThat(execution.getTaskRunList()).isEmpty(); // rejected before any task ran
        }
    }

    @Test
    void failsWhenValueCannotBeCoerced() throws Exception {
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(parse(), Map.of("count", "not-a-number"), Duration.ofSeconds(15));
            assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
        }
    }
}
