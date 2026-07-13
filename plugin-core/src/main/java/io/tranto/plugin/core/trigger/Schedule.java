package io.tranto.plugin.core.trigger;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.triggers.AbstractTrigger;
import io.tranto.core.models.triggers.CronExpression;
import io.tranto.core.models.triggers.Schedulable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Fires an execution on a cron schedule. The scheduler asks {@link #nextEvaluationDate} for the next
 * fire time and creates one execution when the clock reaches it.
 *
 * <pre>
 * triggers:
 *   - id: nightly
 *     type: io.tranto.plugin.core.trigger.Schedule
 *     cron: "0 2 * * *"     # every day at 02:00
 * </pre>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Trigger a flow on a cron schedule")
public class Schedule extends AbstractTrigger implements Schedulable {

    /** Standard 5-field cron expression (minute hour day-of-month month day-of-week). */
    @PluginProperty
    private String cron;

    /** Optional IANA timezone the cron is evaluated in (defaults to the system zone). */
    @PluginProperty
    private String timezone;

    @Override
    public ZonedDateTime nextEvaluationDate(final ZonedDateTime after) {
        ZoneId zone = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone);
        ZonedDateTime from = after.withZoneSameInstant(zone);
        return new CronExpression(cron).next(from);
    }
}
