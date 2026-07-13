package io.tranto.plugin.tools.file;

import io.tranto.core.models.annotations.Example;
import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Writes rendered text content to a local file on the worker's filesystem.
 *
 * <p>Parent directories are created when {@code createDirectories} is {@code true} (the default).
 * Set {@code append: true} to append instead of overwrite. Both the {@code path} and {@code content}
 * are Pebble-rendered. Useful for staging data for a downstream {@code Script}/{@code Process} task
 * or writing a report locally.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Write content to a local file",
    examples = @Example(
        title = "Write a rendered report to disk",
        code = {
            "id: write_report",
            "type: io.tranto.plugin.tools.file.Write",
            "path: \"/tmp/reports/{{ execution.id }}.txt\"",
            "content: \"Processed {{ outputs.count.value }} rows\""
        }
    )
)
public class Write extends Task implements RunnableTask<Write.WriteOutput> {

    /** The target file path. Pebble-rendered. */
    @PluginProperty(dynamic = true)
    private String path;

    /** The content to write. Pebble-rendered. Defaults to the empty string. */
    @PluginProperty(dynamic = true)
    private String content;

    /** The charset used to encode the content. Defaults to {@code UTF-8}. */
    @PluginProperty
    private String charset;

    /** Create missing parent directories. Defaults to {@code true}. */
    @PluginProperty
    private Boolean createDirectories;

    /** Append to an existing file instead of overwriting it. Defaults to {@code false}. */
    @PluginProperty
    private Boolean append;

    @Override
    public WriteOutput run(final RunContext runContext) throws Exception {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Write requires a non-blank 'path'");
        }
        final Path target = Path.of(runContext.render(path));
        final String body = content != null ? runContext.render(content) : "";
        final Charset cs = charset != null && !charset.isBlank()
            ? Charset.forName(charset.trim())
            : StandardCharsets.UTF_8;

        if ((createDirectories == null || createDirectories) && target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }

        final List<OpenOption> options = new ArrayList<>();
        options.add(StandardOpenOption.CREATE);
        options.add(StandardOpenOption.WRITE);
        options.add(Boolean.TRUE.equals(append)
            ? StandardOpenOption.APPEND
            : StandardOpenOption.TRUNCATE_EXISTING);

        final byte[] bytes = body.getBytes(cs);
        Files.write(target, bytes, options.toArray(OpenOption[]::new));

        final Path absolute = target.toAbsolutePath().normalize();
        runContext.logger().info("Wrote {} byte(s) to {}", bytes.length, absolute);
        return new WriteOutput(absolute.toString(), bytes.length);
    }

    /**
     * @param path the absolute, normalized path written to
     * @param size the number of bytes written
     */
    public record WriteOutput(String path, long size) implements Output {
    }
}
