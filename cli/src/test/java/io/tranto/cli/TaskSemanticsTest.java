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

import static org.assertj.core.api.Assertions.assertThat;

/** Task-level semantics: allowFailure -> WARNING (and keep going), runIf false -> SKIPPED. */
class TaskSemanticsTest {

    private Flow parse(final String yaml) throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new YamlFlowParser(new JacksonMapper(registry)).parse(yaml);
    }

    @Test
    void allowFailureShouldWarnAndContinue() throws Exception {
        Flow flow = parse("""
            id: allow_fail
            namespace: dev
            tasks:
              - id: risky
                type: io.tranto.plugin.core.execution.Fail
                allowFailure: true
                message: "oops"
              - id: after
                type: io.tranto.plugin.core.log.Log
                message: "still runs"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.WARNING);
            assertThat(execution.findTaskRunByTaskId("risky").get().getState().current())
                .isEqualTo(StateType.WARNING);
            assertThat(execution.findTaskRunByTaskId("after")).isPresent();
            assertThat(execution.findTaskRunByTaskId("after").get().getState().current())
                .isEqualTo(StateType.SUCCESS);
        }
    }

    @Test
    void runIfFalseShouldSkip() throws Exception {
        Flow flow = parse("""
            id: run_if
            namespace: dev
            tasks:
              - id: maybe
                type: io.tranto.plugin.core.log.Log
                runIf: "false"
                message: "skip me"
              - id: always
                type: io.tranto.plugin.core.log.Log
                message: "ran"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(execution.findTaskRunByTaskId("maybe").get().getState().current())
                .isEqualTo(StateType.SKIPPED);
            assertThat(execution.findTaskRunByTaskId("always").get().getState().current())
                .isEqualTo(StateType.SUCCESS);
        }
    }
}
