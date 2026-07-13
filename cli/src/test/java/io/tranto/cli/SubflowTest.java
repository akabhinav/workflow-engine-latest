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

/** Subflow: a parent runs a child flow and mirrors its outcome. */
class SubflowTest {

    private final YamlFlowParser parser;

    SubflowTest() {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        this.parser = new YamlFlowParser(new JacksonMapper(registry));
    }

    @Test
    void parentSucceedsWhenChildSucceeds() throws Exception {
        Flow child = parser.parse("""
            id: child_ok
            namespace: dev
            tasks:
              - id: c1
                type: io.tranto.plugin.core.log.Log
                message: "child ran"
            """);
        Flow parent = parser.parse("""
            id: parent_ok
            namespace: dev
            tasks:
              - id: call
                type: io.tranto.plugin.core.flow.Subflow
                namespace: dev
                flowId: child_ok
              - id: after
                type: io.tranto.plugin.core.log.Log
                message: "parent continues"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            engine.register(child);
            Execution execution = engine.run(parent, null, Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(execution.findTaskRunByTaskId("call").get().getState().current())
                .isEqualTo(StateType.SUCCESS);
            assertThat(execution.findTaskRunByTaskId("after")).isPresent();
        }
    }

    @Test
    void parentFailsWhenChildFails() throws Exception {
        Flow child = parser.parse("""
            id: child_bad
            namespace: dev
            tasks:
              - id: boom
                type: io.tranto.plugin.core.execution.Fail
                message: "child failed"
            """);
        Flow parent = parser.parse("""
            id: parent_bad
            namespace: dev
            tasks:
              - id: call
                type: io.tranto.plugin.core.flow.Subflow
                namespace: dev
                flowId: child_bad
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            engine.register(child);
            Execution execution = engine.run(parent, null, Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
            assertThat(execution.findTaskRunByTaskId("call").get().getState().current())
                .isEqualTo(StateType.FAILED);
        }
    }
}
