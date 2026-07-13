package io.tranto.cli;

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

/** The engine tallies executions by terminal state. */
class MetricsTest {

    private Flow parse(final JacksonMapper mappers, final String yaml) throws Exception {
        return new YamlFlowParser(mappers).parse(yaml);
    }

    @Test
    void countsExecutionsByOutcome() throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        JacksonMapper mappers = new JacksonMapper(registry);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            engine.run(parse(mappers, """
                id: ok_flow
                namespace: dev
                tasks:
                  - id: a
                    type: io.tranto.plugin.core.log.Log
                    message: "fine"
                """), Map.of(), Duration.ofSeconds(10));

            engine.run(parse(mappers, """
                id: bad_flow
                namespace: dev
                tasks:
                  - id: boom
                    type: io.tranto.plugin.core.execution.Fail
                    message: "kaboom"
                """), Map.of(), Duration.ofSeconds(10));

            Map<String, Long> metrics = engine.metrics();
            assertThat(metrics.get("SUCCESS")).isGreaterThanOrEqualTo(1);
            assertThat(metrics.get("FAILED")).isGreaterThanOrEqualTo(1);
            assertThat(metrics.get("total")).isGreaterThanOrEqualTo(2);
        }
    }
}
