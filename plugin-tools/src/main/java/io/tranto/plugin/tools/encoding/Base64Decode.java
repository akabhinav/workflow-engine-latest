package io.tranto.plugin.tools.encoding;

import io.tranto.core.models.annotations.Example;
import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base64-decodes a string back into text. Accepts the standard alphabet by default, or the
 * URL/filename-safe alphabet when {@code urlSafe: true}. Surrounding whitespace is tolerated.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Base64-decode a string",
    examples = @Example(
        title = "Decode a Base64 payload into text",
        code = {
            "id: decode",
            "type: io.tranto.plugin.tools.encoding.Base64Decode",
            "from: \"{{ outputs.fetch.body }}\""
        }
    )
)
public class Base64Decode extends Task implements RunnableTask<Base64Decode.DecodeOutput> {

    /** The Base64 text to decode. Pebble-rendered before decoding. */
    @PluginProperty(dynamic = true)
    private String from;

    /** The charset used to reconstruct the decoded string. Defaults to {@code UTF-8}. */
    @PluginProperty
    private String charset;

    /** Interpret the input using the URL and filename-safe alphabet. Defaults to {@code false}. */
    @PluginProperty
    private Boolean urlSafe;

    @Override
    public DecodeOutput run(final RunContext runContext) throws Exception {
        if (from == null) {
            throw new IllegalArgumentException("Base64Decode requires a non-null 'from' value");
        }
        final String rendered = runContext.render(from).trim();
        final Charset cs = charset != null && !charset.isBlank()
            ? Charset.forName(charset.trim())
            : StandardCharsets.UTF_8;

        final Base64.Decoder decoder = Boolean.TRUE.equals(urlSafe)
            ? Base64.getUrlDecoder()
            : Base64.getDecoder();

        final byte[] bytes;
        try {
            bytes = decoder.decode(rendered);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Input is not valid Base64: " + e.getMessage(), e);
        }

        final String decoded = new String(bytes, cs);
        return new DecodeOutput(decoded, bytes.length);
    }

    /**
     * @param decoded the decoded text
     * @param bytes   the number of decoded bytes
     */
    public record DecodeOutput(String decoded, int bytes) implements Output {
    }
}
