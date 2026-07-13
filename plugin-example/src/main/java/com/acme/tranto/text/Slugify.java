package com.acme.tranto.text;

import io.tranto.core.models.annotations.Example;
import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * A sample third-party task: turns a string into a URL-safe "slug".
 *
 * <p>This is a complete, real plugin authored entirely against the Tranto SDK — it extends
 * {@link Task}, implements {@link RunnableTask}, renders its input with the {@link RunContext}, and
 * returns a typed {@link Output}. It has no idea the engine exists. Because it is annotated with
 * {@link Plugin}, the build-time processor lists it in {@code META-INF/services}, so the runtime
 * discovers it by {@code type: com.acme.tranto.text.Slugify} with zero engine changes.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Turn text into a URL-safe slug",
    examples = @Example(
        title = "Slugify a blog title",
        code = {
            "id: to_slug",
            "type: com.acme.tranto.text.Slugify",
            "text: \"{{ inputs.title }}\""
        }
    )
)
public class Slugify extends Task implements RunnableTask<Slugify.SlugOutput> {

    /** The text to slugify. Supports Pebble expressions. */
    @PluginProperty(dynamic = true)
    private String text;

    @Override
    public SlugOutput run(final RunContext runContext) throws Exception {
        String rendered = runContext.render(text);
        String slug = rendered
            .toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        runContext.logger().info("slugified '{}' -> '{}'", rendered, slug);
        return new SlugOutput(slug);
    }

    /** @param slug the URL-safe slug, e.g. {@code "hello-world-2026"}. */
    public record SlugOutput(String slug) implements Output {
    }
}
