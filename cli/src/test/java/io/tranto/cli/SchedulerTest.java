package io.tranto.cli;

import io.tranto.core.models.flows.Flow;
import io.tranto.core.models.triggers.AbstractTrigger;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.schedulers.Scheduler;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.plugin.core.CorePlugins;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The scheduler fires a schedulable trigger exactly once per due window and advances it. */
class SchedulerTest {

    private Flow parse(final String yaml) throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new YamlFlowParser(new JacksonMapper(registry)).parse(yaml);
    }

    @Test
    void firesTriggerWhenDueAndDoesNotDoubleFire() throws Exception {
        Flow flow = parse("""
            id: scheduled
            namespace: dev
            triggers:
              - id: minutely
                type: io.tranto.plugin.core.trigger.Schedule
                cron: "* * * * *"
                timezone: UTC
            tasks:
              - id: hello
                type: io.tranto.plugin.core.log.Log
                message: "tick"
            """);

        AtomicInteger fires = new AtomicInteger();
        try (Scheduler scheduler = new Scheduler((f, t) -> fires.incrementAndGet())) {
            ZonedDateTime t0 = ZonedDateTime.of(2026, 7, 5, 10, 0, 30, 0, ZoneOffset.UTC);
            scheduler.register(flow, t0);
            assertThat(scheduler.scheduledCount()).isEqualTo(1);

            // Next fire is 10:01:00. A tick before it does nothing; a tick at/after it fires once.
            scheduler.tick(ZonedDateTime.of(2026, 7, 5, 10, 0, 45, 0, ZoneOffset.UTC));
            assertThat(fires.get()).isZero();

            scheduler.tick(ZonedDateTime.of(2026, 7, 5, 10, 1, 5, 0, ZoneOffset.UTC));
            assertThat(fires.get()).isEqualTo(1);

            // Same window again — must not double-fire (next was advanced to 10:02).
            scheduler.tick(ZonedDateTime.of(2026, 7, 5, 10, 1, 30, 0, ZoneOffset.UTC));
            assertThat(fires.get()).isEqualTo(1);

            // The following minute fires again.
            scheduler.tick(ZonedDateTime.of(2026, 7, 5, 10, 2, 5, 0, ZoneOffset.UTC));
            assertThat(fires.get()).isEqualTo(2);
        }
    }

    @Test
    void disabledTriggerIsNotScheduled() throws Exception {
        Flow flow = parse("""
            id: scheduled_off
            namespace: dev
            triggers:
              - id: minutely
                type: io.tranto.plugin.core.trigger.Schedule
                cron: "* * * * *"
                disabled: true
            tasks:
              - id: hello
                type: io.tranto.plugin.core.log.Log
                message: "tick"
            """);

        AtomicInteger fires = new AtomicInteger();
        try (Scheduler scheduler = new Scheduler((f, t) -> fires.incrementAndGet())) {
            scheduler.register(flow, ZonedDateTime.now());
            assertThat(scheduler.scheduledCount()).isZero();
        }
    }
}
