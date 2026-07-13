package io.tranto.core.plugins;

import io.tranto.core.models.Plugin;
import io.tranto.core.models.annotations.Plugin.Id;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link PluginRegistry} backed by a concurrent map. A plugin is registered under:
 * <ul>
 *   <li>its fully-qualified class name (the default {@code type}),</li>
 *   <li>its {@code @Plugin.Id} value, if present,</li>
 *   <li>each of its {@code @Plugin(aliases = ...)} entries, if present.</li>
 * </ul>
 *
 * <p>Thread-safe. Phase 2 replaces/extends this with classpath + external-JAR scanning, but the
 * lookup contract stays the same.</p>
 */
public class SimplePluginRegistry implements PluginRegistry {

    private final ConcurrentHashMap<String, Class<? extends Plugin>> byType = new ConcurrentHashMap<>();

    @Override
    public void register(final Class<? extends Plugin> pluginClass) {
        // Always resolvable by class name (the default type identifier).
        byType.put(pluginClass.getName(), pluginClass);

        Id id = pluginClass.getAnnotation(Id.class);
        if (id != null && !id.value().isBlank()) {
            byType.put(id.value(), pluginClass);
        }

        io.tranto.core.models.annotations.Plugin plugin =
            pluginClass.getAnnotation(io.tranto.core.models.annotations.Plugin.class);
        if (plugin != null) {
            for (String alias : plugin.aliases()) {
                if (!alias.isBlank()) {
                    byType.put(alias, pluginClass);
                }
            }
        }
    }

    @Override
    public Optional<Class<? extends Plugin>> findByType(final String type) {
        return Optional.ofNullable(type).map(byType::get);
    }
}
