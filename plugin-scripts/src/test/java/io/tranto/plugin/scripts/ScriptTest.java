package io.tranto.plugin.scripts;

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

/**
 * Runs the Script plugin end-to-end on the real engine: a rendered inline script executes as a local
 * process, its stdout/exit-code are captured, and a non-zero exit fails the task. Cross-platform
 * (uses the platform shell), so it passes on Windows and Unix alike.
 */
class ScriptTest {

    private final YamlFlowParser parser;

    ScriptTest() {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        registry.register(Script.class);
        this.parser = new YamlFlowParser(new JacksonMapper(registry));
    }

    @Test
    void runsRenderedScriptAndCapturesStdout() throws Exception {
        Flow flow = parser.parse("""
            id: script_ok
            namespace: dev
            inputs:
              - id: name
                type: STRING
                defaults: world
            tasks:
              - id: run
                type: io.tranto.plugin.scripts.Script
                script: echo rendered-{{ inputs.name }}
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, Map.of(), Duration.ofSeconds(20));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            Object outputs = execution.findTaskRunByTaskId("run").get().getOutputs();
            assertThat(outputs.toString()).contains("rendered-world");
            assertThat(outputs.toString()).contains("exitCode=0");
        }
    }

    @Test
    void nonZeroExitFailsTheTask() throws Exception {
        Flow flow = parser.parse("""
            id: script_bad
            namespace: dev
            tasks:
              - id: run
                type: io.tranto.plugin.scripts.Script
                script: exit 3
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, Map.of(), Duration.ofSeconds(20));

            assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
        }
    }
}
