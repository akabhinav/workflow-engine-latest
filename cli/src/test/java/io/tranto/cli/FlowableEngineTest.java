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

/**
 * Phase-4 milestone: nested flowable control flow (Parallel + If) runs to SUCCESS through the real
 * engine, with the correct branch taken and both parallel children executed.
 */
class FlowableEngineTest {

    private Flow parse(final String yaml) throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new YamlFlowParser(new JacksonMapper(registry)).parse(yaml);
    }

    @Test
    void shouldRunParallelThenConditionalBranch() throws Exception {
        Flow flow = parse("""
            id: flowable
            namespace: dev
            tasks:
              - id: par
                type: io.tranto.plugin.core.flow.Parallel
                tasks:
                  - id: a
                    type: io.tranto.plugin.core.log.Log
                    message: "A"
                  - id: b
                    type: io.tranto.plugin.core.log.Log
                    message: "B"
              - id: branch
                type: io.tranto.plugin.core.flow.If
                condition: "true"
                then:
                  - id: yes_task
                    type: io.tranto.plugin.core.log.Log
                    message: "taken"
                else:
                  - id: no_task
                    type: io.tranto.plugin.core.log.Log
                    message: "skipped"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            // Both parallel leaves ran and succeeded
            assertThat(execution.findTaskRunByTaskId("a")).isPresent();
            assertThat(execution.findTaskRunByTaskId("b")).isPresent();
            // The 'then' branch was taken; the 'else' branch never ran
            assertThat(execution.findTaskRunByTaskId("yes_task")).isPresent();
            assertThat(execution.findTaskRunByTaskId("no_task")).isEmpty();
            // The flowable containers themselves resolved to SUCCESS
            assertThat(execution.findTaskRunByTaskId("par").get().getState().current())
                .isEqualTo(StateType.SUCCESS);
            assertThat(execution.findTaskRunByTaskId("branch").get().getState().current())
                .isEqualTo(StateType.SUCCESS);
            assertThat(execution.getTaskRunList()).allMatch(tr -> tr.isTerminated());
        }
    }

    @Test
    void shouldTakeElseBranchWhenConditionFalse() throws Exception {
        Flow flow = parse("""
            id: branch_false
            namespace: dev
            tasks:
              - id: branch
                type: io.tranto.plugin.core.flow.If
                condition: "false"
                then:
                  - id: yes_task
                    type: io.tranto.plugin.core.log.Log
                    message: "taken"
                else:
                  - id: no_task
                    type: io.tranto.plugin.core.log.Log
                    message: "fallback"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(execution.findTaskRunByTaskId("no_task")).isPresent();
            assertThat(execution.findTaskRunByTaskId("yes_task")).isEmpty();
        }
    }
}
