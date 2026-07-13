package io.tranto.core.plugins;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * A child-first (parent-last) classloader for isolating an external plugin JAR and its bundled
 * dependencies, so two plugins can depend on conflicting library versions without clashing.
 *
 * <p>The one exception to child-first is the <em>shared contract</em>: the SDK model/interfaces and
 * the JDK must always load from the parent. If a plugin loaded its own copy of {@code Task} or
 * {@code RunContext}, the engine's {@code instanceof} checks would fail across the boundary. So
 * those packages delegate up; everything else (the plugin's own classes and vendored deps) resolves
 * from this loader first.</p>
 */
public class PluginClassLoader extends URLClassLoader {

    /** Package prefixes that MUST come from the parent to keep the contract types identical. */
    private static final String[] SHARED_PREFIXES = {
        "java.", "javax.", "jakarta.", "org.slf4j.",
        "io.tranto.core.models.", "io.tranto.core.runners.",
        "io.tranto.core.storages.", "io.tranto.core.kv.", "io.tranto.core.exceptions."
    };

    public PluginClassLoader(final URL[] urls, final ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isShared(name)) {
                    loaded = super.loadClass(name, false); // delegate to parent (contract + JDK)
                } else {
                    try {
                        loaded = findClass(name);          // child-first: prefer the plugin's own copy
                    } catch (ClassNotFoundException notLocal) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean isShared(final String name) {
        for (String prefix : SHARED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
