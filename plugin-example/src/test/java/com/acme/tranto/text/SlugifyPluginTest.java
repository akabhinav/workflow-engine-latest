package com.acme.tranto.text;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.PluginScanner;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.StandaloneEngine;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the full third-party plugin loop: the annotation processor generated a ServiceLoader
 * manifest for {@link Slugify} at build time, the engine's {@link PluginScanner} discovers it at
 * runtime, and a flow referencing {@code type: com.acme.tranto.text.Slugify} runs successfully —
 * all without the engine knowing about this plugin at compile time.
 */
class SlugifyPluginTest {

    @Test
    void customPluginIsDiscoveredAndRuns() throws Exception {
        // 1) Discovery: scan the classpath — finds Slugify via its generated META-INF/services entry.
        PluginRegistry registry = new SimplePluginRegistry();
        new PluginScanner().scanAndRegister(registry, Thread.currentThread().getContextClassLoader());
        assertThat(registry.findByType("com.acme.tranto.text.Slugify")).isPresent();

        // 2) Author a flow that uses the custom type.
        Flow flow = new YamlFlowParser(new JacksonMapper(registry)).parse("""
            id: slug_demo
            namespace: demo
            inputs:
              - id: title
                type: STRING
                defaults: "Hello, World! 2026"
            tasks:
              - id: make_slug
                type: com.acme.tranto.text.Slugify
                text: "{{ inputs.title }}"
            """);

        // 3) Run it on the real engine.
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(flow, Map.of(), Duration.ofSeconds(10));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            Object outputs = execution.findTaskRunByTaskId("make_slug").get().getOutputs();
            assertThat(outputs.toString()).contains("hello-world-2026");
        }
    }
}
