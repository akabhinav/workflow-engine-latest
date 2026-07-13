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

/** Exponential backoff is actually applied by the executor between retry attempts. */
class RetryBackoffTest {

    @Test
    void exponentialBackoffDelaysBetweenAttempts() throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        Flow flow = new YamlFlowParser(new JacksonMapper(registry)).parse("""
            id: backoff
            namespace: dev
            tasks:
              - id: always_fails
                type: io.tranto.plugin.core.execution.Fail
                message: "nope"
                retry:
                  maxAttempts: 2
                  behavior: EXPONENTIAL
                  delay: "PT0.2S"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            long start = System.nanoTime();
            Execution execution = engine.run(flow, null, Duration.ofSeconds(15));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
            assertThat(execution.findTaskRunByTaskId("always_fails").get().getAttempts()).isEqualTo(2);
            // Delays are 0.2s (attempt 1) + 0.4s (attempt 2) = 0.6s; assert well above a zero-delay run.
            assertThat(elapsed).isGreaterThan(Duration.ofMillis(400));
        }
    }
}
