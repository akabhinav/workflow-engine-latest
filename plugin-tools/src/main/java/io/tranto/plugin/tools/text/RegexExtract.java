package io.tranto.plugin.tools.text;

import io.tranto.core.models.annotations.Example;
import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Applies a regular expression to text and extracts matches.
 *
 * <ul>
 *   <li>{@code matched} — whether the pattern matched at least once.</li>
 *   <li>{@code first} — the first full match ({@code null} when there is none).</li>
 *   <li>{@code groups} — the capture groups of the first match (group&nbsp;0 is the full match).</li>
 *   <li>{@code all} — every full match across the input.</li>
 * </ul>
 *
 * <p>Uses Java regex syntax. {@code caseInsensitive} and {@code multiline} toggle the corresponding
 * {@link Pattern} flags.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Extract matches from text with a regex",
    examples = @Example(
        title = "Pull an order id out of a log line",
        code = {
            "id: order_id",
            "type: io.tranto.plugin.tools.text.RegexExtract",
            "from: \"{{ outputs.read.content }}\"",
            "pattern: \"order-(\\\\d+)\""
        }
    )
)
public class RegexExtract extends Task implements RunnableTask<RegexExtract.RegexOutput> {

    /** The text to search. Pebble-rendered before matching. */
    @PluginProperty(dynamic = true)
    private String from;

    /** The regular expression (Java syntax). Pebble-rendered before compilation. */
    @PluginProperty(dynamic = true)
    private String pattern;

    /** Enable case-insensitive matching. Defaults to {@code false}. */
    @PluginProperty
    private Boolean caseInsensitive;

    /** Enable multiline mode ({@code ^}/{@code $} match at line boundaries). Defaults to {@code false}. */
    @PluginProperty
    private Boolean multiline;

    @Override
    public RegexOutput run(final RunContext runContext) throws Exception {
        if (from == null) {
            throw new IllegalArgumentException("RegexExtract requires a non-null 'from' value");
        }
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("RegexExtract requires a non-empty 'pattern'");
        }
        final String text = runContext.render(from);
        final String regex = runContext.render(pattern);

        int flags = 0;
        if (Boolean.TRUE.equals(caseInsensitive)) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        if (Boolean.TRUE.equals(multiline)) {
            flags |= Pattern.MULTILINE;
        }

        final Pattern compiled;
        try {
            compiled = Pattern.compile(regex, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex '" + regex + "': " + e.getDescription(), e);
        }

        final Matcher matcher = compiled.matcher(text);
        String first = null;
        List<String> groups = List.of();
        final List<String> all = new ArrayList<>();

        boolean isFirst = true;
        while (matcher.find()) {
            final String match = matcher.group();
            all.add(match);
            if (isFirst) {
                first = match;
                final List<String> g = new ArrayList<>(matcher.groupCount() + 1);
                for (int i = 0; i <= matcher.groupCount(); i++) {
                    g.add(matcher.group(i));
                }
                groups = List.copyOf(g);
                isFirst = false;
            }
        }

        runContext.logger().debug("Regex matched {} time(s)", all.size());
        return new RegexOutput(!all.isEmpty(), first, groups, List.copyOf(all));
    }

    /**
     * @param matched whether the pattern matched at least once
     * @param first   the first full match, or {@code null}
     * @param groups  the capture groups of the first match (index 0 is the full match)
     * @param all     every full match, in order
     */
    public record RegexOutput(boolean matched, String first, List<String> groups, List<String> all)
        implements Output {
    }
}
