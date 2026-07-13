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
 * Base64-encodes a string. Supports the standard alphabet and the URL/filename-safe alphabet
 * ({@code urlSafe: true}), and can suppress padding ({@code padding: false}).
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Base64-encode a string",
    examples = @Example(
        title = "Encode credentials for a Basic auth header",
        code = {
            "id: basic_auth",
            "type: io.tranto.plugin.tools.encoding.Base64Encode",
            "from: \"{{ secret('USER') }}:{{ secret('PASS') }}\""
        }
    )
)
public class Base64Encode extends Task implements RunnableTask<Base64Encode.EncodeOutput> {

    /** The value to encode. Pebble-rendered before encoding. */
    @PluginProperty(dynamic = true)
    private String from;

    /** The charset the input is encoded with before Base64 encoding. Defaults to {@code UTF-8}. */
    @PluginProperty
    private String charset;

    /** Use the URL and filename-safe alphabet ({@code -} and {@code _}). Defaults to {@code false}. */
    @PluginProperty
    private Boolean urlSafe;

    /** Emit {@code =} padding. Defaults to {@code true}. */
    @PluginProperty
    private Boolean padding;

    @Override
    public EncodeOutput run(final RunContext runContext) throws Exception {
        if (from == null) {
            throw new IllegalArgumentException("Base64Encode requires a non-null 'from' value");
        }
        final String rendered = runContext.render(from);
        final Charset cs = charset != null && !charset.isBlank()
            ? Charset.forName(charset.trim())
            : StandardCharsets.UTF_8;

        Base64.Encoder encoder = Boolean.TRUE.equals(urlSafe)
            ? Base64.getUrlEncoder()
            : Base64.getEncoder();
        if (Boolean.FALSE.equals(padding)) {
            encoder = encoder.withoutPadding();
        }

        final String encoded = encoder.encodeToString(rendered.getBytes(cs));
        return new EncodeOutput(encoded, encoded.length());
    }

    /**
     * @param encoded the Base64 text
     * @param length  its character length
     */
    public record EncodeOutput(String encoded, int length) implements Output {
    }
}
