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

/** The Commands task runs shell commands through the Process runner and captures their output. */
class CommandsTest {

    private Flow parse(final String yaml) throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new YamlFlowParser(new JacksonMapper(registry)).parse(yaml);
    }

    @Test
    void runsShellCommandAndCapturesStdout() throws Exception {
        Flow flow = parse("""
            id: shell
            namespace: dev
            tasks:
              - id: cmd
                type: io.tranto.plugin.core.script.Commands
                commands:
                  - echo tranto-works
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, Map.of(), Duration.ofSeconds(20));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            Object outputs = execution.findTaskRunByTaskId("cmd").get().getOutputs();
            assertThat(outputs.toString()).contains("tranto-works");
        }
    }

    @Test
    void nonZeroExitFailsTheTask() throws Exception {
        Flow flow = parse("""
            id: shell_fail
            namespace: dev
            tasks:
              - id: cmd
                type: io.tranto.plugin.core.script.Commands
                commands:
                  - exit 3
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, Map.of(), Duration.ofSeconds(20));
            assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
        }
    }
}
