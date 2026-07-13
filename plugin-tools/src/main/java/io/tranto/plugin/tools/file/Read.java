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
import java.nio.file.Path;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Reads a local file into a string output.
 *
 * <p>To protect the worker from accidentally loading an enormous file into memory, reads are
 * capped at {@code maxBytes} (default 10&nbsp;MiB); a larger file fails the task. The {@code path}
 * is Pebble-rendered and the content is decoded with {@code charset} (default {@code UTF-8}).</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Read a local file into a string",
    examples = @Example(
        title = "Read a config file and parse it downstream",
        code = {
            "id: read",
            "type: io.tranto.plugin.tools.file.Read",
            "path: \"/etc/app/config.json\""
        }
    )
)
public class Read extends Task implements RunnableTask<Read.ReadOutput> {

    /** The default read cap: 10 MiB. */
    private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    /** The file path to read. Pebble-rendered. */
    @PluginProperty(dynamic = true)
    private String path;

    /** The charset used to decode the file. Defaults to {@code UTF-8}. */
    @PluginProperty
    private String charset;

    /** Maximum number of bytes to read before failing. Defaults to {@code 10485760} (10 MiB). */
    @PluginProperty
    private Long maxBytes;

    @Override
    public ReadOutput run(final RunContext runContext) throws Exception {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Read requires a non-blank 'path'");
        }
        final Path source = Path.of(runContext.render(path));
        if (!Files.exists(source)) {
            throw new java.nio.file.NoSuchFileException(source.toString());
        }
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Not a regular file: " + source);
        }

        final long limit = maxBytes != null && maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
        final long size = Files.size(source);
        if (size > limit) {
            throw new IllegalStateException(
                "File is " + size + " bytes, exceeding the " + limit + "-byte limit (raise 'maxBytes')");
        }

        final Charset cs = charset != null && !charset.isBlank()
            ? Charset.forName(charset.trim())
            : StandardCharsets.UTF_8;

        final byte[] bytes = Files.readAllBytes(source);
        final String content = new String(bytes, cs);

        runContext.logger().debug("Read {} byte(s) from {}", bytes.length, source);
        return new ReadOutput(content, bytes.length);
    }

    /**
     * @param content the file's decoded content
     * @param size    the number of bytes read
     */
    public record ReadOutput(String content, long size) implements Output {
    }
}
