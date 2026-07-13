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

/** kv.Set then kv.Get round-trips a value through the namespace KV store within an execution. */
class KvTaskTest {

    @Test
    void setThenGetReturnsStoredValue() throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        Flow flow = new YamlFlowParser(new JacksonMapper(registry)).parse("""
            id: kvflow
            namespace: dev
            tasks:
              - id: put
                type: io.tranto.plugin.core.kv.Set
                key: greeting
                value: "hello kv"
              - id: fetch
                type: io.tranto.plugin.core.kv.Get
                key: greeting
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, Map.of(), Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            Object outputs = execution.findTaskRunByTaskId("fetch").get().getOutputs();
            assertThat(outputs.toString()).contains("hello kv");
        }
    }
}
