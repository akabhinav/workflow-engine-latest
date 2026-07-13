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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** A flow trigger: finishing the upstream flow automatically starts the downstream flow. */
class FlowTriggerTest {

    private Flow parse(final JacksonMapper mappers, final String yaml) throws Exception {
        return new YamlFlowParser(mappers).parse(yaml);
    }

    @Test
    void downstreamFlowStartsWhenUpstreamSucceeds() throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        JacksonMapper mappers = new JacksonMapper(registry);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            // Watch for the downstream ('consumer') flow reaching SUCCESS.
            CountDownLatch consumerDone = new CountDownLatch(1);
            AtomicReference<StateType> consumerState = new AtomicReference<>();
            engine.subscribe(execution -> {
                if (execution.getFlowId().equals("consumer") && execution.getState().isTerminated()) {
                    consumerState.set(execution.getState().current());
                    consumerDone.countDown();
                }
            });

            // Downstream flow: fires when 'producer' succeeds.
            engine.register(parse(mappers, """
                id: consumer
                namespace: data
                triggers:
                  - id: after_producer
                    type: io.tranto.plugin.core.trigger.FlowTrigger
                    namespace: data
                    flowId: producer
                    states: [SUCCESS]
                tasks:
                  - id: react
                    type: io.tranto.plugin.core.log.Log
                    message: "reacting to producer completion"
                """));

            // Run the upstream flow. Its success should auto-start the consumer.
            Execution producer = engine.run(parse(mappers, """
                id: producer
                namespace: data
                tasks:
                  - id: emit
                    type: io.tranto.plugin.core.log.Log
                    message: "producing"
                """), Map.of(), Duration.ofSeconds(10));

            assertThat(producer.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(consumerDone.await(10, TimeUnit.SECONDS))
                .as("consumer flow should be triggered by producer success").isTrue();
            assertThat(consumerState.get()).isEqualTo(StateType.SUCCESS);
        }
    }
}
