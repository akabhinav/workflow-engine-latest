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

/** A task with a retry policy exhausts its attempts before the execution fails. */
class RetryTest {

    @Test
    void shouldRetryFailedTaskThenFail() throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        Flow flow = new YamlFlowParser(new JacksonMapper(registry)).parse("""
            id: retrying
            namespace: dev
            tasks:
              - id: always_fails
                type: io.tranto.plugin.core.execution.Fail
                message: "nope"
                retry:
                  maxAttempts: 2
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, null, Duration.ofSeconds(15));

            assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
            // First attempt + 2 retries all consumed
            assertThat(execution.findTaskRunByTaskId("always_fails").get().getAttempts()).isEqualTo(2);
        }
    }
}
