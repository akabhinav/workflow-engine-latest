package io.tranto.core.schedulers;

import io.tranto.core.models.flows.Flow;
import io.tranto.core.models.triggers.AbstractTrigger;
import io.tranto.core.models.triggers.Schedulable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Evaluates flows' time-based triggers and starts executions when they come due. It never blocks a
 * thread waiting for a schedule — it keeps, per (flow, trigger), the next fire time, and a periodic
 * tick compares it against the clock. Firing is delegated to a callback (the engine submits the
 * execution), keeping the scheduler independent of how executions are created.
 *
 * <p>{@link #tick(ZonedDateTime)} is the pure, deterministic core (call it with a controlled clock
 * in tests); {@link #start()} just drives it once a second on a daemon thread.</p>
 */
public class Scheduler implements AutoCloseable {

    /** One registered schedulable trigger and the next moment it should fire. */
    private static final class Entry {
        final Flow flow;
        final AbstractTrigger trigger;
        volatile ZonedDateTime nextFire;

        Entry(final Flow flow, final AbstractTrigger trigger, final ZonedDateTime nextFire) {
            this.flow = flow;
            this.trigger = trigger;
            this.nextFire = nextFire;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final BiConsumer<Flow, AbstractTrigger> onFire;
    /** Only fire when this returns true — the leader gate for multi-node clusters. */
    private final java.util.function.BooleanSupplier isLeader;
    private final ScheduledExecutorService ticker = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "tranto-scheduler");
        t.setDaemon(true);
        return t;
    });

    /**
     * A single-node scheduler that always fires (the standalone engine).
     *
     * @param onFire invoked when a trigger comes due, to create the execution (flow, trigger)
     */
    public Scheduler(final BiConsumer<Flow, AbstractTrigger> onFire) {
        this(onFire, () -> true);
    }

    /**
     * @param onFire   invoked when a trigger comes due, to create the execution (flow, trigger)
     * @param isLeader gate consulted before each tick; in a cluster only the elected leader node's
     *                 gate returns true, so cron triggers fire exactly once per due window
     */
    public Scheduler(final BiConsumer<Flow, AbstractTrigger> onFire,
                     final java.util.function.BooleanSupplier isLeader) {
        this.onFire = onFire;
        this.isLeader = isLeader;
    }

    /**
     * Register a flow's schedulable triggers, seeding each one's next fire time relative to
     * {@code from}. Re-registering the same flow refreshes its trigger definitions but preserves the
     * already-computed next fire time so a redeploy doesn't skip or double a schedule.
     */
    public void register(final Flow flow, final ZonedDateTime from) {
        List<AbstractTrigger> triggers = flow.getTriggers();
        if (triggers == null) {
            return;
        }
        for (AbstractTrigger trigger : triggers) {
            if (trigger.isDisabled() || !(trigger instanceof Schedulable schedulable)) {
                continue;
            }
            String key = keyFor(flow, trigger);
            Entry existing = entries.get(key);
            ZonedDateTime next = existing != null ? existing.nextFire : schedulable.nextEvaluationDate(from);
            entries.put(key, new Entry(flow, trigger, next));
        }
    }

    /**
     * Fire every trigger whose next fire time is at or before {@code now}, then advance it. Only the
     * leader node actually fires; followers still advance their next-fire clocks so a leadership change
     * does not replay already-elapsed windows (no double execution on failover).
     */
    public void tick(final ZonedDateTime now) {
        boolean leader = isLeader.getAsBoolean();
        for (Entry entry : entries.values()) {
            ZonedDateTime next = entry.nextFire;
            if (next == null || now.isBefore(next)) {
                continue;
            }
            try {
                if (leader) {
                    onFire.accept(entry.flow, entry.trigger);
                }
            } finally {
                entry.nextFire = ((Schedulable) entry.trigger).nextEvaluationDate(now);
            }
        }
    }

    /** Start the once-a-second background tick. */
    public void start() {
        ticker.scheduleAtFixedRate(() -> {
            try {
                tick(ZonedDateTime.now());
            } catch (Exception ignored) {
                // a bad trigger must not kill the scheduler thread
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** @return how many schedulable triggers are currently registered. */
    public int scheduledCount() {
        return entries.size();
    }

    private static String keyFor(final Flow flow, final AbstractTrigger trigger) {
        return flow.getTenantId() + "|" + flow.getNamespace() + "|" + flow.getId() + "|" + trigger.getId();
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }
}
