package io.tranto.core.plugins;

import io.tranto.core.models.Plugin;

import java.util.Optional;

/**
 * Resolves a plugin {@code type} identifier (as written in flow YAML) to its concrete class.
 * This is the indirection that lets arbitrary plugin JARs contribute task/trigger types without
 * the engine knowing them at compile time.
 *
 * <p>Phase 1 provides an in-memory implementation ({@link SimplePluginRegistry}); Phase 2 adds
 * ServiceLoader/classpath scanning and isolated classloaders for external plugin JARs.</p>
 */
public interface PluginRegistry {

    /**
     * Register a concrete plugin class under its {@code type} identifier(s).
     *
     * @param pluginClass a concrete (instantiable) plugin class
     */
    void register(Class<? extends Plugin> pluginClass);

    /**
     * Look up a plugin class by its {@code type} identifier.
     *
     * @param type the identifier from YAML (e.g. {@code io.tranto.plugin.core.log.Log})
     * @return the class, or empty if no plugin answers to that type
     */
    Optional<Class<? extends Plugin>> findByType(String type);
}
