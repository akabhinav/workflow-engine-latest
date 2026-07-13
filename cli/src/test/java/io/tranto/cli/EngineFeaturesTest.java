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

/** error/finally hooks, Loop/ForEach, and timeout enforcement. */
class EngineFeaturesTest {

    private Flow parse(final String yaml) throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new YamlFlowParser(new JacksonMapper(registry)).parse(yaml);
    }

    @Test
    void errorsRunOnFailureAndFinallyAlways() throws Exception {
        Flow flow = parse("""
            id: hooks
            namespace: dev
            tasks:
              - id: boom
                type: io.tranto.plugin.core.execution.Fail
                message: "kaboom"
            errors:
              - id: on_error
                type: io.tranto.plugin.core.log.Log
                message: "handled"
            finally:
              - id: cleanup
                type: io.tranto.plugin.core.log.Log
                message: "cleanup"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(15));

            // Main failed -> execution FAILED, but error + finally handlers ran.
            assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
            assertThat(execution.findTaskRunByTaskId("on_error")).isPresent();
            assertThat(execution.findTaskRunByTaskId("on_error").get().getState().current())
                .isEqualTo(StateType.SUCCESS);
            assertThat(execution.findTaskRunByTaskId("cleanup")).isPresent();
        }
    }

    @Test
    void finallyRunsOnSuccessAndErrorsDoNot() throws Exception {
        Flow flow = parse("""
            id: hooks_ok
            namespace: dev
            tasks:
              - id: ok
                type: io.tranto.plugin.core.log.Log
                message: "fine"
            errors:
              - id: on_error
                type: io.tranto.plugin.core.log.Log
                message: "should not run"
            finally:
              - id: cleanup
                type: io.tranto.plugin.core.log.Log
                message: "always"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(execution.findTaskRunByTaskId("on_error")).isEmpty();
            assertThat(execution.findTaskRunByTaskId("cleanup")).isPresent();
        }
    }

    @Test
    void loopRunsChildrenPerValue() throws Exception {
        Flow flow = parse("""
            id: looping
            namespace: dev
            tasks:
              - id: each
                type: io.tranto.plugin.core.flow.Loop
                values: "a,b,c"
                tasks:
                  - id: say
                    type: io.tranto.plugin.core.log.Log
                    message: "value is {{ taskrun.value }}"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            // The loop child ran once per value (3 runs of task id "say", one per iteration scope).
            long sayRuns = execution.getTaskRunList().stream()
                .filter(tr -> tr.getTaskId().equals("say")).count();
            assertThat(sayRuns).isEqualTo(3);
            assertThat(execution.getTaskRunList().stream()
                .filter(tr -> tr.getTaskId().equals("say"))
                .allMatch(tr -> tr.getState().current() == StateType.SUCCESS)).isTrue();
        }
    }

    @Test
    void timeoutFailsALongRunningTask() throws Exception {
        Flow flow = parse("""
            id: timing_out
            namespace: dev
            tasks:
              - id: slow
                type: io.tranto.plugin.core.flow.Sleep
                timeout: "PT0.1S"
                duration: "PT5S"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
            assertThat(execution.findTaskRunByTaskId("slow").get().getState().current())
                .isEqualTo(StateType.FAILED);
        }
    }
}
