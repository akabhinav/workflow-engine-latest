package io.tranto.core.serializers;

import com.fasterxml.jackson.databind.module.SimpleModule;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.models.tasks.runners.TaskRunner;
import io.tranto.core.models.triggers.AbstractTrigger;
import io.tranto.core.plugins.PluginRegistry;

/**
 * Jackson module that teaches the mapper how to deserialize Tranto's polymorphic plugin base
 * types by their {@code type} field, using the {@link PluginRegistry}.
 *
 * <p>Phase 1 wires {@link Task}. Later phases add the other plugin bases ({@code AbstractTrigger},
 * {@code Condition}, {@code TaskRunner}, ...) the same way.</p>
 */
public class PluginModule extends SimpleModule {

    public PluginModule(final PluginRegistry registry) {
        super("TrantoPluginModule");
        this.addDeserializer(Task.class, new PluginDeserializer<>(registry));
        this.addDeserializer(AbstractTrigger.class, new PluginDeserializer<>(registry));
        this.addDeserializer(TaskRunner.class, new PluginDeserializer<>(registry));
    }
}
