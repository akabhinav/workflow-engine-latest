package io.tranto.plugin.tools.text;

import io.tranto.core.models.annotations.Example;
import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Replaces text matched by a regular expression. By default every match is replaced; set
 * {@code first: true} to replace only the first. The {@code replacement} supports group references
 * ({@code $1}, {@code ${name}}); set {@code literal: true} to treat it as plain text instead.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Replace text matched by a regex",
    examples = @Example(
        title = "Mask card numbers in a message",
        code = {
            "id: mask",
            "type: io.tranto.plugin.tools.text.Replace",
            "from: \"{{ inputs.message }}\"",
            "pattern: \"\\\\d{13,16}\"",
            "replacement: \"****\""
        }
    )
)
public class Replace extends Task implements RunnableTask<Replace.ReplaceOutput> {

    /** The text to transform. Pebble-rendered before matching. */
    @PluginProperty(dynamic = true)
    private String from;

    /** The regular expression (Java syntax). Pebble-rendered before compilation. */
    @PluginProperty(dynamic = true)
    private String pattern;

    /** The replacement string. Pebble-rendered; supports {@code $1}/{@code ${name}} unless {@code literal}. */
    @PluginProperty(dynamic = true)
    private String replacement;

    /** Replace only the first match instead of all. Defaults to {@code false}. */
    @PluginProperty
    private Boolean first;

    /** Treat {@code replacement} as a literal (no group substitution). Defaults to {@code false}. */
    @PluginProperty
    private Boolean literal;

    /** Case-insensitive matching. Defaults to {@code false}. */
    @PluginProperty
    private Boolean caseInsensitive;

    @Override
    public ReplaceOutput run(final RunContext runContext) throws Exception {
        if (from == null) {
            throw new IllegalArgumentException("Replace requires a non-null 'from' value");
        }
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("Replace requires a non-empty 'pattern'");
        }
        final String text = runContext.render(from);
        final String regex = runContext.render(pattern);
        final String raw = replacement != null ? runContext.render(replacement) : "";
        final String repl = Boolean.TRUE.equals(literal) ? Matcher.quoteReplacement(raw) : raw;

        final int flags = Boolean.TRUE.equals(caseInsensitive) ? Pattern.CASE_INSENSITIVE : 0;
        final Pattern compiled;
        try {
            compiled = Pattern.compile(regex, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex '" + regex + "': " + e.getDescription(), e);
        }

        final Matcher matcher = compiled.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        matcher.reset();

        final String result = Boolean.TRUE.equals(first)
            ? matcher.replaceFirst(repl)
            : matcher.replaceAll(repl);
        final int replaced = Boolean.TRUE.equals(first) ? Math.min(count, 1) : count;

        runContext.logger().debug("Replaced {} match(es)", replaced);
        return new ReplaceOutput(result, replaced);
    }

    /**
     * @param result the transformed text
     * @param count  the number of replacements performed
     */
    public record ReplaceOutput(String result, int count) implements Output {
    }
}
