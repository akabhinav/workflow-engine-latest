package io.tranto.core.models;

/**
 * The root marker for everything loadable into Tranto: tasks, triggers, conditions,
 * task-runners, storages, and so on.
 *
 * <p>A plugin's {@linkplain #getType() type} is the identifier used in flow YAML
 * ({@code type: io.tranto.plugin.core.log.Log}) and the key the plugin registry resolves at
 * deserialization time. By default it is the fully-qualified class name; a plugin may expose a
 * stabler identifier via {@code @Plugin.Id}.</p>
 */
public interface Plugin {

    /**
     * @return the type identifier used to reference this plugin from YAML/JSON.
     *         Defaults to the fully-qualified class name.
     */
    default String getType() {
        return this.getClass().getName();
    }
}
