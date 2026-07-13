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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The engine's update subscription (behind the SSE follow endpoint) streams state to a terminal. */
class ExecutionSubscriptionTest {

    @Test
    void subscriberReceivesTerminalStateUpdate() throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        Flow flow = new YamlFlowParser(new JacksonMapper(registry)).parse("""
            id: watched
            namespace: dev
            tasks:
              - id: hello
                type: io.tranto.plugin.core.log.Log
                message: "hi"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            CountDownLatch terminal = new CountDownLatch(1);
            AtomicReference<StateType> lastState = new AtomicReference<>();
            engine.subscribe(execution -> {
                if (execution.getState().isTerminated()) {
                    lastState.set(execution.getState().current());
                    terminal.countDown();
                }
            });

            Execution submitted = engine.submit(flow, Map.of());
            assertThat(terminal.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(lastState.get()).isEqualTo(StateType.SUCCESS);
            assertThat(submitted.getId()).isNotBlank();
        }
    }
}
