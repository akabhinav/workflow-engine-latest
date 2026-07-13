package io.tranto.cli;

import io.tranto.core.models.triggers.CronExpression;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic coverage for the 5-field cron parser. */
class CronExpressionTest {

    private ZonedDateTime at(final int y, final int mo, final int d, final int h, final int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC);
    }

    @Test
    void dailyAtTwoAmFindsNextOccurrence() {
        CronExpression cron = new CronExpression("0 2 * * *");
        assertThat(cron.next(at(2026, 7, 5, 1, 30))).isEqualTo(at(2026, 7, 5, 2, 0));
        assertThat(cron.next(at(2026, 7, 5, 2, 0))).isEqualTo(at(2026, 7, 6, 2, 0)); // strictly after
    }

    @Test
    void everyFifteenMinutesSteps() {
        CronExpression cron = new CronExpression("*/15 * * * *");
        assertThat(cron.next(at(2026, 7, 5, 10, 7))).isEqualTo(at(2026, 7, 5, 10, 15));
        assertThat(cron.next(at(2026, 7, 5, 10, 55))).isEqualTo(at(2026, 7, 5, 11, 0));
    }

    @Test
    void everyMinuteAdvancesByOne() {
        CronExpression cron = new CronExpression("* * * * *");
        assertThat(cron.next(at(2026, 7, 5, 10, 7))).isEqualTo(at(2026, 7, 5, 10, 8));
    }

    @Test
    void rejectsMalformedExpression() {
        assertThatThrownBy(() -> new CronExpression("0 2 * *")) // only 4 fields
            .isInstanceOf(IllegalArgumentException.class);
    }
}
