package io.tranto.core.plugins;

import io.tranto.core.models.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Discovers plugins at runtime via the JDK {@link ServiceLoader}, reading the
 * {@code META-INF/services/io.tranto.core.models.Plugin} manifests that the build-time annotation
 * processor generates. This replaces manual registration: any JAR on the classpath (or on an
 * isolated {@link PluginClassLoader}) that ships the manifest contributes its tasks and triggers.
 */
public class PluginScanner {

    /**
     * @param classLoader the loader to scan (its manifests + those of its parents)
     * @return the concrete plugin classes discovered
     */
    public List<Class<? extends Plugin>> scan(final ClassLoader classLoader) {
        List<Class<? extends Plugin>> found = new ArrayList<>();
        for (Plugin plugin : ServiceLoader.load(Plugin.class, classLoader)) {
            found.add(plugin.getClass());
        }
        return found;
    }

    /**
     * Scan and register every discovered plugin into {@code registry}.
     *
     * @return the number of plugins registered
     */
    public int scanAndRegister(final PluginRegistry registry, final ClassLoader classLoader) {
        List<Class<? extends Plugin>> found = scan(classLoader);
        found.forEach(registry::register);
        return found.size();
    }
}
