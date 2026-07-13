package io.tranto.webserver.config;

import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.StandaloneEngine;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.plugin.core.CorePlugins;
import io.tranto.plugin.tools.ToolPlugins;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the engine as Spring beans. The plugin registry is seeded with the built-ins; the
 * {@link StandaloneEngine} is a singleton whose {@code close()} Spring calls on shutdown.
 */
@Configuration
public class EngineConfig {

    @Bean
    public PluginRegistry pluginRegistry() {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        ToolPlugins.all().forEach(registry::register);
        return registry;
    }

    @Bean
    public YamlFlowParser yamlFlowParser(final PluginRegistry registry) {
        return new YamlFlowParser(new JacksonMapper(registry));
    }

    @Bean(destroyMethod = "close")
    public StandaloneEngine standaloneEngine() {
        return new StandaloneEngine();
    }
}
