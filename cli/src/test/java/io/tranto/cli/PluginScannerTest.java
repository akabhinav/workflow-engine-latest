package io.tranto.cli;

import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.PluginScanner;
import io.tranto.core.plugins.SimplePluginRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end plugin discovery: the annotation processor wrote a ServiceLoader manifest at build
 * time, and {@link PluginScanner} loads it at runtime — no manual registration, no reflection scan.
 */
class PluginScannerTest {

    @Test
    void discoversCorePluginsViaServiceLoaderManifest() {
        PluginScanner scanner = new PluginScanner();
        PluginRegistry registry = new SimplePluginRegistry();

        int registered = scanner.scanAndRegister(registry, Thread.currentThread().getContextClassLoader());

        // All 17 built-ins ship a generated manifest entry.
        assertThat(registered).isGreaterThanOrEqualTo(17);
        assertThat(registry.findByType("io.tranto.plugin.core.log.Log")).isPresent();
        assertThat(registry.findByType("io.tranto.plugin.core.flow.Subflow")).isPresent();
        assertThat(registry.findByType("io.tranto.plugin.core.trigger.Schedule")).isPresent();
    }
}
