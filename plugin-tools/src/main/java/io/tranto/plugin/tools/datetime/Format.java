package io.tranto.plugin.tools.datetime;

import io.tranto.core.models.annotations.Example;
import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Parses, shifts and formats a date-time.
 *
 * <p>The instant is taken from {@code from} (or the current time when {@code from} is omitted),
 * optionally shifted by an ISO-8601 {@code offset} duration (e.g. {@code PT1H}, {@code P1D},
 * {@code -PT30M}), then rendered in {@code zone} (default {@code UTC}) using {@code format}
 * (a {@link DateTimeFormatter} pattern; defaults to ISO-8601 instant).</p>
 *
 * <p>When {@code inputFormat} is set it parses {@code from} with that pattern; otherwise it
 * auto-detects common ISO shapes (instant, offset, zoned, local date-time, local date).</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Parse, shift and format a date-time",
    examples = @Example(
        title = "Format tomorrow in a given zone",
        code = {
            "id: tomorrow",
            "type: io.tranto.plugin.tools.datetime.Format",
            "offset: \"P1D\"",
            "zone: \"Europe/Paris\"",
            "format: \"yyyy-MM-dd\""
        }
    )
)
public class Format extends Task implements RunnableTask<Format.DateOutput> {

    /** The input date-time. Pebble-rendered. When omitted, the current time is used. */
    @PluginProperty(dynamic = true)
    private String from;

    /** A {@link DateTimeFormatter} pattern used to parse {@code from}. Optional (ISO auto-detect). */
    @PluginProperty(dynamic = true)
    private String inputFormat;

    /** An ISO-8601 duration to add to the instant, e.g. {@code PT1H}, {@code P1D}, {@code -PT30M}. */
    @PluginProperty(dynamic = true)
    private String offset;

    /** The zone used to interpret local inputs and to render the output. Defaults to {@code UTC}. */
    @PluginProperty(dynamic = true)
    private String zone;

    /** The output {@link DateTimeFormatter} pattern. Defaults to the ISO-8601 instant. */
    @PluginProperty(dynamic = true)
    private String format;

    @Override
    public DateOutput run(final RunContext runContext) throws Exception {
        final ZoneId zoneId = zone != null && !zone.isBlank()
            ? ZoneId.of(runContext.render(zone).trim())
            : ZoneId.of("UTC");

        Instant instant = resolveInstant(runContext, zoneId);

        if (offset != null && !offset.isBlank()) {
            final String rendered = runContext.render(offset).trim();
            try {
                instant = instant.plus(Duration.parse(rendered));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                    "Invalid ISO-8601 'offset' duration '" + rendered + "' (e.g. PT1H, P1D, -PT30M)", e);
            }
        }

        final ZonedDateTime zdt = instant.atZone(zoneId);
        final String formatted;
        if (format != null && !format.isBlank()) {
            try {
                formatted = DateTimeFormatter.ofPattern(runContext.render(format)).format(zdt);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid output 'format' pattern: " + e.getMessage(), e);
            }
        } else {
            formatted = DateTimeFormatter.ISO_INSTANT.format(instant);
        }

        return new DateOutput(
            formatted,
            DateTimeFormatter.ISO_INSTANT.format(instant),
            instant.toEpochMilli(),
            instant.getEpochSecond());
    }

    private Instant resolveInstant(final RunContext runContext, final ZoneId zoneId) throws Exception {
        if (from == null || from.isBlank()) {
            return Instant.now();
        }
        final String value = runContext.render(from).trim();

        if (inputFormat != null && !inputFormat.isBlank()) {
            final DateTimeFormatter parser = DateTimeFormatter.ofPattern(runContext.render(inputFormat));
            try {
                return LocalDateTime.parse(value, parser).atZone(zoneId).toInstant();
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDate.parse(value, parser).atStartOfDay(zoneId).toInstant();
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(
                        "Could not parse '" + value + "' with pattern '" + inputFormat + "'", e);
                }
            }
        }

        return autoDetect(value, zoneId);
    }

    /** Try common ISO-8601 shapes in order, from most-specific (has offset/zone) to least. */
    private static Instant autoDetect(final String value, final ZoneId zoneId) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return ZonedDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value).atZone(zoneId).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(value).atStartOfDay(zoneId).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "Could not auto-detect a date-time in '" + value + "'; set 'inputFormat'", e);
        }
    }

    /**
     * @param formatted    the value rendered with the output pattern in the target zone
     * @param iso          the instant as an ISO-8601 UTC string
     * @param epochMilli   milliseconds since the Unix epoch
     * @param epochSecond  seconds since the Unix epoch
     */
    public record DateOutput(String formatted, String iso, long epochMilli, long epochSecond)
        implements Output {
    }
}
