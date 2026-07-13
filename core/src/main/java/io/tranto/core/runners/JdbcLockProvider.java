package io.tranto.core.runners;

import io.tranto.core.jdbc.JdbcLock;

/**
 * Cluster-wide {@link LockProvider} for the distributed engine. For each key it takes the per-JVM
 * lock first (so threads on the same node serialise cheaply) and then a {@link JdbcLock} lease on
 * {@code exec:<key>} (so different executor nodes serialise too). The composition is what closes the
 * "the per-execution lock only spans one JVM" gap: with two executor nodes pointed at one database,
 * they can no longer read→mutate→write the same execution concurrently.
 *
 * <p>The DB lease has a TTL so a crashed holder cannot wedge an execution forever; a live holder that
 * runs longer than the TTL renews it while it works.</p>
 */
public class JdbcLockProvider implements LockProvider {

    /** How long a held execution lease lives before a crashed holder's grip expires. */
    private static final long LEASE_MS = 30_000;
    /** Renew the lease this often while the action runs (well under {@link #LEASE_MS}). */
    private static final long RENEW_MS = 10_000;
    /** Backoff between acquisition attempts while another node holds the lock. */
    private static final long RETRY_MS = 20;

    private final LockProvider local = new InMemory();
    private final JdbcLock jdbcLock;
    private final String holderId;

    public JdbcLockProvider(final JdbcLock jdbcLock, final String holderId) {
        this.jdbcLock = jdbcLock;
        this.holderId = holderId;
    }

    @Override
    public void withLock(final String key, final Runnable action) {
        local.withLock(key, () -> {
            String lockName = "exec:" + key;
            acquire(lockName);
            java.util.concurrent.ScheduledFuture<?> renewal = renewer(lockName);
            try {
                action.run();
            } finally {
                if (renewal != null) {
                    renewal.cancel(false);
                }
                jdbcLock.release(lockName, holderId);
            }
        });
    }

    private void acquire(final String lockName) {
        while (!jdbcLock.tryAcquire(lockName, holderId, LEASE_MS)) {
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // give up waiting; the local lock still serialises same-node threads
            }
        }
    }

    private java.util.concurrent.ScheduledFuture<?> renewer(final String lockName) {
        return RENEWERS.scheduleAtFixedRate(
            () -> jdbcLock.tryAcquire(lockName, holderId, LEASE_MS),
            RENEW_MS, RENEW_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static final java.util.concurrent.ScheduledExecutorService RENEWERS =
        java.util.concurrent.Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "tranto-lock-renew");
            t.setDaemon(true);
            return t;
        });
}
