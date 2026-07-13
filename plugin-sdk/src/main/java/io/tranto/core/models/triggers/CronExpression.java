package io.tranto.core.models.triggers;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;

/**
 * A minimal but correct standard 5-field cron parser: minute, hour, day-of-month, month,
 * day-of-week. Supports {@code *}, step values like {@code every-n}, {@code a-b} ranges,
 * stepped ranges, and {@code a,b,c} lists. Day-of-week is 0 to 6 with 0 = Sunday.
 *
 * <p>Self-contained (no external cron library) so the SDK stays dependency-light. {@code next}
 * finds the next matching minute by forward search, bounded to four years, beyond which the
 * expression is treated as never firing again.</p>
 */
public final class CronExpression {

    private final BitSet minutes = new BitSet(60);
    private final BitSet hours = new BitSet(24);
    private final BitSet daysOfMonth = new BitSet(32);   // 1..31
    private final BitSet months = new BitSet(13);        // 1..12
    private final BitSet daysOfWeek = new BitSet(7);     // 0..6 (0 = Sunday)
    private final boolean domRestricted;
    private final boolean dowRestricted;
    private final String expression;

    public CronExpression(final String expression) {
        this.expression = expression;
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException(
                "Cron expression must have 5 fields (min hour dom month dow): '" + expression + "'");
        }
        parse(fields[0], minutes, 0, 59);
        parse(fields[1], hours, 0, 23);
        parse(fields[2], daysOfMonth, 1, 31);
        parse(fields[3], months, 1, 12);
        parse(fields[4], daysOfWeek, 0, 6);
        this.domRestricted = !fields[2].equals("*");
        this.dowRestricted = !fields[4].equals("*");
    }

    /**
     * @param after the moment to search strictly after
     * @return the next matching time (seconds/nanos zeroed), or {@code null} if none within 4 years
     */
    public ZonedDateTime next(final ZonedDateTime after) {
        ZonedDateTime candidate = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        ZonedDateTime limit = candidate.plusYears(4);
        while (candidate.isBefore(limit)) {
            if (matches(candidate)) {
                return candidate;
            }
            candidate = candidate.plusMinutes(1);
        }
        return null;
    }

    private boolean matches(final ZonedDateTime time) {
        if (!minutes.get(time.getMinute()) || !hours.get(time.getHour())
            || !months.get(time.getMonthValue())) {
            return false;
        }
        int dom = time.getDayOfMonth();
        int dow = time.getDayOfWeek().getValue() % 7; // java: Mon=1..Sun=7 -> cron: Sun=0..Sat=6
        boolean domOk = daysOfMonth.get(dom);
        boolean dowOk = daysOfWeek.get(dow);
        if (domRestricted && dowRestricted) {
            return domOk || dowOk; // classic cron: either day field may match
        }
        if (domRestricted) {
            return domOk;
        }
        if (dowRestricted) {
            return dowOk;
        }
        return true;
    }

    private static void parse(final String field, final BitSet target, final int min, final int max) {
        for (String part : field.split(",")) {
            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                step = Integer.parseInt(part.substring(slash + 1));
                range = part.substring(0, slash);
            }
            int lo;
            int hi;
            if (range.equals("*")) {
                lo = min;
                hi = max;
            } else if (range.contains("-")) {
                String[] bounds = range.split("-");
                lo = Integer.parseInt(bounds[0]);
                hi = Integer.parseInt(bounds[1]);
            } else {
                lo = Integer.parseInt(range);
                hi = lo;
            }
            if (lo < min || hi > max || lo > hi || step < 1) {
                throw new IllegalArgumentException("Invalid cron field part '" + part + "'");
            }
            for (int v = lo; v <= hi; v += step) {
                target.set(v);
            }
        }
    }

    @Override
    public String toString() {
        return expression;
    }
}
